# TEST DATA only: pure inventory/argument checks, no ADB process or device operation.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'emulator-selection.ps1')
$cases = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'fixtures\emulator-selection-cases.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$checked = 0
foreach ($case in $cases) {
    $actual = $null
    try { $actual = Select-XiuyinEmulator -Listing $case.listing -RequestedSerial $case.requested }
    catch { if ($null -ne $case.expected) { throw ('Unexpected rejection: ' + $case.name + ': ' + $_.Exception.Message) } }
    if ($actual -cne $case.expected) { throw ('Wrong selection: ' + $case.name + '; actual=' + $actual) }
    $checked++
}
$guards = @(
    @{ Name='global_inventory'; Arguments=@('devices'); Serial=$null; Allowed=$true },
    @{ Name='selected_install'; Arguments=@('-s','emulator-5562','install','-r','TEST.apk'); Serial='emulator-5562'; Allowed=$true },
    @{ Name='selected_shell'; Arguments=@('-s','emulator-5562','shell','am','force-stop','TEST'); Serial='emulator-5562'; Allowed=$true },
    @{ Name='selected_push'; Arguments=@('-s','emulator-5562','push','TEST','TEST'); Serial='emulator-5562'; Allowed=$true },
    @{ Name='unbound_install'; Arguments=@('install','-r','TEST.apk'); Serial='emulator-5562'; Allowed=$false },
    @{ Name='wrong_emulator'; Arguments=@('-s','emulator-5582','shell','am','force-stop','TEST'); Serial='emulator-5562'; Allowed=$false },
    @{ Name='physical_target'; Arguments=@('-s','PHONE123','shell','am','force-stop','TEST'); Serial='PHONE123'; Allowed=$false },
    @{ Name='global_stop'; Arguments=@('kill-server'); Serial='emulator-5562'; Allowed=$false },
    @{ Name='global_stop_with_serial'; Arguments=@('-s','emulator-5562','kill-server'); Serial='emulator-5562'; Allowed=$false }
)
foreach ($case in $guards) {
    $accepted = $true
    try { Assert-XiuyinAdbTargetArguments -Arguments $case.Arguments -Serial $case.Serial } catch { $accepted = $false }
    if ($accepted -ne $case.Allowed) { throw ('Wrong argument guard: ' + $case.Name) }
}
# Parse the actual callers without executing their build/install/test bodies.
foreach ($name in @('check.ps1','device-test.ps1','emulator-selection.ps1')) {
    $tokens = $null; $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name), [ref]$tokens, [ref]$errors)
    if ($errors.Count -gt 0) { throw ('PowerShell parse failure: ' + $name + ': ' + ($errors | Out-String)) }
}
[pscustomobject]@{status='PASS';selectionCases=$checked;argumentGuardCases=$guards.Count;parsedCallers=3;deviceOperations=0} | ConvertTo-Json -Compress
