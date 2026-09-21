[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArguments
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$wrapper = Join-Path $projectRoot 'gradlew.bat'
$wrapperProperties = Join-Path $projectRoot 'gradle\wrapper\gradle-wrapper.properties'
$wrapperJar = Join-Path $projectRoot 'gradle\wrapper\gradle-wrapper.jar'
$localProperties = Join-Path $projectRoot 'local.properties'
$javaTmp = Join-Path $projectRoot 'tools\local\java-tmp'
$knownProfile = 'C:\Users\Administrator.DESKTOP-GEV465F'
$knownJdk = 'D:\ClaudeGC\zulu21.52.15-ca-fx-jdk21.0.12-win_x64'
# --offline takes effect after the wrapper bootstrap. Verify its exact cache first.
$distributionRelative = 'wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66'
$expectedDistributionUrl = 'https://services.gradle.org/distributions/gradle-8.14.3-bin.zip'
$environmentNames = @('JAVA_HOME', 'GRADLE_USER_HOME', 'JAVA_TOOL_OPTIONS')
$savedEnvironment = @{}
foreach ($name in $environmentNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$exitCode = 1
$locationPushed = $false
try {
    foreach ($path in @($wrapper, $wrapperProperties, $wrapperJar, $localProperties)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Required project file is missing: $path. No dependency will be downloaded automatically."
        }
    }
    # Offline fail-closed model guard: a successful APK build must contain the verified real engines.
    $pianoInfo = Get-Content -LiteralPath (Join-Path $projectRoot 'app/src/main/assets/piano/model-manifest.json') -Raw -Encoding utf8 | ConvertFrom-Json
    $omrInfo = Get-Content -LiteralPath (Join-Path $projectRoot 'app/src/main/assets/omr/models.json') -Raw -Encoding utf8 | ConvertFrom-Json
    $modelFiles = @([pscustomobject]@{ path = 'tools/local/piano-model/bundle-assets/piano/onsets_frames_wavinput_no_offset_uni.tflite'; bytes = $pianoInfo.bytes; sha256 = $pianoInfo.sha256 })
    foreach ($entry in $omrInfo.models) {
        if ($entry.name -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Invalid OMR asset name in manifest' }
        $modelFiles += [pscustomobject]@{ path = ('tools/local/omr-probe/bundle-assets/omr/models/' + $entry.name); bytes = $entry.bytes; sha256 = $entry.sha256 }
    }
    $runtimeInfo = Get-Content -LiteralPath (Join-Path $projectRoot 'app/libs/runtime-manifest.json') -Raw -Encoding utf8 | ConvertFrom-Json
    foreach ($entry in $runtimeInfo) {
        if (-not $entry.packaged) { continue }
        $runtimeName = ([Uri]$entry.url).Segments[-1]
        if ($runtimeName -notmatch '^[A-Za-z0-9_.-]+\.(aar|jar)$') { throw 'Invalid runtime artifact name' }
        $modelFiles += [pscustomobject]@{ path = ('app/libs/' + $runtimeName); bytes = $entry.bytes; sha256 = $entry.sha256 }
    }
    function Read-Sha256([string]$Path) {
        $stream = [IO.File]::OpenRead($Path)
        $hasher = [Security.Cryptography.SHA256]::Create()
        try { return [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '') }
        finally { $hasher.Dispose(); $stream.Dispose() }
    }
    foreach ($entry in $modelFiles) {
        $modelPath = Join-Path $projectRoot $entry.path
        if (-not (Test-Path -LiteralPath $modelPath -PathType Leaf)) { throw "Required local model missing: $modelPath. No download was attempted." }
        $info = Get-Item -LiteralPath $modelPath
        if (($info.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or $info.Length -ne $entry.bytes -or (Read-Sha256 $modelPath) -ne $entry.sha256) { throw "Model integrity mismatch: $modelPath" }
    }
    $wrapperText = Get-Content -LiteralPath $wrapperProperties -Raw
    $urlMatch = [regex]::Match($wrapperText, '(?m)^distributionUrl=(.+)$')
    if (-not $urlMatch.Success -or $urlMatch.Groups[1].Value.Trim().Replace('\:', ':') -ne $expectedDistributionUrl) {
        throw 'The wrapper distribution changed. Update and verify this offline cache guard before building.'
    }

    $selectedJdk = $savedEnvironment['JAVA_HOME']
    if ($selectedJdk) { $selectedJdk = $selectedJdk.Trim('"') }
    if (-not $selectedJdk -or -not (Test-Path -LiteralPath (Join-Path $selectedJdk 'bin\java.exe') -PathType Leaf)) {
        $selectedJdk = $knownJdk
    }
    if (-not (Test-Path -LiteralPath (Join-Path $selectedJdk 'bin\java.exe') -PathType Leaf)) {
        throw 'No usable JAVA_HOME/bin/java.exe or verified fallback JDK was found. Configure a local JDK before building.'
    }

    $cacheCandidates = @()
    if ($env:USERPROFILE) { $cacheCandidates += Join-Path $env:USERPROFILE '.gradle' }
    $cacheCandidates += Join-Path $knownProfile '.gradle'
    $gradleCache = $null
    foreach ($candidate in @($cacheCandidates | Select-Object -Unique)) {
        $distributionRoot = Join-Path $candidate $distributionRelative
        $launcher = Join-Path $distributionRoot 'gradle-8.14.3\bin\gradle.bat'
        $completeMarker = Join-Path $distributionRoot 'gradle-8.14.3-bin.zip.ok'
        $dependencyCache = Join-Path $candidate 'caches\modules-2\files-2.1'
        if ((Test-Path -LiteralPath $launcher -PathType Leaf) -and
            (Test-Path -LiteralPath $completeMarker -PathType Leaf) -and
            (Test-Path -LiteralPath $dependencyCache -PathType Container)) {
            $gradleCache = $candidate
            break
        }
    }
    if (-not $gradleCache) {
        throw 'The verified Gradle 8.14.3 distribution and dependency cache are unavailable. Offline startup stopped; no download was attempted.'
    }

    $sdkMatch = [regex]::Match((Get-Content -LiteralPath $localProperties -Raw), '(?m)^\s*sdk\.dir\s*=\s*(.+)$')
    if (-not $sdkMatch.Success) { throw 'local.properties has no sdk.dir entry.' }
    $sdkRoot = $sdkMatch.Groups[1].Value.Trim().Replace('\\', '\').Replace('\:', ':')
    if (-not [IO.Path]::IsPathRooted($sdkRoot)) { $sdkRoot = Join-Path $projectRoot $sdkRoot }
    foreach ($relative in @('platforms\android-36\android.jar', 'build-tools\35.0.0\aapt2.exe')) {
        $requiredSdkFile = Join-Path $sdkRoot $relative
        if (-not (Test-Path -LiteralPath $requiredSdkFile -PathType Leaf)) {
            throw "Required Android SDK component is missing: $requiredSdkFile. Check local.properties; nothing was installed."
        }
    }

    New-Item -ItemType Directory -Path $javaTmp -Force | Out-Null
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $selectedJdk, 'Process')
    [Environment]::SetEnvironmentVariable('GRADLE_USER_HOME', $gradleCache, 'Process')
    $tempOption = '-Djdk.net.unixdomain.tmpdir="' + $javaTmp + '"'
    $originalOptions = $savedEnvironment['JAVA_TOOL_OPTIONS']
    $combinedOptions = if ([string]::IsNullOrWhiteSpace($originalOptions)) { $tempOption } else { $originalOptions + ' ' + $tempOption }
    [Environment]::SetEnvironmentVariable('JAVA_TOOL_OPTIONS', $combinedOptions, 'Process')

    $forwardedArguments = @($GradleArguments | Where-Object { $null -ne $_ })
    if ($forwardedArguments.Count -eq 0) { $forwardedArguments = @(':app:assembleDebug') }
    $effectiveArguments = @('--offline') + $forwardedArguments
    Write-Host ('Offline Gradle build. JAVA_HOME: ' + $selectedJdk)
    Write-Host ('Gradle cache: ' + $gradleCache)
    Write-Host ('Android SDK: ' + $sdkRoot)
    Push-Location -LiteralPath $projectRoot
    $locationPushed = $true
    & $wrapper @effectiveArguments
    $exitCode = $LASTEXITCODE
} catch {
    [Console]::Error.WriteLine('Build startup failed: ' + $_.Exception.Message)
    $exitCode = 1
} finally {
    if ($locationPushed) { Pop-Location }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
exit $exitCode
