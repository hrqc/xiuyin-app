# Pure guards. Dot-sourcing never calls ADB or mutates a device.
function Select-XiuyinEmulator {
    param([string]$Listing, [string]$RequestedSerial)
    $records = @($Listing -split '\r?\n' | ForEach-Object {
        if ($_ -match '^\s*(\S+)\s+(\S+)(?:\s+.*)?$' -and $Matches[1] -ne 'List' -and $Matches[1] -ne '*') {
            [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
        }
    })
    if (-not [string]::IsNullOrEmpty($RequestedSerial)) {
        if ($RequestedSerial -cnotmatch '^emulator-[0-9]+$') { throw 'Selected target must be emulator-N; physical or invalid serial rejected.' }
        $selected = @($records | Where-Object { $_.Serial -ceq $RequestedSerial })
        if ($selected.Count -ne 1 -or $selected[0].State -cne 'device') {
            throw 'Selected emulator must appear once and be device; missing/offline/unauthorized/duplicate rejected.'
        }
        return $RequestedSerial
    }
    $online = @($records | Where-Object { $_.Serial -cmatch '^emulator-[0-9]+$' -and $_.State -ceq 'device' })
    if ($online.Count -ne 1) { throw 'Without a target, exactly one online emulator is required; set ANDROID_SERIAL or -Serial.' }
    $serial = $online[0].Serial
    if (@($records | Where-Object { $_.Serial -ceq $serial }).Count -ne 1) { throw 'Duplicate emulator rows; automatic selection rejected.' }
    return $serial
}

function Assert-XiuyinAdbTargetArguments {
    param([string[]]$Arguments, [string]$Serial)
    # Inventory is the sole allowed global command. Every other operation has a fixed target.
    if ($Arguments.Count -eq 1 -and $Arguments[0] -ceq 'devices') { return }
    if ($Serial -cnotmatch '^emulator-[0-9]+$' -or $Arguments.Count -lt 3 -or
        $Arguments[0] -cne '-s' -or $Arguments[1] -cne $Serial -or
        $Arguments[2] -cnotin @('install', 'shell', 'push', 'pull', 'exec-out')) {
        throw 'ADB operation lacks the selected emulator in explicit -s arguments; execution rejected.'
    }
}
