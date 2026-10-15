param([string]$PythonExecutable = 'C:\Python314\python.exe', [string]$Serial = $env:ANDROID_SERIAL)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'emulator-selection.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ')
$reportDir = Join-Path $projectRoot "reports\$runId"
New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
$sourceSha = (& git -c "safe.directory=$projectRoot" rev-parse HEAD 2>$null | Out-String).Trim()
$dirty = [bool](& git -c "safe.directory=$projectRoot" status --porcelain)
$versionMatch = [regex]::Match((Get-Content -LiteralPath (Join-Path $projectRoot 'app/build.gradle.kts') -Raw), 'versionName = "([^"]+)"')
if (-not $versionMatch.Success) { throw '无法读取本次构建版本' }
$versionName = $versionMatch.Groups[1].Value
$started = Get-Date
$steps = @()
$device = $null
$failure = $null
$oldAndroidHome = $env:ANDROID_USER_HOME
$oldSerial = $env:ANDROID_SERIAL
$buildRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'app\build'))
$groups = [ordered]@{
    unit = [ordered]@{
        name = 'unit'; task = ':app:testDebugUnitTest'; source = 'app\build\test-results\testDebugUnitTest'
        status = 'not_run'; startedAt = $null; priorResults = $null
        tests = 0; passed = 0; failed = 0; skipped = 0; suites = @(); rejected = @(); runnerFailures = @(); failure = $null
    }
    device = [ordered]@{
        name = 'device'; task = 'adb:instrumentation'; source = 'app\build\outputs\androidTest-results\connected'
        status = 'not_run'; startedAt = $null; priorResults = $null
        tests = 0; passed = 0; failed = 0; skipped = 0; suites = @(); rejected = @(); runnerFailures = @(); failure = $null
    }
}

function Assert-ContainedPath([string]$Path, [string]$Parent) {
    $full = [IO.Path]::GetFullPath($Path)
    $prefix = [IO.Path]::GetFullPath($Parent).TrimEnd('\') + '\'
    if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "路径不在指定项目目录内：$full"
    }
    # A lexical prefix alone is insufficient when a parent is a junction.
    $cursor = $full
    while ($cursor -and $cursor.StartsWith($projectRoot, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "拒绝移动或读取通过链接重定向的检测路径：$cursor"
            }
        }
        if ($cursor.Equals($projectRoot, [StringComparison]::OrdinalIgnoreCase)) { break }
        $cursor = Split-Path -Parent $cursor
    }
}

function Get-TestCounts([object[]]$Records) {
    $count = [ordered]@{ tests = 0; passed = 0; failed = 0; skipped = 0 }
    foreach ($record in @($Records)) {
        if ($null -eq $record) { continue }
        $count.tests += [int]$record.tests
        $count.failed += [int]$record.failures + [int]$record.errors
        $count.skipped += [int]$record.skipped
    }
    $count.passed = $count.tests - $count.failed - $count.skipped
    return [pscustomobject]$count
}

function Prepare-TestResultDirectory($Group) {
    $source = [IO.Path]::GetFullPath((Join-Path $projectRoot $Group.source))
    Assert-ContainedPath $source $buildRoot
    if (Test-Path -LiteralPath $source) {
        $resolved = (Resolve-Path -LiteralPath $source).ProviderPath
        Assert-ContainedPath $resolved $buildRoot
        if (-not $resolved.Equals($source, [StringComparison]::OrdinalIgnoreCase)) {
            throw "检测输出路径解析结果不一致：$source"
        }
        if (-not (Test-Path -LiteralPath $source -PathType Container)) {
            throw "检测输出位置不是目录：$source"
        }
        $priorRoot = Join-Path $reportDir 'prior-results'
        $destination = Join-Path $priorRoot $Group.name
        Assert-ContainedPath $destination $reportDir
        if (Test-Path -LiteralPath $destination) { throw "上轮报告存档已存在：$destination" }
        New-Item -ItemType Directory -Path $priorRoot -Force | Out-Null
        # Only the two fixed app/build test-result directories are moved.
        Move-Item -LiteralPath $source -Destination $destination
        $Group.priorResults = $destination
    }
    if (Test-Path -LiteralPath $source) { throw "本轮检测输出目录未成功隔离：$source" }
}

