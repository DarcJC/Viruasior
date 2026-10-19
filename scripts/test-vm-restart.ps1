# Requires an installed debug APK, an imported Venus bundle, and existing root/AVF access.
# Keep the device unlocked. No force-stop or policy changes are made between runs.
param(
    [string]$Label = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [ValidateRange(2, 10)][int]$Cycles = 3,
    [string]$Package
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$packageArgs = if ($PSBoundParameters.ContainsKey('Package')) { @('--package', $Package) } else { @() }
$Package = (& node (Join-Path $PSScriptRoot 'application-id.cjs') @packageArgs)
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve installed applicationId from APK metadata or PACKAGE/-Package' }
$Package = $Package.Trim()
$rootProcessPattern = '\bcrosvm(?:_|\b)|\bvirtmgr(?:_|\b)|libvenus_overla|' + [regex]::Escape($Package) + ':root:'
$out = Join-Path $repo ".pi/device-debug/restart-$Label"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$tags = @('ViruaisorAvf', 'ViruaisorService', 'ViruaisorState', 'ViruaisorVenusDesktop', 'ViruaisorRootDisplay', 'AndroidRuntime')
$script:appPid = ''

function Wait-State([string]$stamp, [string]$expected, [string]$phase, [switch]$Cleanup) {
    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    $stableSince = $null
    do {
        $lines = & adb logcat -d -v threadtime -T $stamp -s @tags
        if ($LASTEXITCODE -ne 0) { throw 'Cannot read device logs' }
        $lines | Set-Content -LiteralPath (Join-Path $out "$phase.log")
        $states = @($lines | Select-String 'ViruaisorState.*status=')
        $last = if ($states.Count) { $states[-1].Line } else { '' }
        if ($lines -match 'status=Crashed|FATAL EXCEPTION|DESKTOP_LEASE_CLEANUP_FAILED|ROLLBACK=FAIL') {
            throw "FAIL ${phase}: $last; inspect $out"
        }
        $currentPid = ((& adb shell pidof "${Package}:venus_vm") -join '').Trim()
        if (!$currentPid -or ($script:appPid -and $script:appPid -ne $currentPid)) {
            throw "FAIL ${phase}: App process exited or restarted ($script:appPid -> $currentPid)"
        }
        if (!$script:appPid) { $script:appPid = $currentPid }
        $complete = $last -match $expected
        if ($Cleanup) {
            $processes = & adb shell ps -A
            $processes | Set-Content -LiteralPath (Join-Path $out 'processes-after-stop.txt')
            $complete = $complete -and [bool]($lines -match 'DESKTOP_LEASE_END.*native_exit=0') -and
                [bool]($lines -match 'STOCK_LEASE_RELEASED_BY_PIDFD children_exited=1 owner_preserved=1') -and
                [bool]($lines -match 'ROLLBACK=PASS') -and
                ![bool]($processes -match $rootProcessPattern)
        }
        if ($complete) {
            if ($null -eq $stableSince) { $stableSince = [DateTime]::UtcNow }
            if (([DateTime]::UtcNow - $stableSince).TotalSeconds -ge 5) {
                Write-Output "PASS ${phase}: pid=$currentPid $last"
                return
            }
        } else { $stableSince = $null }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "FAIL ${phase}: timed out; last state: $last; inspect $out"
}

if ((& adb shell ps -A) -match '\bcrosvm(?:_|\b)') { throw 'Stop existing VMs before this single-VM test' }
& adb shell am start -W -f 0x20000000 -n "$Package/pro.darc.viruaisor.MainActivity" -a "$Package.action.DEBUG_ROOT_DISPLAY" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Cannot open Viruaisor' }
for ($cycle = 1; $cycle -le $Cycles; $cycle++) {
    Start-Sleep -Seconds 1
    $stamp = (& adb shell "date '+%m-%d %H:%M:%S.000'").Trim()
    & adb shell am start -W -f 0x20000000 -n "$Package/pro.darc.viruaisor.MainActivity" -a "$Package.action.DEBUG_START" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot send Start' }
    Wait-State $stamp 'status=GuestAgentReady' "start-$cycle"
    Start-Sleep -Seconds 1
    $stamp = (& adb shell "date '+%m-%d %H:%M:%S.000'").Trim()
    & adb shell am start -W -f 0x20000000 -n "$Package/pro.darc.viruaisor.MainActivity" -a "$Package.action.DEBUG_STOP" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot send Stop' }
    Wait-State $stamp 'status=Stopped' "stop-$cycle"
}
# The UI can reach Stopped before asynchronous lease cleanup finishes.
Wait-State $stamp 'status=Stopped' 'final-cleanup' -Cleanup
Write-Output "PASS: $Cycles start/stop cycles; unchanged App PID=$script:appPid. Evidence: $out"
