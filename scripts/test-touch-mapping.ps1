param(
    [string]$Adb='C:\Tools\Android\platform-tools\adb.exe',
    [int]$Width=1920,
    [int]$Height=1200,
    [string]$OutDir='.pi/device-debug/touch-mapping'
)
$ErrorActionPreference='Stop'
# Requires the bounded XQueryPointer probe in the guest, logging to its serial console.
# Assert current-session coordinates, not merely successful Android event injection.
$vmBefore=(& $Adb shell pidof crosvm_default).Trim()
if($vmBefore -notmatch '^\d+$'){throw 'Exactly one running VM is required'}
& "$PSScriptRoot/assert-surface-frame.ps1" -OutDir $OutDir -Adb $Adb
if($LASTEXITCODE -ne 0){throw 'Guest pixels not visible'}
$report=Get-Content "$OutDir/frame-report.json" -Raw | ConvertFrom-Json
$b=$report.bounds
if($b[2]-$b[0] -lt 3000){throw 'Enter fullscreen before running touch validation'}
$started=(& $Adb shell "date '+%m-%d %H:%M:%S.000'").Trim()
$expected=@()
foreach($xy in @(@(.05,.05),@(.95,.05),@(.95,.95),@(.05,.95),@(.5,.5))){
    $x=[int][Math]::Round($b[0]+($b[2]-$b[0])*$xy[0])
    $y=[int][Math]::Round($b[1]+($b[3]-$b[1])*$xy[1])
    & $Adb shell input tap $x $y
    $expected+=@{x=[int][Math]::Round($Width*$xy[0]);y=[int][Math]::Round($Height*$xy[1])}
    Start-Sleep -Milliseconds 300
}
$log=(& $Adb logcat -b all -d -T $started -s virtmgr) -join "`n"
$log | Set-Content -Encoding utf8 "$OutDir/touch-log.txt"
$observed=@([regex]::Matches($log,"VIRUAISOR_POINTER_PROBE x=(\d+) y=(\d+) mask=\d+ width=$Width height=$Height\b") |
    ForEach-Object {@{x=[int]$_.Groups[1].Value;y=[int]$_.Groups[2].Value}})
$missing=@($expected | Where-Object {
    $p=$_
    !@($observed | Where-Object {[Math]::Abs($_.x-$p.x) -le 5 -and [Math]::Abs($_.y-$p.y) -le 5})
})
$passed=$missing.Count -eq 0 -and (& $Adb shell pidof crosvm_default).Trim() -eq $vmBefore
@{passed=$passed;width=$Width;height=$Height;expected=$expected;observed=$observed;missing=$missing} |
    ConvertTo-Json -Depth 5 | Tee-Object "$OutDir/touch-report.json"
if(!$passed){throw 'Guest touch coordinates differ from viewport targets'}