function Read-CurrentTestResults($Group, [datetime]$TaskStart) {
    $source = [IO.Path]::GetFullPath((Join-Path $projectRoot $Group.source))
    $records = @()
    $rejected = @()
    $runnerFailures = @()
    Assert-ContainedPath $source $buildRoot
    if (Test-Path -LiteralPath $source -PathType Container) {
        $files = @(Get-ChildItem -LiteralPath $source -Filter 'TEST-*.xml' -File -Recurse)
        foreach ($file in $files) {
            try {
                Assert-ContainedPath $file.FullName $source
                if ($file.LastWriteTimeUtc -lt $TaskStart.ToUniversalTime().AddSeconds(-1)) {
                    throw 'XML 时间早于本轮任务开始时间，不计入本轮结果。'
                }
                [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw -Encoding utf8
                if ($xml.DocumentElement.LocalName -ne 'testsuite') { throw 'XML 缺少 testsuite 根节点。' }
                foreach ($property in @($xml.testsuite.properties.property)) {
                    if ($null -ne $property -and $property.name -eq 'xiuyin.runner.status' -and $property.value -ne 'passed') {
                        $runnerFailures += [pscustomobject]@{ file = $file.FullName; reason = '原生仪器执行器未完整成功结束；保留真实用例计数，但本组不能通过。' }
                    }
                }
                $values = @{}
                foreach ($attribute in @('tests', 'failures', 'errors', 'skipped')) {
                    $number = 0
                    if (-not [int]::TryParse($xml.DocumentElement.GetAttribute($attribute), [ref]$number) -or $number -lt 0) {
                        throw "XML 的 $attribute 计数缺失或无效。"
                    }
                    $values[$attribute] = $number
                }
                if ($values.tests -lt ($values.failures + $values.errors + $values.skipped)) {
                    throw 'XML 中失败和跳过计数超过测试总数。'
                }
                $relative = $file.FullName.Substring($source.Length).TrimStart('\')
                $snapshotRoot = Join-Path $reportDir ('current-results\' + $Group.name)
                $snapshot = Join-Path $snapshotRoot $relative
                Assert-ContainedPath $snapshot $reportDir
                New-Item -ItemType Directory -Path (Split-Path -Parent $snapshot) -Force | Out-Null
                Copy-Item -LiteralPath $file.FullName -Destination $snapshot
                $records += [pscustomobject][ordered]@{
                    group = $Group.name; file = $snapshot; source = $file.FullName
                    modifiedAt = $file.LastWriteTimeUtc.ToString('o'); suite = [string]$xml.testsuite.name
                    tests = $values.tests; failures = $values.failures; errors = $values.errors; skipped = $values.skipped
                }
            } catch {
                $rejected += [pscustomobject]@{ file = $file.FullName; reason = $_.Exception.Message }
            }
        }
    }
    $counts = Get-TestCounts -Records $records
    $Group.suites = @($records)
    $Group.rejected = @($rejected)
    $Group.runnerFailures = @($runnerFailures)
    foreach ($key in @('tests', 'passed', 'failed', 'skipped')) { $Group[$key] = $counts.$key }
    if ($rejected.Count -gt 0 -or $runnerFailures.Count -gt 0 -or $counts.tests -lt 1 -or $counts.failed -gt 0 -or $counts.skipped -gt 0) {
        $Group.status = 'failed'
        $Group.failure = '该组必须有本轮新生成的测试结果，且通过数大于零、失败与跳过均为零。'
    } else {
        $Group.status = 'passed'
    }
}

try {
    $properties = Get-Content -LiteralPath (Join-Path $projectRoot 'local.properties')
    if (-not (Test-Path -LiteralPath $PythonExecutable -PathType Leaf)) { throw "回退执行器所需 Python 不存在：$PythonExecutable" }
    $sdkLine = $properties | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
    if (!$sdkLine) { throw 'local.properties 缺少 sdk.dir，请按 README 配置本机 SDK。' }
    $sdk = ($sdkLine -replace '^sdk.dir=', '').Replace('\:', ':').Replace('\\', '\')
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    if (!(Test-Path -LiteralPath $adb)) { throw "缺少 ADB：$adb" }
    $env:ANDROID_USER_HOME = Join-Path $projectRoot 'tools\local\android'
    New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME -Force | Out-Null
    $listing = & $adb devices | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'ADB devices 失败，未选择任何设备。' }
    $selectedSerial = Select-XiuyinEmulator -Listing $listing -RequestedSerial $Serial
    $env:ANDROID_SERIAL = $selectedSerial
    $device = [ordered]@{
        serial = $selectedSerial
        model = (& $adb -s $selectedSerial shell getprop ro.product.model | Out-String).Trim()
        android = (& $adb -s $selectedSerial shell getprop ro.build.version.release | Out-String).Trim()
        api = (& $adb -s $selectedSerial shell getprop ro.build.version.sdk | Out-String).Trim()
    }
    foreach ($task in @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug', ':app:assembleRelease', 'adb:instrumentation', 'rollback:emulator')) {
        $group = $null
        foreach ($candidate in $groups.Values) { if ($candidate.task -eq $task) { $group = $candidate; break } }
        if ($null -ne $group) { Prepare-TestResultDirectory $group }
        $taskStart = Get-Date
        $name = $task.Trim(':').Replace(':', '-')
        $log = Join-Path $reportDir "$name.log"
        $taskArguments = @($task, '--console=plain', '--no-daemon')
        if ($null -ne $group) {
            $group.status = 'running'
            $group.startedAt = $taskStart.ToUniversalTime().ToString('o')
            if ($group.name -eq 'unit') { $taskArguments += '--rerun-tasks' }
        }
        $global:LASTEXITCODE = -999
        $ErrorActionPreference = 'Continue'
        if ($task -eq 'adb:instrumentation') {
            # Compile the unchanged Android test set, then run the native runner.
            & (Join-Path $projectRoot 'build.cmd') :app:assembleDebug :app:assembleDebugAndroidTest --console=plain --no-daemon 2>&1 | ForEach-Object { $_.ToString() } | Tee-Object -FilePath $log
            $code = $LASTEXITCODE
            if ($code -eq 0) {
                & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'device-test.ps1') -Serial $selectedSerial 2>&1 | ForEach-Object { $_.ToString() } | Tee-Object -FilePath $log -Append
                $code = $LASTEXITCODE
            }
        } elseif ($task -eq 'rollback:emulator') {
            & $PythonExecutable (Join-Path $PSScriptRoot 'rollback-rehearsal.py') --serial $env:ANDROID_SERIAL --report-dir (Join-Path $reportDir 'rollback') 2>&1 | ForEach-Object { $_.ToString() } | Tee-Object -FilePath $log
            $code = $LASTEXITCODE
        } else {
            & (Join-Path $projectRoot 'build.cmd') @taskArguments 2>&1 | ForEach-Object { $_.ToString() } | Tee-Object -FilePath $log
            $code = $LASTEXITCODE
        }
        $ErrorActionPreference = 'Stop'
        $taskFailure = $null
        if ($task -eq 'rollback:emulator' -and $code -eq 0) {
            try {
                $rollbackReportPath = Join-Path $reportDir 'rollback/rollback-report.json'
                if (-not (Test-Path -LiteralPath $rollbackReportPath -PathType Leaf)) { throw '本次回退报告缺失' }
                if ((Get-Item -LiteralPath $rollbackReportPath).LastWriteTimeUtc -lt $taskStart.ToUniversalTime()) { throw '回退报告不是本次执行生成' }
                $rollback = Get-Content -LiteralPath $rollbackReportPath -Raw -Encoding utf8 | ConvertFrom-Json
                $hashStream = [IO.File]::OpenRead((Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'))
                $hasher = [Security.Cryptography.SHA256]::Create()
                try { $candidateHash = [BitConverter]::ToString($hasher.ComputeHash($hashStream)).Replace('-', '') }
                finally { $hasher.Dispose(); $hashStream.Dispose() }
                if ($rollback.status -ne 'passed' -or -not $rollback.baselineDataPreserved -or -not $rollback.restoredDataPreserved -or -not $rollback.baselineScoreSourcesPreserved -or -not $rollback.restoredScoreSourcesPreserved -or -not $rollback.baselineMetadataPreserved -or -not $rollback.restoredMetadataPreserved -or -not $rollback.baselineExtendedFilesPreserved -or -not $rollback.restoredExtendedFilesPreserved -or $rollback.candidateSha256 -ne $candidateHash) { throw '回退结果或候选产物哈希不符合门禁' }
                if (@($rollback.actions | Where-Object { $_.version -eq '0.2.0' -and $_.launch -eq 'passed' }).Count -lt 1 -or @($rollback.actions | Where-Object { $_.version -eq $versionName -and $_.launch -eq 'passed' }).Count -lt 2) { throw '缺少真实旧版/候选启动证据' }
            } catch { $taskFailure = "回退证据验证失败：$($_.Exception.Message)" }
        }
        if ($null -ne $group) {
            try { Read-CurrentTestResults $group $taskStart } catch {
                $group.status = 'failed'
                $group.failure = $_.Exception.Message
            }
            if ($group.status -ne 'passed') { $taskFailure = "$task：$($group.failure)" }
        }
        if ($code -ne 0) {
            $taskFailure = "检查失败：$task，退出码 $code"
            if ($null -ne $group) { $group.status = 'failed'; $group.failure = $taskFailure }
        }
        $steps += [pscustomobject][ordered]@{
            task = $task; executor = $(if ($task -eq 'adb:instrumentation') { 'adb-am-instrument' } elseif ($task -eq 'rollback:emulator') {'local-adb-artifact-rollback'} else { 'gradle' }); exitCode = $code; status = $(if ($taskFailure) {'failed'} else {'passed'})
            durationSeconds = [math]::Round(((Get-Date)-$taskStart).TotalSeconds,2)
            log = $log; failure = $taskFailure
        }
        if ($taskFailure) { throw $taskFailure }
    }
} catch {
    $failure = $_.Exception.Message
    Write-Host $failure -ForegroundColor Red
} finally {
    $env:ANDROID_USER_HOME = $oldAndroidHome
    $env:ANDROID_SERIAL = $oldSerial
    # Only in-memory results collected immediately after this run's tasks count.
    # Preparation failure leaves both groups not_run and all counters at zero.
    $testRecords = @()
    foreach ($group in $groups.Values) { $testRecords += @($group.suites) }
    $counts = Get-TestCounts -Records $testRecords
    $total = $counts.tests
    $failed = $counts.failed
    $skipped = $counts.skipped
    $invalidGroups = @($groups.Values | Where-Object { $_.status -ne 'passed' -or $_.tests -lt 1 -or $_.failed -gt 0 -or $_.skipped -gt 0 })
    $invalidSteps = @($steps | Where-Object { $_.status -ne 'passed' })
    if ($steps.Count -ne 6 -or $invalidSteps.Count -gt 0 -or $invalidGroups.Count -gt 0) {
        if (!$failure) { $failure = '必需检查未全部成功；单元测试和设备测试必须分别存在本轮结果，且零失败、零跳过。' }
    }
    $report = [ordered]@{
        run_id = $runId; version = $versionName; sha = $sourceSha; dirty = $dirty; testSet = 'digital-score-1 + metadata-1 + workbench-1 + audio-2 + OMR-1 + companion-1'
        startedAt = $started.ToUniversalTime().ToString('o'); finishedAt = (Get-Date).ToUniversalTime().ToString('o')
        status = $(if ($failure) {'failed'} else {'passed'}); failure = $failure; device = $device
        total = $total; passed = $counts.passed; failed = $failed; skipped = $skipped
        steps = @($steps); groups = @($groups.Values); suites = @($testRecords)
        priorResultsPolicy = 'prior-results 只保留上轮结果，不参与本轮计数；current-results 保存本轮新结果。'
        notApplicable = @('本版无后端/API')
        unverified = @('物理 Android 手机现场输入','无人工逐音参考的琴房准确率/误停率','端到端手机音频延迟','Android 8-14 与 16 的设备兼容性')
        remoteSync = '本检测命令不执行 Push；远程同步须单独核验对应 SHA'
    }
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $reportDir 'report.json') -Encoding utf8
    @(
        "# 修音 $versionName 检测结果"
        ""
        "Run: $runId"
        "SHA: $sourceSha"
        "未提交修改: $dirty"
        "状态: $($report.status)"
        "通过: $($report.passed) / 失败: $failed / 跳过: $skipped / 总数: $total"
        "单元测试：$($groups.unit.status)，通过 $($groups.unit.passed) / 总数 $($groups.unit.tests)"
        "设备测试：$($groups.device.status)，通过 $($groups.device.passed) / 总数 $($groups.device.tests)"
        "原因: $failure"
        ""
        "设备: $($device | ConvertTo-Json -Compress)"
        "完整明细与每步日志见同目录 report.json；current-results 仅包含本轮结果。"
        "prior-results 仅存档上轮输出，未计入本轮结果。失败时保留已有源码与用户数据。"
        "此报告为软件工程与声明数据集门禁；真机现场和无真值琴房准确率未验证，另见真实材料验收报告。远程同步另行记录，不由本检测命令推断。"
    ) | Set-Content -LiteralPath (Join-Path $reportDir 'SUMMARY.md') -Encoding utf8
    Write-Host "报告目录：$reportDir"
}
if ($failure) { exit 1 }
exit 0
