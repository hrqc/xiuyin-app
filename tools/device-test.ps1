param()
$ErrorActionPreference = 'Stop'

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

function Invoke-AdbCapture([string[]]$AdbArguments, [string]$LogPath) {
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
    $devices = @($listing.output -split '\r?\n' | Where-Object { $_ -match '^\S+\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($devices.Count -ne 1 -or $devices[0] -notmatch '^emulator-\d+$') {
        throw '只允许恰好一台在线模拟器；不会安装到物理手机。'
    }
    $deviceSerial = $devices[0]
    if ($env:ANDROID_SERIAL -and $env:ANDROID_SERIAL -ne $deviceSerial) { throw '当前模拟器与调用方指定的 ANDROID_SERIAL 不一致。' }
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
    $testStarted = Get-Date
    Write-Host ('运行相同 AndroidJUnitRunner 测试集：' + $deviceSerial)
    $instrumentation = Invoke-AdbCapture -AdbArguments @('-s', $deviceSerial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'omrInputDirectory', 'omr-evaluation-input', '-e', 'omrProblemInput', 'problem-score.jpg', '-e', 'omrReanalysisArchive', 'omr-legacy-problem', '-e', 'manualReviewScore', 'czerny-manual-review.musicxml', '-e', 'phoneVideoWav', '/data/user/0/com.xiuyin.app/files/phone-video-20260923.wav', '-e', 'phoneVideoExpectedSha256', '777ff0c774277532ae61754afd4aea345d71e3be03c1e264fbf817ae50d9e858', '-e', 'roomPianoWav', '/data/user/0/com.xiuyin.app/files/practice-evaluation-input/room.wav', '-e', 'octaveBaselineWav', '/data/user/0/com.xiuyin.app/files/practice-evaluation-input/octave-regression.wav', '-e', 'octaveBaselineExpectedSha256', 'a047684a7e86a8fd4f4c3c8bc07d5be3903407bc1225f2eeb36eed42a8e21c20', '-e', 'practiceWav', '/data/user/0/com.xiuyin.app/files/practice-evaluation-input/website.wav', '-e', 'practiceTimeline', '/data/user/0/com.xiuyin.app/files/practice-evaluation-input/expected-timeline.json', '-e', 'traceRunId', $traceRunId, '-e', 'traceWav', '/data/user/0/com.xiuyin.app/files/practice-evaluation-input/website.wav', '-e', 'traceTimeline', '/data/user/0/com.xiuyin.app/files/practice-evaluation-input/expected-timeline.json', '-e', 'traceWavSha256', $traceContext.wavSha256, '-e', 'traceTimelineSha256', $traceContext.timelineSha256, '-e', 'traceAppSha256', $traceContext.appSha256, '-e', 'traceModelSha256', $traceContext.modelSha256, 'com.xiuyin.app.test/androidx.test.runner.AndroidJUnitRunner') -LogPath (Join-Path $outputRoot 'instrumentation.log')
    $duration = ((Get-Date) - $testStarted).TotalSeconds
    $result = ConvertFrom-InstrumentationOutput -Output $instrumentation.output -ExitCode $instrumentation.exitCode
    Write-InstrumentationXml -Result $result -Path (Join-Path $outputRoot 'TEST-instrumentation.xml') -Raw $instrumentation.output -StartedAt $testStarted -Duration $duration
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
    if (Test-Path -LiteralPath $outputRoot -PathType Container) {
        [ordered]@{
            executor = 'adb-am-instrument'; runner = 'com.xiuyin.app.test/androidx.test.runner.AndroidJUnitRunner'
            startedAt = $started.ToUniversalTime().ToString('o'); finishedAt = (Get-Date).ToUniversalTime().ToString('o')
            status = $(if ($exitCode -eq 0) { 'passed' } else { 'failed' }); device = $deviceSerial
            failure = $failure; result = $result; fullTrace = $traceEvidence
        } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $outputRoot 'executor.json') -Encoding utf8
    }
}
exit $exitCode