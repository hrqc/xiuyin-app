param([string]$Serial = $env:ANDROID_SERIAL)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'emulator-selection.ps1')

function ConvertFrom-InstrumentationOutput([string]$Output, [int]$ExitCode) {
    $cases = @()
    $problems = @()
    $block = @{}
    $continuation = $null
    $startedCases = @{}
    $finishedCases = @{}
    $declaredCounts = @()
    $finalCodes = @()
    foreach ($line in ($Output -split '\r?\n')) {
        if ($line -match '^INSTRUMENTATION_STATUS: ([^=]+)=(.*)$') {
            $continuation = $Matches[1]
            $block[$continuation] = $Matches[2]
            continue
        }
        if ($line -match '^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$') {
            $status = [int]$Matches[1]
            $className = [string]$block['class']
            $testName = [string]$block['test']
            $key = $className + [char]0 + $testName
            if ($block.ContainsKey('numtests')) {
                $declared = 0
                if ([int]::TryParse([string]$block['numtests'], [ref]$declared) -and $declared -gt 0) {
                    $declaredCounts += $declared
                } else { $problems += '状态块的 numtests 无效。' }
            }
            if ([string]::IsNullOrWhiteSpace($className) -or [string]::IsNullOrWhiteSpace($testName)) {
                $problems += "状态码 $status 缺少 class/test，无法识别真实用例。"
            } elseif ($status -eq 1) {
                if ($startedCases.ContainsKey($key) -or $finishedCases.ContainsKey($key)) {
                    $problems += "重复开始用例：$className#$testName"
                }
                $startedCases[$key] = $true
            } elseif ($status -in @(0, -1, -2, -3, -4)) {
                if ($finishedCases.ContainsKey($key)) {
                    $problems += "重复结束用例：$className#$testName"
                } else {
                    if ($status -in @(0, -1, -2) -and -not $startedCases.ContainsKey($key)) {
                        $problems += "用例没有开始记录：$className#$testName"
                    }
                    $finishedCases[$key] = $true
                    $cases += [pscustomobject][ordered]@{
                        className = $className; testName = $testName; statusCode = $status
                        stack = [string]$block['stack']
                    }
                }
            } else { $problems += "未支持的用例状态码：$status" }
            $block = @{}
            $continuation = $null
            continue
        }
        if ($line -match '^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$') {
            $finalCodes += [int]$Matches[1]
            $continuation = $null
            continue
        }
        if ($line -match '^INSTRUMENTATION_(FAILED|ABORTED):') {
            $problems += $line
            $continuation = $null
            continue
        }
        if ($line -match '^INSTRUMENTATION_RESULT:') {
            $continuation = $null
            continue
        }
        if ($continuation -eq 'stack') { $block['stack'] += "`n" + $line }
    }
    if ($block.Count -gt 0) { $problems += '存在没有结束状态码的用例块。' }
    foreach ($key in $startedCases.Keys) {
        if (-not $finishedCases.ContainsKey($key)) { $problems += '有已开始但未结束的用例。' }
    }
    $okMatches = [regex]::Matches($Output, '(?m)^\s*OK\s+\((\d+)\s+tests?\)\s*$')
    $finalCount = 0
    if ($okMatches.Count -eq 1) { $finalCount = [int]$okMatches[0].Groups[1].Value }
    if ($okMatches.Count -ne 1 -or $finalCount -lt 1) { $problems += '缺少唯一且数量大于零的 OK (N tests) 完成摘要。' }
    if ($cases.Count -ne $finalCount) { $problems += "完整用例数 $($cases.Count) 与完成摘要 $finalCount 不一致。" }
    if ($finalCodes.Count -ne 1 -or $finalCodes[0] -ne -1) { $problems += '缺少唯一的 INSTRUMENTATION_CODE: -1 正常完成码。' }
    if ($ExitCode -ne 0) { $problems += "ADB 进程退出码为 $ExitCode。" }
    foreach ($count in $declaredCounts) {
        if ($count -ne $finalCount) { $problems += '状态块 numtests 与最终数量不一致。'; break }
    }
    $failures = @($cases | Where-Object { $_.statusCode -eq -2 }).Count
    $errors = @($cases | Where-Object { $_.statusCode -eq -1 }).Count
    $skipped = @($cases | Where-Object { $_.statusCode -in @(-3, -4) }).Count
    if ($failures -gt 0 -or $errors -gt 0 -or $skipped -gt 0) { $problems += '仪器测试存在失败、异常或跳过。' }
    return [pscustomobject][ordered]@{
        status = $(if ($problems.Count -eq 0) { 'passed' } else { 'failed' })
        cases = @($cases); problems = @($problems); declaredTests = $finalCount
        tests = $cases.Count; failures = $failures; errors = $errors; skipped = $skipped
        exitCode = $ExitCode; finalCodes = @($finalCodes)
    }
}

function Write-InstrumentationXml($Result, [string]$Path, [string]$Raw, [datetime]$StartedAt, [double]$Duration) {
    $settings = New-Object Xml.XmlWriterSettings
    $settings.Encoding = New-Object Text.UTF8Encoding($true)
    $settings.Indent = $true
    $writer = [Xml.XmlWriter]::Create($Path, $settings)
    try {
        $writer.WriteStartDocument()
        $writer.WriteStartElement('testsuite')
        $writer.WriteAttributeString('name', 'com.xiuyin.app.NativeInstrumentation')
        foreach ($key in @('tests', 'failures', 'errors', 'skipped')) { $writer.WriteAttributeString($key, [string]$Result.$key) }
        $writer.WriteAttributeString('timestamp', $StartedAt.ToUniversalTime().ToString('o'))
        $writer.WriteAttributeString('time', $Duration.ToString('0.000', [Globalization.CultureInfo]::InvariantCulture))
        $writer.WriteStartElement('properties')
        $writer.WriteStartElement('property')
        $writer.WriteAttributeString('name', 'xiuyin.runner.status')
        $writer.WriteAttributeString('value', $Result.status)
        $writer.WriteEndElement()
        $writer.WriteEndElement()
        foreach ($case in $Result.cases) {
            $writer.WriteStartElement('testcase')
            $writer.WriteAttributeString('classname', $case.className)
            $writer.WriteAttributeString('name', $case.testName)
            if ($case.statusCode -in @(-1, -2, -3, -4)) {
                $element = if ($case.statusCode -eq -1) { 'error' } elseif ($case.statusCode -eq -2) { 'failure' } else { 'skipped' }
                $writer.WriteStartElement($element)
                $writer.WriteAttributeString('message', 'INSTRUMENTATION_STATUS_CODE: ' + $case.statusCode)
                $writer.WriteString($case.stack)
                $writer.WriteEndElement()
            }
            $writer.WriteEndElement()
        }
        $writer.WriteElementString('system-out', $Raw)
        if ($Result.problems.Count -gt 0) { $writer.WriteElementString('system-err', ($Result.problems -join "`n")) }
        $writer.WriteEndElement()
        $writer.WriteEndDocument()
    } finally { $writer.Dispose() }
}

function Get-ArtifactSha256([string]$Path) {
    # Windows PowerShell may lack the Get-FileHash module in the host environment.
    # Read exact artifact bytes without changing global module paths or loading extras.
    $stream = [IO.File]::OpenRead($Path)
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $hasher.Dispose(); $stream.Dispose() }
}

function Get-ClavierPcmSampleCount([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $reader = New-Object IO.BinaryReader($stream)
    try {
        if ($stream.Length -lt 44 -or [Text.Encoding]::ASCII.GetString($reader.ReadBytes(4)) -cne 'RIFF') { throw 'Clavier 输入缺少 WAV RIFF。' }
        $riffBytes = [long]$reader.ReadUInt32()
        if ($riffBytes + 8 -ne $stream.Length -or [Text.Encoding]::ASCII.GetString($reader.ReadBytes(4)) -cne 'WAVE') { throw 'Clavier WAV 长度或类型错误。' }
        $formatValid = $false
        $samples = $null
        while ($stream.Position + 8 -le $stream.Length) {
            $chunk = [Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
            $length = [long]$reader.ReadUInt32()
            $offset = $stream.Position
            if ($length -gt $stream.Length - $offset) { throw 'Clavier WAV 区块越界。' }
            if ($chunk -ceq 'fmt ') {
                if ($length -lt 16) { throw 'Clavier WAV fmt 长度错误。' }
                $pcm = $reader.ReadUInt16(); $channels = $reader.ReadUInt16(); $rate = $reader.ReadUInt32()
                $bytesPerSecond = $reader.ReadUInt32(); $alignment = $reader.ReadUInt16(); $bits = $reader.ReadUInt16()
                $formatValid = $pcm -eq 1 -and $channels -eq 1 -and $rate -eq 48000 -and $bytesPerSecond -eq 96000 -and $alignment -eq 2 -and $bits -eq 16
                if (-not $formatValid) { throw 'Clavier 输入必须为真实 48kHz 单声道 PCM16。' }
            } elseif ($chunk -ceq 'data') {
                if (-not $formatValid -or $length -le 0 -or $length % 2 -ne 0 -or $null -ne $samples) { throw 'Clavier WAV data 区块无效或重复。' }
                $samples = [long]($length / 2)
            }
            $next = $offset + $length + ($length % 2)
            if ($next -gt $stream.Length) { throw 'Clavier WAV 填充区块越界。' }
            $stream.Position = $next
        }
        if ($null -eq $samples) { throw 'Clavier WAV 缺少真实 PCM data。' }
        return $samples
    } finally { $reader.Dispose(); $stream.Dispose() }
}

function Get-OmrIndependentManifestEntries($Manifest) {
    if ($Manifest.schemaVersion -ne 1 -or $Manifest.musicalAcceptance -cne 'UNVERIFIED') { throw '独立OMR清单版本或音乐边界无效。' }
    $entries = @($Manifest.files)
    if ($entries.Count -eq 0) { throw '独立OMR清单没有真实文件。' }
    $seen = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $entries) {
        $name = [string]$entry.path
        if ($name -notmatch '^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$' -or @($name.Split('/') | Where-Object { $_ -in @('.', '..') }).Count -gt 0 -or
            -not $seen.Add($name) -or $name -eq 'evidence-manifest.json' -or [IO.Path]::GetFileName($name) -like 'TEST-*.xml') {
            throw ('独立OMR清单有越界、重复或测试计数冲突路径：' + $name)
        }
        if (($entry.bytes -isnot [int] -and $entry.bytes -isnot [long]) -or $entry.bytes -lt 0 -or [string]$entry.sha256 -cnotmatch '^[a-f0-9]{64}$') {
            throw ('独立OMR清单缺真实字节/SHA：' + $name)
        }
    }
    return $entries
}

function Assert-OmrIndependentReport($Report, $Context, $Manifest) {
    if ($Report.schemaVersion -ne 1 -or $Report.runId -cne $Context.runId -or $Report.phase -cne 'IMPORT' -or $Report.case -cne 'S02' -or
        $Report.status -cne 'INFERENCE_AND_REPOSITORY_RELOAD_PASSED' -or $Report.musicalAcceptance -cne 'UNVERIFIED' -or
        $Report.crossProcessReload -cne 'NOT_YET_RUN' -or $Report.teacherConfirmed -isnot [bool] -or $Report.teacherConfirmed -or
        $Report.reusedExisting -isnot [bool] -or $Report.reusedExisting -or $Report.repositoryReloadEqual -isnot [bool] -or -not $Report.repositoryReloadEqual -or
        $Report.revisionGeneration -ne 0 -or $Report.appSha256 -cne $Context.appSha256 -or $Report.testApkSha256 -cne $Context.testApkSha256 -or
        $Report.gitCommitAtBuild -cne $Context.gitCommit -or $Report.sourceSha256 -cne $Context.inputSha256 -or $Report.referenceSha256 -cne $Context.referenceSha256 -or
        $Report.sourceFile -cne $Context.inputFile -or $Report.referenceFile -cne $Context.referenceFile -or [string]$Report.sourceId -cnotmatch '^[a-f0-9]{64}$') {
        throw '独立OMR默认报告不是本次S02真实IMPORT/仓库冷加载，或身份/音乐边界不一致。'
    }
    if ([string]$Report.startedAt -notmatch 'Z$' -or [string]$Report.finishedAt -notmatch 'Z$') { throw '独立OMR报告缺实际UTC运行时间。' }
    $from = [DateTimeOffset]::Parse($Report.startedAt, [Globalization.CultureInfo]::InvariantCulture).ToUnixTimeMilliseconds()
    $until = [DateTimeOffset]::Parse($Report.finishedAt, [Globalization.CultureInfo]::InvariantCulture).ToUnixTimeMilliseconds()
    if ($from -lt $Context.deviceStartedAtMillis -or $until -lt $from -or $until -gt $Context.deviceFinishedAtMillis) { throw '独立OMR报告不在本次设备运行窗口内。' }
    $byName = @{}
    foreach ($entry in @(Get-OmrIndependentManifestEntries -Manifest $Manifest)) { $byName[[string]$entry.path] = $entry }
    $sourcePrefix = 'omr-sources/' + $Report.sourceId + '/'
    foreach ($pair in @(@('reference.json', $Context.referenceSha256), @('current-score.json', $Report.currentSha256),
        @('digital-score.json', $Report.currentSha256), @('metadata.json', $Report.metadataSha256),
        @(($sourcePrefix + 'raw-result.json'), $Report.rawSha256), @(($sourcePrefix + 'original.json'), $Report.originalSha256),
        @(($sourcePrefix + 'original-000.document'), $Context.inputSha256))) {
        if (-not $byName.ContainsKey($pair[0]) -or [string]$pair[1] -cnotmatch '^[a-f0-9]{64}$' -or $byName[$pair[0]].sha256 -cne $pair[1]) {
            throw ('独立OMR的Raw/Original/Current/原输入或元数据未完整绑定：' + $pair[0])
        }
    }
    foreach ($name in @('report.json', 'metadata-database.db', 'model-manifest.json')) {
        if (-not $byName.ContainsKey($name) -or $byName[$name].bytes -le 0) { throw ('独立OMR真实归档缺失：' + $name) }
    }
    if (@($byName.Keys | Where-Object { $_ -cmatch ('^' + [regex]::Escape($sourcePrefix) + 'page-\d{3}\.image$') }).Count -lt 1) { throw '独立OMR未保留Android真实渲染页。' }
    if ($Report.actual.rawEventCount -ne $Report.actual.convertedEventCount -or $Report.actual.noteCount -lt 1 -or $Report.actual.pageCount -lt 1) {
        throw '独立OMR模型未输出真实发声音符，或转换事件数量不守恒。'
    }
}

function Invoke-AdbCapture([string[]]$AdbArguments, [string]$LogPath) {
    Assert-XiuyinAdbTargetArguments -Arguments $AdbArguments -Serial $deviceSerial
    $lines = New-Object 'Collections.Generic.List[string]'
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $adb @AdbArguments 2>&1 | ForEach-Object {
            $line = $_.ToString()
            $lines.Add($line)
            Write-Host $line
        }
        $nativeCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    [IO.File]::WriteAllLines($LogPath, $lines.ToArray(), (New-Object Text.UTF8Encoding($true)))
    return [pscustomobject]@{ exitCode = $nativeCode; output = ($lines -join "`n"); log = $LogPath }
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$outputRoot = Join-Path $projectRoot 'app\build\outputs\androidTest-results\connected\adb'
$started = Get-Date
$previousUserHome = $env:ANDROID_USER_HOME
$exitCode = 1
$deviceSerial = $null
$result = $null
$failure = $null
$traceEvidence = $null
$boundaryEvidence = $null
$completionEvidence = $null
$omrEvidence = $null
$omrContext = $null
$omrDirectory = $null
$userPhotoContext = $null
$userPhotoDirectory = $null
$userPhotoEvidence = $null
$boundaryContext = $null
$boundaryDirectory = $null
try {
    New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
    $sdkLine = Get-Content -LiteralPath (Join-Path $projectRoot 'local.properties') | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
    if (-not $sdkLine) { throw 'local.properties 缺少 sdk.dir。' }
    $sdkRoot = ($sdkLine -replace '^sdk.dir=', '').Replace('\:', ':').Replace('\\', '\')
    $adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
    $appApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $testApk = Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    foreach ($path in @($adb, $appApk, $testApk)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "原生仪器测试缺少文件：$path" }
    }
    $env:ANDROID_USER_HOME = Join-Path $projectRoot 'tools\local\android'
    New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME -Force | Out-Null
    $listing = Invoke-AdbCapture -AdbArguments @('devices') -LogPath (Join-Path $outputRoot 'devices.log')
    if ($listing.exitCode -ne 0) { throw "ADB devices 失败：$($listing.exitCode)" }
    $deviceSerial = Select-XiuyinEmulator -Listing $listing.output -RequestedSerial $Serial
    foreach ($apk in @($appApk, $testApk)) {
        Write-Host ('安装测试所需 APK：' + [IO.Path]::GetFileName($apk))
        $install = Invoke-AdbCapture -AdbArguments @('-s', $deviceSerial, 'install', '-r', $apk) -LogPath (Join-Path $outputRoot ([IO.Path]::GetFileNameWithoutExtension($apk) + '-install.log'))
        if ($install.exitCode -ne 0 -or $install.output -notmatch '(?m)^\s*Success\s*$') { throw "APK 安装未成功：$apk" }
    }
    & 'C:\Python314\python.exe' (Join-Path $PSScriptRoot 'prepare_device_fixtures.py') --serial $deviceSerial --report (Join-Path $outputRoot 'input-manifest.json')
    if ($LASTEXITCODE -ne 0) { throw '真实测试素材准备失败，禁止用替代输入放行' }
    # Every default @Test runs with explicit immutable Autopiano inputs; no test exclusion/skip.
    $traceRunId = 'device-' + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmss').ToLowerInvariant() + '-' + [guid]::NewGuid().ToString('N')
    $traceDirectory = Join-Path $outputRoot ('full-trace\' + $traceRunId)
    New-Item -ItemType Directory -Path $traceDirectory | Out-Null
    $traceContext = [ordered]@{
        runId = $traceRunId; device = $deviceSerial
        appSha256 = (Get-ArtifactSha256 -Path $appApk)
        testApkSha256 = (Get-ArtifactSha256 -Path $testApk)
        wavSha256 = '7788b1af50d53349aa5a2874d830d45854cb7e2fd2a467a48f609d7fae39ce14'
        timelineSha256 = '0fbe2047fa83c7b7773839f10e41d87f202fa68e21cc26b75eeca94ef8d49397'
        modelSha256 = '69ac3567d8ee5fda620225a49c62839c3e82165e89d54d1af63caac877605dfc'
        fixture = 'AUTOPIANO_REAL_FROZEN_PCM'; musicalAcceptance = 'NOT_CERTIFIED_BY_DIAGNOSTIC'
    }
    $traceContext | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $traceDirectory 'trace-context.json') -Encoding utf8
    $traceEvidence = [ordered]@{ runId = $traceRunId; directory = $traceDirectory; status = 'NOT_VERIFIED' }
    # Supply the frozen real recording to the default suite, preserving its no-skipped gate.
    # Every remote path is unique to this invocation and all ADB calls use the selected serial.
    $clavierSource = Join-Path $projectRoot 'reports\calibration-873c4d39-20261015\original\audio.wav'
    $clavierExpectedSha = '753c59bc3005d6c37cbb4a2078938c46d4293b690ae15a61d75203bf2b75280d'
    if (-not (Test-Path -LiteralPath $clavierSource -PathType Leaf) -or (Get-ArtifactSha256 -Path $clavierSource) -cne $clavierExpectedSha) { throw 'Clavier 第一份真实输入缺失或冻结 SHA 不符；禁止替代音频或跳过。' }
    $clavierSamples = Get-ClavierPcmSampleCount -Path $clavierSource
    if ($clavierSamples -ne 1995776) { throw 'Clavier 第一份真实输入样本数不等于冻结的 1,995,776。' }
    $clavierDirectory = Join-Path $traceDirectory 'clavier-input'
    New-Item -ItemType Directory -Path $clavierDirectory | Out-Null
    $clavierRemoteDirectory = 'files/clavier-input-' + $traceRunId
    $clavierRemotePrivate = $clavierRemoteDirectory + '/audio.wav'
    $clavierRemoteTemporary = '/data/local/tmp/xiuyin-clavier-' + $traceRunId + '.wav'
    $clavierWav = '/data/user/0/com.xiuyin.app/' + $clavierRemotePrivate
    $clavierContext = [ordered]@{
        runId = $traceRunId; device = $deviceSerial; sourcePath = $clavierSource
        sourceSha256 = $clavierExpectedSha; samples = $clavierSamples; sampleRateHz = 48000; channels = 1; pcmBits = 16
        temporaryPath = $clavierRemoteTemporary; targetPath = $clavierWav
        appSha256 = $traceContext.appSha256; testApkSha256 = $traceContext.testApkSha256
        prepared = $false; musicalAcceptance = 'UNVERIFIED'; realTimePerformanceValidated = $false
    }
    $clavierContextPath = Join-Path $clavierDirectory 'input-context.json'
    $clavierContext | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $clavierContextPath -Encoding utf8
    $clavierMkdir = Invoke-AdbCapture -AdbArguments @('-s', $deviceSerial, 'shell', 'run-as', 'com.xiuyin.app', 'mkdir', $clavierRemoteDirectory) -LogPath (Join-Path $clavierDirectory 'mkdir.log')
    if ($clavierMkdir.exitCode -ne 0) { throw '不能建立 Clavier 本轮私有输入目录；不复用历史目录。' }
    $clavierPush = Invoke-AdbCapture -AdbArguments @('-s', $deviceSerial, 'push', $clavierSource, $clavierRemoteTemporary) -LogPath (Join-Path $clavierDirectory 'push.log')
    if ($clavierPush.exitCode -ne 0) { throw 'Clavier 本轮真实输入 push 失败。' }
    $clavierCopy = Invoke-AdbCapture -AdbArguments @('-s', $deviceSerial, 'shell', 'run-as', 'com.xiuyin.app', 'cp', $clavierRemoteTemporary, $clavierRemotePrivate) -LogPath (Join-Path $clavierDirectory 'copy.log')
    if ($clavierCopy.exitCode -ne 0) { throw 'Clavier 本轮真实输入复制到 App 私有目录失败。' }
    $clavierDeviceSha = Invoke-AdbCapture -AdbArguments @('-s', $deviceSerial, 'shell', 'run-as', 'com.xiuyin.app', 'sha256sum', $clavierRemotePrivate) -LogPath (Join-Path $clavierDirectory 'device-sha.log')
    if ($clavierDeviceSha.exitCode -ne 0 -or $clavierDeviceSha.output -notmatch '^([a-f0-9]{64})\s+' -or $Matches[1] -cne $clavierExpectedSha) { throw 'Clavier 设备私有输入字节 SHA 与本轮源文件不一致。' }
    $clavierContext.prepared = $true
    $clavierContext | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $clavierContextPath -Encoding utf8
    # Fixed device filenames must be bound to THIS invocation, never trusted as fresh by name.
    $boundaryDirectory = Join-Path $outputRoot ('field-frame-boundary\' + $traceRunId)
    New-Item -ItemType Directory -Path $boundaryDirectory | Out-Null
    $boundaryEvidence = [ordered]@{ runId = $traceRunId; directory = $boundaryDirectory; status = 'NOT_VERIFIED' }
    $commitOutput = & git -c ('safe.directory=' + $projectRoot) -C $projectRoot rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $commitOutput -notmatch '^[a-f0-9]{40}$') { throw '无法确认当前边界测试 Git Commit。' }
    $boundaryContext = [ordered]@{
        runId = $traceRunId; device = $deviceSerial; gitCommit = [string]$commitOutput
        appSha256 = $traceContext.appSha256; testApkSha256 = $traceContext.testApkSha256
        commands = @(); files = @(); instrumentationExitCode = $null
        scope = 'TEST DATA real model/component stop boundaries; not physical microphone, Activity background transition, or musical accuracy'
    }
    function Invoke-BoundaryCapture([string[]]$Arguments, [string]$Name, [string]$Directory = $boundaryDirectory, $Context = $boundaryContext) {
        $path = Join-Path $Directory $Name
        if (Test-Path -LiteralPath $path) { throw ('拒绝覆盖边界证据：' + $path) }
        $capture = Invoke-AdbCapture -AdbArguments $Arguments -LogPath $path
        $Context.commands += [ordered]@{ file = $Name; exitCode = $capture.exitCode; sha256 = (Get-ArtifactSha256 -Path $path) }
        return $capture
    }
    function Save-BoundaryInstalledIdentity([string]$Phase) {
        foreach ($entry in @(@('app', 'com.xiuyin.app', $traceContext.appSha256), @('tests', 'com.xiuyin.app.test', $traceContext.testApkSha256))) {
            $locations = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'shell', 'pm', 'path', $entry[1]) -Name ($Phase + '-' + $entry[0] + '-path.log')
            if ($locations.exitCode -ne 0 -or $locations.output.Trim() -notmatch '^package:(/data/app/[A-Za-z0-9_./+=~\-]+/base\.apk)$') { throw '边界测试 APK 安装路径异常。' }
            $installedPath = $Matches[1]
            $identity = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'shell', 'sha256sum', $installedPath) -Name ($Phase + '-' + $entry[0] + '-sha.log')
            if ($identity.exitCode -ne 0 -or $identity.output -notmatch '^([a-f0-9]{64})\s+' -or $Matches[1] -ne $entry[2]) { throw '边界测试实际安装 APK 与本次构建不同。' }
        }
    }
    # The independent S02 input never enters the existing exact-two-page 58/59 directory.
    $omrNonce = [guid]::NewGuid().ToString('N')
    $omrRunId = 's02-default-' + $omrNonce
    $omrInputDirectory = 'omr-independent-input-' + $omrNonce
    # Keep the host directory short: ADB pulls one file to a short temporary name before native placement.
    $omrDirectory = Join-Path $outputRoot ('omr\' + $omrNonce.Substring(0, 16))
    if (Test-Path -LiteralPath $omrDirectory) { throw '拒绝复用独立OMR本轮归档目录。' }
    New-Item -ItemType Directory -Path $omrDirectory | Out-Null
    $omrContext = [ordered]@{
        runId = $omrRunId; mainRunId = $traceRunId; device = $deviceSerial; gitCommit = $boundaryContext.gitCommit
        phase = 'IMPORT'; case = 'S02'; inputDirectory = $omrInputDirectory
        inputFile = 'score-02-treble-notes.jpg'; inputSha256 = 'ed68c9edeff8edfcfbab678559a4e3e110df1eeaa95d1a56fa9d8bfa86e3531a'
        referenceFile = 's02-single-notes-1beat.json'; referenceSha256 = '9c17cc9075736db43ef5f8eb68a4570cf05ea39c5daf0bfbfa46ccf4bc9e775b'
        appSha256 = $traceContext.appSha256; testApkSha256 = $traceContext.testApkSha256
        commands = @(); files = @(); musicalAcceptance = 'UNVERIFIED'; crossProcessReload = 'NOT_YET_RUN'
        scope = 'Default suite: real S02 IMPORT plus cold repository reload only; three-case cross-process gate must run separately.'
    }
    $omrEvidence = [ordered]@{ runId = $omrRunId; directory = $omrDirectory; status = 'NOT_VERIFIED'; musicalAcceptance = 'UNVERIFIED'; crossProcessReload = 'NOT_YET_RUN' }
    $omrInputBase = Join-Path $projectRoot 'reports\onlinepianist-validation-20260923'
    $omrInputs = @(@((Join-Path $omrInputBase ('inputs\' + $omrContext.inputFile)), $omrContext.inputFile, $omrContext.inputSha256),
        @((Join-Path $omrInputBase ('references\' + $omrContext.referenceFile)), $omrContext.referenceFile, $omrContext.referenceSha256))
    $makeInput = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'shell', 'run-as', 'com.xiuyin.app', 'mkdir', ('files/' + $omrInputDirectory)) -Name 'input-mkdir.log' -Directory $omrDirectory -Context $omrContext
    if ($makeInput.exitCode -ne 0) { throw '不能建立本次独立OMR输入目录；不复用历史目录。' }
    $inputIndex = 0
    foreach ($entry in $omrInputs) {
        if (-not (Test-Path -LiteralPath $entry[0] -PathType Leaf) -or (Get-ArtifactSha256 -Path $entry[0]) -cne $entry[2]) { throw '真实S02原图/参照缺失或SHA不符，禁止替代输入。' }
        $remoteTemporary = '/data/local/tmp/xiuyin-omr-' + $omrNonce + '-' + $entry[1]
        $remotePrivate = 'files/' + $omrInputDirectory + '/' + $entry[1]
        $pushed = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'push', $entry[0], $remoteTemporary) -Name ('input-' + $inputIndex + '-push.log') -Directory $omrDirectory -Context $omrContext
        if ($pushed.exitCode -ne 0) { throw '本次独立OMR真实输入push失败。' }
        $copied = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'shell', 'run-as', 'com.xiuyin.app', 'cp', $remoteTemporary, $remotePrivate) -Name ('input-' + $inputIndex + '-copy.log') -Directory $omrDirectory -Context $omrContext
        $checked = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'shell', 'run-as', 'com.xiuyin.app', 'sha256sum', $remotePrivate) -Name ('input-' + $inputIndex + '-sha.log') -Directory $omrDirectory -Context $omrContext
        if ($copied.exitCode -ne 0 -or $checked.exitCode -ne 0 -or $checked.output -notmatch '^([a-f0-9]{64})\s+' -or $Matches[1] -cne $entry[2]) { throw '设备真实S02输入原字节SHA不一致。' }
        $inputIndex++
    }
    # Exact newly supplied camera page: isolated nonce path, no substitution and no skipped test.
    $userPhotoSource = Join-Path $projectRoot 'reports\fieldtest7-development\user-photo-repro\user-original.jpg'
    $userPhotoExpectedSha = 'db00943990d83b96c4cb57be7919b9d22241a14b761c6379a82493db1ad3b450'
    if (-not (Test-Path -LiteralPath $userPhotoSource -PathType Leaf) -or (Get-ArtifactSha256 -Path $userPhotoSource) -cne $userPhotoExpectedSha) {
        throw '本次用户问题原图缺失或冻结 SHA 不符；禁止换图或跳过真实导入。'
    }
    $userPhotoNonce = [guid]::NewGuid().ToString('N')
    $userPhotoInputName = 'user-photo-' + $userPhotoNonce + '.jpg'
    $userPhotoPrivate = 'files/' + $userPhotoInputName
    $userPhotoTemporary = '/data/local/tmp/xiuyin-' + $userPhotoInputName
    $userPhotoDirectory = Join-Path $outputRoot ('user-photo\' + $userPhotoNonce.Substring(0, 16))
    if (Test-Path -LiteralPath $userPhotoDirectory) { throw '拒绝复用本次用户原图证据目录。' }
    New-Item -ItemType Directory -Path $userPhotoDirectory | Out-Null
    $userPhotoContext = [ordered]@{
        mainRunId = $traceRunId; device = $deviceSerial; gitCommit = $boundaryContext.gitCommit
        sourcePath = $userPhotoSource; sourceBytes = (Get-Item -LiteralPath $userPhotoSource).Length; sourceSha256 = $userPhotoExpectedSha
        inputName = $userPhotoInputName; temporaryPath = $userPhotoTemporary; privatePath = $userPhotoPrivate
        appSha256 = $traceContext.appSha256; testApkSha256 = $traceContext.testApkSha256
        prepared = $false; musicalAcceptance = 'UNVERIFIED'; commands = @(); files = @()
    }
    $userPhotoEvidence = [ordered]@{ directory = $userPhotoDirectory; status = 'NOT_VERIFIED'; musicalAcceptance = 'UNVERIFIED' }
    function Invoke-UserPhotoCapture([string[]]$Arguments, [string]$Name) {
        $capture = Invoke-BoundaryCapture -Arguments $Arguments -Name $Name -Directory $userPhotoDirectory -Context $userPhotoContext
        $userPhotoContext.commands[-1].arguments = @($Arguments)
        return $capture
    }
    $userPhotoAbsent = Invoke-UserPhotoCapture -Arguments @('-s', $deviceSerial, 'shell', 'run-as', 'com.xiuyin.app', 'test', '!', '-e', $userPhotoPrivate) -Name 'input-absent.log'
    if ($userPhotoAbsent.exitCode -ne 0) { throw '用户原图本轮私有名称已存在或无法确认；不覆盖。' }
    $userPhotoPush = Invoke-UserPhotoCapture -Arguments @('-s', $deviceSerial, 'push', $userPhotoSource, $userPhotoTemporary) -Name 'input-push.log'
    if ($userPhotoPush.exitCode -ne 0) { throw '用户问题原图 push 失败。' }
    $userPhotoCopy = Invoke-UserPhotoCapture -Arguments @('-s', $deviceSerial, 'shell', 'run-as', 'com.xiuyin.app', 'cp', $userPhotoTemporary, $userPhotoPrivate) -Name 'input-copy.log'
    if ($userPhotoCopy.exitCode -ne 0) { throw '用户问题原图复制到本轮私有路径失败。' }
    $userPhotoSha = Invoke-UserPhotoCapture -Arguments @('-s', $deviceSerial, 'shell', 'run-as', 'com.xiuyin.app', 'sha256sum', $userPhotoPrivate) -Name 'input-device-sha.log'
    if ($userPhotoSha.exitCode -ne 0 -or $userPhotoSha.output -notmatch '^([a-f0-9]{64})\s+' -or $Matches[1] -cne $userPhotoExpectedSha -or
        (Get-ArtifactSha256 -Path $userPhotoSource) -cne $userPhotoExpectedSha) { throw '本轮用户原图的源文件/设备私有文件 SHA 不一致。' }
    $userPhotoContext.prepared = $true
    $userPhotoContext | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $userPhotoDirectory 'input-context.json') -Encoding utf8
    Save-BoundaryInstalledIdentity -Phase 'before'
    $oldBoundary = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'shell', 'if [ -f /sdcard/Android/data/com.xiuyin.app/files/field-frame-boundary-result.json ]; then sha256sum /sdcard/Android/data/com.xiuyin.app/files/field-frame-boundary-result.json; else echo ABSENT; fi') -Name 'before-report-sha.log'
    if ($oldBoundary.exitCode -ne 0) { throw '无法读取边界测试旧报告状态。' }
    $beforeClock = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'shell', 'date', '+%s') -Name 'before-clock.log'
    if ($beforeClock.exitCode -ne 0 -or $beforeClock.output.Trim() -notmatch '^\d{10,}$') { throw '无法读取边界测试设备开始时间。' }
    $testStarted = Get-Date
    Write-Host ('运行相同 AndroidJUnitRunner 测试集：' + $deviceSerial)
    $instrumentation = Invoke-AdbCapture -AdbArguments @('-s', $deviceSerial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'omrInputDirectory', 'omr-evaluation-input', '-e', 'omrPhase', 'IMPORT', '-e', 'omrRunId', $omrRunId, '-e', 'omrCase', 'S02', '-e', 'omrIndependentInputDirectory', $omrInputDirectory, '-e', 'omrInputFile', $omrContext.inputFile, '-e', 'omrInputSha256', $omrContext.inputSha256, '-e', 'omrReferenceFile', $omrContext.referenceFile, '-e', 'omrReferenceSha256', $omrContext.referenceSha256, '-e', 'omrProblemInput', 'problem-score.jpg', '-e', 'omrUserPhotoInput', $userPhotoInputName, '-e', 'omrUserPhotoSha256', $userPhotoExpectedSha, '-e', 'omrReanalysisArchive', 'omr-legacy-problem', '-e', 'manualReviewScore', 'czerny-manual-review.musicxml', '-e', 'clavierWav', $clavierWav, '-e', 'phoneVideoWav', '/data/user/0/com.xiuyin.app/files/phone-video-20260923.wav', '-e', 'phoneVideoExpectedSha256', '777ff0c774277532ae61754afd4aea345d71e3be03c1e264fbf817ae50d9e858', '-e', 'roomPianoWav', '/data/user/0/com.xiuyin.app/files/practice-evaluation-input/room.wav', '-e', 'octaveBaselineWav', '/data/user/0/com.xiuyin.app/files/practice-evaluation-input/octave-regression.wav', '-e', 'octaveBaselineExpectedSha256', 'a047684a7e86a8fd4f4c3c8bc07d5be3903407bc1225f2eeb36eed42a8e21c20', '-e', 'practiceWav', '/data/user/0/com.xiuyin.app/files/practice-evaluation-input/website.wav', '-e', 'practiceTimeline', '/data/user/0/com.xiuyin.app/files/practice-evaluation-input/expected-timeline.json', '-e', 'traceRunId', $traceRunId, '-e', 'traceWav', '/data/user/0/com.xiuyin.app/files/practice-evaluation-input/website.wav', '-e', 'traceTimeline', '/data/user/0/com.xiuyin.app/files/practice-evaluation-input/expected-timeline.json', '-e', 'traceWavSha256', $traceContext.wavSha256, '-e', 'traceTimelineSha256', $traceContext.timelineSha256, '-e', 'traceAppSha256', $traceContext.appSha256, '-e', 'traceModelSha256', $traceContext.modelSha256, 'com.xiuyin.app.test/androidx.test.runner.AndroidJUnitRunner') -LogPath (Join-Path $outputRoot 'instrumentation.log')
    $duration = ((Get-Date) - $testStarted).TotalSeconds
    $afterClock = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'shell', 'date', '+%s') -Name 'after-clock.log'
    $result = ConvertFrom-InstrumentationOutput -Output $instrumentation.output -ExitCode $instrumentation.exitCode
    Write-InstrumentationXml -Result $result -Path (Join-Path $outputRoot 'TEST-instrumentation.xml') -Raw $instrumentation.output -StartedAt $testStarted -Duration $duration
    # Archive failures too; boundary failure must not prevent preserving the existing full trace.
    $boundaryFailure = $null
    try {
        $boundaryContext.instrumentationExitCode = $instrumentation.exitCode
        foreach ($name in @('instrumentation.log', 'TEST-instrumentation.xml')) {
            # Evidence XML is not another executed suite; recursive TEST-*.xml discovery must see the main suite only once.
            $archiveName = if ($name -eq 'TEST-instrumentation.xml') { 'instrumentation-result.xml' } else { $name }
            $destination = Join-Path $boundaryDirectory $archiveName
            if (Test-Path -LiteralPath $destination) { throw ('拒绝覆盖边界证据：' + $name) }
            Copy-Item -LiteralPath (Join-Path $outputRoot $name) -Destination $destination
            $boundaryContext.files += [ordered]@{ file = $archiveName; sha256 = (Get-ArtifactSha256 -Path $destination) }
        }
        $pullFailures = @()
        foreach ($name in @('field-frame-boundary-result.json', 'field-frame-boundary-export.zip')) {
            $destination = Join-Path $boundaryDirectory $name
            if (Test-Path -LiteralPath $destination) { throw ('拒绝覆盖边界证据：' + $name) }
            $remote = '/sdcard/Android/data/com.xiuyin.app/files/' + $name
            $pulled = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'pull', $remote, $destination) -Name ($name + '-pull.log')
            $remoteSha = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'shell', 'sha256sum', $remote) -Name ($name + '-device-sha.log')
            if ($pulled.exitCode -ne 0 -or -not (Test-Path -LiteralPath $destination -PathType Leaf)) {
                $pullFailures += ('缺少本次边界证据：' + $name)
            } elseif ($remoteSha.exitCode -ne 0 -or $remoteSha.output -notmatch '^([a-f0-9]{64})\s+' -or (Get-ArtifactSha256 -Path $destination) -ne $Matches[1]) {
                $pullFailures += ('边界证据原字节传输哈希不符：' + $name)
            } else { $boundaryContext.files += [ordered]@{ file = $name; sha256 = (Get-ArtifactSha256 -Path $destination) } }
        }
        Save-BoundaryInstalledIdentity -Phase 'after'
        if ($pullFailures.Count -gt 0) { throw ($pullFailures -join '；') }
        $boundaryContext | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $boundaryDirectory 'boundary-context.json') -Encoding utf8
        & 'C:\Python314\python.exe' -B (Join-Path $PSScriptRoot 'validate-field-zip.py') (Join-Path $boundaryDirectory 'field-frame-boundary-export.zip') --require-music --sha $boundaryContext.gitCommit --output (Join-Path $boundaryDirectory 'field-zip-validation.json') > (Join-Path $boundaryDirectory 'field-zip-validation.log') 2>&1
        $zipValidationExit = $LASTEXITCODE
        & 'C:\Python314\python.exe' -B (Join-Path $PSScriptRoot 'check-fieldtest.py') --validate-field-frame-boundary $boundaryDirectory --expected-commit $boundaryContext.gitCommit --output (Join-Path $boundaryDirectory 'boundary-validation.json') > (Join-Path $boundaryDirectory 'boundary-validation.log') 2>&1
        if ($LASTEXITCODE -ne 0 -or $zipValidationExit -ne 0) { throw '真实停止边界证据未通过本次身份/240帧/三边界及严格ZIP门禁。' }
        $boundaryEvidence.status = 'PASSED'
        $boundaryEvidence.validation = Join-Path $boundaryDirectory 'boundary-validation.json'
    } catch {
        $boundaryFailure = $_.Exception.Message
        $boundaryEvidence.status = 'FAILED'; $boundaryEvidence.failure = $boundaryFailure
    }
    # The terminal test publishes its newly created directory via the runner, never via mtime guessing.
    $completionFailure = $null
    $completionDirectory = Join-Path $outputRoot ('field-completion-boundary\' + $traceRunId)
    New-Item -ItemType Directory -Path $completionDirectory | Out-Null
    $completionEvidence = [ordered]@{ runId = $traceRunId; directory = $completionDirectory; status = 'NOT_VERIFIED' }
    $completionContext = [ordered]@{ runId = $traceRunId; device = $deviceSerial; gitCommit = $boundaryContext.gitCommit; commands = @(); files = @() }
    try {
        $resultPaths = [regex]::Matches($instrumentation.output, '(?m)^INSTRUMENTATION_RESULT: FIELD_COMPLETION_BOUNDARY_RESULT=([^\r\n]+)\s*$')
        if ($resultPaths.Count -ne 1) { throw '终态用例没有返回唯一的本次结果路径。' }
        $reportedPath = $resultPaths[0].Groups[1].Value.Trim()
        if ($reportedPath -notmatch '^/(storage/emulated/0|sdcard)/Android/data/com\.xiuyin\.app/files/field-completion-boundary/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}/result\.json$') { throw '终态结果路径越界或不符合真实独立目录契约。' }
        $completionContext.reportedResultPath = $reportedPath
        $completionContext.boundaryContextSha256 = Get-ArtifactSha256 -Path (Join-Path $boundaryDirectory 'boundary-context.json')
        $remoteDirectory = $reportedPath.Substring(0, $reportedPath.Length - '/result.json'.Length)
        $pullFailures = @()
        foreach ($name in @('result.json', 'reference-source.json', 'original-reference.json', 'actual-practice-report.xpr', 'field-completion-export.zip')) {
            $destination = Join-Path $completionDirectory $name
            if (Test-Path -LiteralPath $destination) { throw ('拒绝覆盖终态证据：' + $name) }
            $remote = $remoteDirectory + '/' + $name
            $pulled = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'pull', $remote, $destination) -Name ($name + '-pull.log') -Directory $completionDirectory -Context $completionContext
            $remoteSha = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'shell', 'sha256sum', $remote) -Name ($name + '-device-sha.log') -Directory $completionDirectory -Context $completionContext
            if ($pulled.exitCode -ne 0 -or -not (Test-Path -LiteralPath $destination -PathType Leaf)) { $pullFailures += ('缺少终态实际文件：' + $name) }
            elseif ($remoteSha.exitCode -ne 0 -or $remoteSha.output -notmatch '^([a-f0-9]{64})\s+' -or (Get-ArtifactSha256 -Path $destination) -ne $Matches[1]) { $pullFailures += ('终态文件原字节哈希不符：' + $name) }
            else { $completionContext.files += [ordered]@{ file = $name; sha256 = (Get-ArtifactSha256 -Path $destination) } }
        }
        if ($pullFailures.Count -gt 0) { throw ($pullFailures -join '；') }
        $completionContext | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $completionDirectory 'completion-context.json') -Encoding utf8
        & 'C:\Python314\python.exe' -B (Join-Path $PSScriptRoot 'validate-field-zip.py') (Join-Path $completionDirectory 'field-completion-export.zip') --require-music --sha $boundaryContext.gitCommit --expected-frames 80 --output (Join-Path $completionDirectory 'field-zip-validation.json') > (Join-Path $completionDirectory 'field-zip-validation.log') 2>&1
        $zipValidationExit = $LASTEXITCODE
        & 'C:\Python314\python.exe' -B (Join-Path $PSScriptRoot 'check-fieldtest.py') --validate-field-completion-boundary $completionDirectory --boundary-evidence $boundaryDirectory --expected-commit $boundaryContext.gitCommit --output (Join-Path $completionDirectory 'completion-validation.json') > (Join-Path $completionDirectory 'completion-validation.log') 2>&1
        if ($LASTEXITCODE -ne 0 -or $zipValidationExit -ne 0) { throw '终态80帧、真实一次完成/尾帧未判分或本次身份门禁失败。' }
        $completionEvidence.status = 'PASSED'; $completionEvidence.validation = Join-Path $completionDirectory 'completion-validation.json'
    } catch {
        $completionFailure = $_.Exception.Message
        $completionEvidence.status = 'FAILED'; $completionEvidence.failure = $completionFailure
    } finally {
        if (-not (Test-Path -LiteralPath (Join-Path $completionDirectory 'completion-context.json'))) {
            $completionContext | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $completionDirectory 'completion-context.json') -Encoding utf8
        }
    }
    # Pull the current run's complete manifest even when a test failed; do not lose failed .pending evidence.
    $omrFailure = $null
    try {
        $omrContext.instrumentationExitCode = $instrumentation.exitCode
        if ($beforeClock.exitCode -ne 0 -or $afterClock.exitCode -ne 0 -or $beforeClock.output.Trim() -notmatch '^\d{10,}$' -or $afterClock.output.Trim() -notmatch '^\d{10,}$') { throw 'OMR本次设备时间窗口缺失。' }
        $omrContext.deviceStartedAtMillis = [long]$beforeClock.output.Trim() * 1000
        $omrContext.deviceFinishedAtMillis = [long]$afterClock.output.Trim() * 1000 + 999
        foreach ($pair in @(@('instrumentation.log', 'instrumentation.log'), @('TEST-instrumentation.xml', 'instrumentation-result.xml'))) {
            $destination = Join-Path $omrDirectory $pair[1]
            if (Test-Path -LiteralPath $destination) { throw '拒绝覆盖独立OMR仪器证据。' }
            Copy-Item -LiteralPath (Join-Path $outputRoot $pair[0]) -Destination $destination
            $omrContext.files += [ordered]@{ file = $pair[1]; sha256 = (Get-ArtifactSha256 -Path $destination) }
        }
        $omrRemote = '/sdcard/Android/data/com.xiuyin.app/files/omr-independent-regression/' + $omrRunId
        $manifestPath = Join-Path $omrDirectory 'evidence-manifest.json'
        $pullManifest = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'pull', ($omrRemote + '/evidence-manifest.json'), $manifestPath) -Name 'manifest-pull.log' -Directory $omrDirectory -Context $omrContext
        $manifestDevice = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'shell', 'sha256sum', ($omrRemote + '/evidence-manifest.json')) -Name 'manifest-device-sha.log' -Directory $omrDirectory -Context $omrContext
        if ($pullManifest.exitCode -ne 0 -or -not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or $manifestDevice.exitCode -ne 0 -or
            $manifestDevice.output -notmatch '^([a-f0-9]{64})\s+' -or (Get-ArtifactSha256 -Path $manifestPath) -cne $Matches[1]) { throw '本次独立OMR清单拉取或原字节SHA验证失败。' }
        $manifestSha = Get-ArtifactSha256 -Path $manifestPath
        $omrManifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json
        $entries = @(Get-OmrIndependentManifestEntries -Manifest $omrManifest)
        $pullFailures = @(); $fileIndex = 0
        foreach ($entry in $entries) {
            $stem = 'file-' + $fileIndex.ToString('D4')
            $temporary = Join-Path $omrDirectory ($stem + '.pull')
            $destination = [IO.Path]::GetFullPath((Join-Path $omrDirectory ($entry.path.Replace('/', '\'))))
            if (-not $destination.StartsWith(([IO.Path]::GetFullPath($omrDirectory) + [IO.Path]::DirectorySeparatorChar), [StringComparison]::OrdinalIgnoreCase) -or
                (Test-Path -LiteralPath $destination) -or (Test-Path -LiteralPath $temporary)) { throw '独立OMR文件越界或覆盖已有证据。' }
            $remote = $omrRemote + '/' + $entry.path
            $pulled = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'pull', $remote, $temporary) -Name ($stem + '-pull.log') -Directory $omrDirectory -Context $omrContext
            $deviceHash = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'shell', 'sha256sum', $remote) -Name ($stem + '-device-sha.log') -Directory $omrDirectory -Context $omrContext
            if ($pulled.exitCode -ne 0 -or -not (Test-Path -LiteralPath $temporary -PathType Leaf)) { $pullFailures += ('文件未取回：' + $entry.path) }
            elseif ($deviceHash.exitCode -ne 0 -or $deviceHash.output -notmatch '^([a-f0-9]{64})\s+' -or $Matches[1] -cne $entry.sha256 -or
                (Get-Item -LiteralPath $temporary).Length -ne $entry.bytes -or (Get-ArtifactSha256 -Path $temporary) -cne $entry.sha256) { $pullFailures += ('清单/设备/主机原字节不符：' + $entry.path) }
            else {
                New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
                Move-Item -LiteralPath $temporary -Destination $destination
                $omrContext.files += [ordered]@{ file = $entry.path; bytes = $entry.bytes; sha256 = $entry.sha256 }
            }
            $fileIndex++
        }
        $manifestAfter = Invoke-BoundaryCapture -Arguments @('-s', $deviceSerial, 'shell', 'sha256sum', ($omrRemote + '/evidence-manifest.json')) -Name 'manifest-after-sha.log' -Directory $omrDirectory -Context $omrContext
        if ($manifestAfter.exitCode -ne 0 -or $manifestAfter.output -notmatch '^([a-f0-9]{64})\s+' -or $Matches[1] -cne $manifestSha) { $pullFailures += '传输期间原始清单发生变化。' }
        if ($pullFailures.Count -gt 0) { throw ($pullFailures -join '；') }
        $omrContext.files += [ordered]@{ file = 'evidence-manifest.json'; sha256 = $manifestSha; bytes = (Get-Item -LiteralPath $manifestPath).Length }
        $omrReport = Get-Content -LiteralPath (Join-Path $omrDirectory 'report.json') -Raw -Encoding utf8 | ConvertFrom-Json
        Assert-OmrIndependentReport -Report $omrReport -Context $omrContext -Manifest $omrManifest
        $omrCase = @($result.cases | Where-Object { $_.className -ceq 'com.xiuyin.app.omr.OmrIndependentDatasetTest' -and $_.testName -ceq 'explicitPhaseUsesRealImportOrCrossProcessArchive' })
        if ($omrCase.Count -ne 1 -or $omrCase[0].statusCode -ne 0) { throw '本次主仪器日志未确认唯一独立OMR测试真实通过。' }
        $omrEvidence.status = 'PASSED'; $omrEvidence.report = Join-Path $omrDirectory 'report.json'; $omrEvidence.manifestSha256 = $manifestSha
    } catch {
        $omrFailure = $_.Exception.Message
        $omrEvidence.status = 'FAILED'; $omrEvidence.failure = $omrFailure
    } finally {
        $omrContext | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $omrDirectory 'omr-context.json') -Encoding utf8
    }
    # Preserve this exact user's full current-run archive, including failed .pending output.
    $userPhotoFailure = $null
    try {
        $userPhotoContext.instrumentationExitCode = $instrumentation.exitCode
        $userPhotoContext.instrumentationLogSha256 = Get-ArtifactSha256 -Path (Join-Path $outputRoot 'instrumentation.log')
        $photoPaths = [regex]::Matches($instrumentation.output, '(?m)^INSTRUMENTATION_RESULT: OMR_USER_PHOTO_RESULT=([^\r\n]+)\s*$')
        if ($photoPaths.Count -ne 1) { throw '用户原图用例没有返回唯一的本次证据路径。' }
        $photoReportedPath = $photoPaths[0].Groups[1].Value.Trim()
        if ($photoReportedPath -notmatch '^/(storage/emulated/0|sdcard)/Android/data/com\.xiuyin\.app/files/omr-user-photo-tests/([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})/report\.json$') { throw '用户原图结果路径越界或不符合独立目录契约。' }
        $userPhotoContext.testRunId = $Matches[2]
        $userPhotoContext.reportedResultPath = $photoReportedPath
        $photoRemote = $photoReportedPath.Substring(0, $photoReportedPath.Length - '/report.json'.Length)
        $photoManifestPath = Join-Path $userPhotoDirectory 'evidence-manifest.json'
        $photoPull = Invoke-UserPhotoCapture -Arguments @('-s', $deviceSerial, 'pull', ($photoRemote + '/evidence-manifest.json'), $photoManifestPath) -Name 'manifest-pull.log'
        $photoDeviceHash = Invoke-UserPhotoCapture -Arguments @('-s', $deviceSerial, 'shell', 'sha256sum', ($photoRemote + '/evidence-manifest.json')) -Name 'manifest-device-sha.log'
        if ($photoPull.exitCode -ne 0 -or -not (Test-Path -LiteralPath $photoManifestPath -PathType Leaf) -or $photoDeviceHash.exitCode -ne 0 -or
            $photoDeviceHash.output -notmatch '^([a-f0-9]{64})\s+' -or (Get-ArtifactSha256 -Path $photoManifestPath) -cne $Matches[1]) { throw '用户原图证据清单拉取或原字节 SHA 验证失败。' }
        $photoManifestSha = Get-ArtifactSha256 -Path $photoManifestPath
        $photoManifest = Get-Content -LiteralPath $photoManifestPath -Raw -Encoding utf8 | ConvertFrom-Json
        $photoEntries = @(Get-OmrIndependentManifestEntries -Manifest $photoManifest)
        $photoPullFailures = @(); $photoIndex = 0
        foreach ($entry in $photoEntries) {
            $stem = 'file-' + $photoIndex.ToString('D4')
            $temporary = Join-Path $userPhotoDirectory ($stem + '.pull')
            $destination = [IO.Path]::GetFullPath((Join-Path $userPhotoDirectory ($entry.path.Replace('/', '\'))))
            if (-not $destination.StartsWith(([IO.Path]::GetFullPath($userPhotoDirectory) + [IO.Path]::DirectorySeparatorChar), [StringComparison]::OrdinalIgnoreCase) -or
                (Test-Path -LiteralPath $destination) -or (Test-Path -LiteralPath $temporary)) { throw '用户原图证据文件越界或覆盖已有文件。' }
            $remote = $photoRemote + '/' + $entry.path
            $pulled = Invoke-UserPhotoCapture -Arguments @('-s', $deviceSerial, 'pull', $remote, $temporary) -Name ($stem + '-pull.log')
            $deviceHash = Invoke-UserPhotoCapture -Arguments @('-s', $deviceSerial, 'shell', 'sha256sum', $remote) -Name ($stem + '-device-sha.log')
            if ($pulled.exitCode -ne 0 -or -not (Test-Path -LiteralPath $temporary -PathType Leaf)) { $photoPullFailures += ('用户原图证据未取回：' + $entry.path) }
            elseif ($deviceHash.exitCode -ne 0 -or $deviceHash.output -notmatch '^([a-f0-9]{64})\s+' -or $Matches[1] -cne $entry.sha256 -or
                (Get-Item -LiteralPath $temporary).Length -ne $entry.bytes -or (Get-ArtifactSha256 -Path $temporary) -cne $entry.sha256) { $photoPullFailures += ('用户原图证据清单/设备/主机原字节不符：' + $entry.path) }
            else {
                New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
                Move-Item -LiteralPath $temporary -Destination $destination
                $userPhotoContext.files += [ordered]@{ file = $entry.path; bytes = $entry.bytes; sha256 = $entry.sha256 }
            }
            $photoIndex++
        }
        $photoManifestAfter = Invoke-UserPhotoCapture -Arguments @('-s', $deviceSerial, 'shell', 'sha256sum', ($photoRemote + '/evidence-manifest.json')) -Name 'manifest-after-sha.log'
        if ($photoManifestAfter.exitCode -ne 0 -or $photoManifestAfter.output -notmatch '^([a-f0-9]{64})\s+' -or $Matches[1] -cne $photoManifestSha) { $photoPullFailures += '用户原图证据清单在传输期间变化。' }
        if ($photoPullFailures.Count -gt 0) { throw ($photoPullFailures -join '；') }
        $userPhotoContext.files += [ordered]@{ file = 'evidence-manifest.json'; bytes = (Get-Item -LiteralPath $photoManifestPath).Length; sha256 = $photoManifestSha }
        $photoReport = Get-Content -LiteralPath (Join-Path $userPhotoDirectory 'report.json') -Raw -Encoding utf8 | ConvertFrom-Json
        if ($photoReport.runId -cne $userPhotoContext.testRunId -or $photoReport.expectedInputSha256 -cne $userPhotoExpectedSha -or
            $photoReport.appApkSha256 -cne $traceContext.appSha256 -or $photoReport.testApkSha256 -cne $traceContext.testApkSha256 -or
            (Get-ArtifactSha256 -Path (Join-Path $userPhotoDirectory 'original-input.document')) -cne $userPhotoExpectedSha) { throw '用户原图报告运行身份、APK 或原始输入不匹配。' }
        if ($photoReport.status -cne 'IMPORT_AND_REPOSITORY_READBACK_PASSED_NEEDS_REVIEW' -or $photoReport.scoreStatus -cne 'NEEDS_REVIEW' -or
            $photoReport.repositoryReload -cne 'NEW_SERVICE_SAME_PROCESS_PASSED' -or $photoReport.rawOutputPresent -ne $true -or
            $photoReport.musicalAccuracy -cne 'UNVERIFIED' -or $photoReport.physicalPhoneAcceptance -cne 'UNVERIFIED') { throw '用户原图真实导入/原始输出/新服务读回未通过；已保留本轮失败证据，不宣称音乐准确率通过。' }
        if ($beforeClock.exitCode -ne 0 -or $afterClock.exitCode -ne 0 -or $beforeClock.output.Trim() -notmatch '^\d{10,}$' -or $afterClock.output.Trim() -notmatch '^\d{10,}$') { throw '用户原图本轮设备时间窗口缺失。' }
        $photoStarted = [DateTimeOffset]::Parse($photoReport.startedAt).ToUnixTimeMilliseconds()
        $photoFinished = [DateTimeOffset]::Parse($photoReport.finishedAt).ToUnixTimeMilliseconds()
        if ($photoStarted -lt ([long]$beforeClock.output.Trim() * 1000) -or $photoFinished -gt ([long]$afterClock.output.Trim() * 1000 + 999) -or $photoFinished -lt $photoStarted) { throw '用户原图证据不属于本次主仪器测试时间窗口。' }
        $photoCase = @($result.cases | Where-Object { $_.className -ceq 'com.xiuyin.app.omr.OmrUserPhotoRealTest' -and $_.testName -ceq 'exactUserPhotoUsesProductionImportAndPreservesAllAvailableEvidence' })
        if ($photoCase.Count -ne 1 -or $photoCase[0].statusCode -ne 0) { throw '本次完整仪器日志未确认唯一用户原图用例真实通过。' }
        $userPhotoEvidence.status = 'PASSED'; $userPhotoEvidence.report = Join-Path $userPhotoDirectory 'report.json'; $userPhotoEvidence.manifestSha256 = $photoManifestSha
    } catch {
        $userPhotoFailure = $_.Exception.Message
        $userPhotoEvidence.status = 'FAILED'; $userPhotoEvidence.failure = $userPhotoFailure
    } finally {
        $userPhotoContext | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $userPhotoDirectory 'user-photo-context.json') -Encoding utf8
    }
    # Preserve complete diagnostic bytes even if another main-suite assertion failed.
    $traceRemote = '/sdcard/Android/data/com.xiuyin.app/files/independent-pcm-traces/' + $traceRunId
    foreach ($name in @('metadata.json', 'frames.jsonl', 'engine-status.jsonl', 'practice-replay-result.json')) {
        $tracePull = Invoke-AdbCapture -AdbArguments @('-s', $deviceSerial, 'pull', ($traceRemote + '/' + $name), (Join-Path $traceDirectory $name)) -LogPath (Join-Path $traceDirectory ($name + '-pull.log'))
        if ($tracePull.exitCode -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $traceDirectory $name) -PathType Leaf)) { throw ('完整默认诊断证据缺失：' + $name) }
    }
    & 'C:\Python314\python.exe' -B (Join-Path $PSScriptRoot 'check-fieldtest.py') --validate-default-trace $traceDirectory --output (Join-Path $traceDirectory 'trace-validation.json')
    if ($LASTEXITCODE -ne 0) { throw '默认真实 Autopiano 完整 raw/跟谱诊断未通过身份或完整性门禁' }
    $traceEvidence.status = 'PASSED'
    $traceEvidence.validation = Join-Path $traceDirectory 'trace-validation.json'
    if ($boundaryFailure) { throw $boundaryFailure }
    if ($completionFailure) { throw $completionFailure }
    if ($omrFailure) { throw $omrFailure }
    if ($userPhotoFailure) { throw $userPhotoFailure }
    if ($result.status -ne 'passed') { throw ($result.problems -join '；') }
    $mainReplayPath = Join-Path $outputRoot 'autopiano-practice-replay-result.json'
    # Pull bytes directly: native UTF-8 JSON must never pass through a locale-decoded PowerShell string.
    $mainReplayRemote = '/sdcard/Android/data/com.xiuyin.app/files/practice-replay-result.json'
    $pull = Invoke-AdbCapture -AdbArguments @('-s', $deviceSerial, 'pull', $mainReplayRemote, $mainReplayPath) -LogPath (Join-Path $outputRoot 'autopiano-report-pull.log')
    if ($pull.exitCode -ne 0 -or -not (Test-Path -LiteralPath $mainReplayPath -PathType Leaf)) { throw '实际App主回放证据原字节复制失败' }
    $remoteHash = Invoke-AdbCapture -AdbArguments @('-s', $deviceSerial, 'shell', 'sha256sum', $mainReplayRemote) -LogPath (Join-Path $outputRoot 'autopiano-report-device-sha.log')
    $hashMatch = [regex]::Match($remoteHash.output, '^[a-f0-9]{64}')
    $hashStream = [IO.File]::OpenRead($mainReplayPath)
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { $localHash = [BitConverter]::ToString($hasher.ComputeHash($hashStream)).Replace('-', '').ToLowerInvariant() }
    finally { $hasher.Dispose(); $hashStream.Dispose() }
    if ($remoteHash.exitCode -ne 0 -or -not $hashMatch.Success -or $localHash -ne $hashMatch.Value) { throw '实际App主回放证据传输前后哈希不同' }
    & 'C:\Python314\python.exe' (Join-Path $PSScriptRoot 'evaluation/evaluate-practice-audio.py') --replay $mainReplayPath --timeline (Join-Path $projectRoot 'reports/autopiano-input-worklet/expected-timeline.json') --output (Join-Path $outputRoot 'autopiano-acoustic-evaluation.json')
    if ($LASTEXITCODE -ne 0) { throw 'Autopiano实际App声学门禁未通过' }
    $corpusPath = Join-Path $PSScriptRoot 'evaluation/website-corpus.json'
    $corpus = Get-Content -LiteralPath $corpusPath -Raw -Encoding utf8 | ConvertFrom-Json
    if (@($corpus.cases).Count -lt 2) { throw '两组既有网站回归均为必需，清单不完整' }
    foreach ($fixture in $corpus.cases) {
        if ($fixture.id -notmatch '^[a-z0-9-]+$') { throw 'Invalid website fixture id' }
        $replayStart = Get-Date
        $replayText = & 'C:\Python314\python.exe' (Join-Path $PSScriptRoot 'replay-practice.py') --serial $deviceSerial --wav (Join-Path $projectRoot $fixture.wav) --timeline (Join-Path $projectRoot $fixture.timeline) --expected-wav-sha $fixture.wavSha256 --expected-timeline-sha $fixture.timelineSha256 | Out-String
        $replayCode = $LASTEXITCODE
        $replayText | Set-Content -LiteralPath (Join-Path $outputRoot ($fixture.id + '-replay.json')) -Encoding utf8
        $replay = $replayText | ConvertFrom-Json
        $rawReplay = Get-Content -LiteralPath (Join-Path $replay.report 'instrumentation.log') -Raw -Encoding utf8
        $replayResult = ConvertFrom-InstrumentationOutput -Output $rawReplay -ExitCode $replayCode
        Write-InstrumentationXml -Result $replayResult -Path (Join-Path $outputRoot ('TEST-' + $fixture.id + '.xml')) -Raw $rawReplay -StartedAt $replayStart -Duration (((Get-Date) - $replayStart).TotalSeconds)

        & 'C:\Python314\python.exe' (Join-Path $PSScriptRoot 'evaluation/evaluate-practice-audio.py') --replay (Join-Path $replay.report 'practice-replay-result.json') --timeline (Join-Path $projectRoot $fixture.timeline) --output (Join-Path $outputRoot ($fixture.id + '-acoustic-evaluation.json'))
        $acousticCode = $LASTEXITCODE
        if ($replay.status -ne 'PASSED' -or $replayResult.status -ne 'passed') { throw ('实际网站陪练回放失败：' + $fixture.id + '；' + $replay.report) }
        if ($acousticCode -ne 0) { throw ('实际网站声学门禁未通过：' + $fixture.id) }

    }
    $exitCode = 0
    Write-Host ("原生仪器测试通过：$($result.tests) 个主用例，及 $(@($corpus.cases).Count) 组独立网站回放；失败 0；跳过 0。")
} catch {
    $failure = $_.Exception.Message
    [Console]::Error.WriteLine('原生仪器测试失败：' + $failure)
} finally {
    $env:ANDROID_USER_HOME = $previousUserHome
    if ($boundaryContext -and $boundaryDirectory -and -not (Test-Path -LiteralPath (Join-Path $boundaryDirectory 'boundary-context.json'))) {
        $boundaryContext | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $boundaryDirectory 'boundary-context.json') -Encoding utf8
    }
    if ($omrContext -and $omrDirectory -and -not (Test-Path -LiteralPath (Join-Path $omrDirectory 'omr-context.json'))) {
        $omrContext | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $omrDirectory 'omr-context.json') -Encoding utf8
    }
    if ($userPhotoContext -and $userPhotoDirectory -and -not (Test-Path -LiteralPath (Join-Path $userPhotoDirectory 'user-photo-context.json'))) {
        $userPhotoContext | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $userPhotoDirectory 'user-photo-context.json') -Encoding utf8
    }
    if (Test-Path -LiteralPath $outputRoot -PathType Container) {
        [ordered]@{
            executor = 'adb-am-instrument'; runner = 'com.xiuyin.app.test/androidx.test.runner.AndroidJUnitRunner'
            startedAt = $started.ToUniversalTime().ToString('o'); finishedAt = (Get-Date).ToUniversalTime().ToString('o')
            status = $(if ($exitCode -eq 0) { 'passed' } else { 'failed' }); device = $deviceSerial
            failure = $failure; result = $result; fullTrace = $traceEvidence; fieldFrameBoundary = $boundaryEvidence; fieldCompletionBoundary = $completionEvidence; omrIndependent = $omrEvidence; omrUserPhoto = $userPhotoEvidence
        } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $outputRoot 'executor.json') -Encoding utf8
    }
}
exit $exitCode
