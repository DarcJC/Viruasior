param(
    [string]$Adb='C:\Tools\Android\platform-tools\adb.exe',
    [string[]]$Modes=@('1920x1200@120000','800x500','1920x1200@120000'),
    [string]$OutDir='.pi/device-debug/resize-validation'
)
$ErrorActionPreference='Stop'
$vmBefore=(& $Adb shell pidof crosvm_default).Trim()
if(!$vmBefore -or $vmBefore -notmatch '^\d+$'){throw 'Exactly one running VM is required'}
$reports=@()
foreach($mode in $Modes){
    if($mode -notmatch '^(\d{1,4})x(\d{1,4})(?:@(\d{5,6}))?$'){throw 'Invalid test mode'}
    $width=[int]$Matches[1];$height=[int]$Matches[2]
    $rate=if($Matches[3]){[int]$Matches[3]}else{0}
    if($width -lt 1 -or $height -lt 1 -or $width -gt 4096 -or $height -gt 4096 -or [long]$width*$height -gt 8388608 -or
       ($rate -ne 0 -and ($rate -lt 10000 -or $rate -gt 120250 -or ([long]$width*$height -gt 4194304 -and $rate -gt 60250)))){throw 'Mode exceeds budget'}
    $started=(& $Adb shell "date '+%m-%d %H:%M:%S.000'").Trim()
    & $Adb shell am start -W -f 0x20000000 -n pro.darc.viruaisor/.MainActivity -a pro.darc.viruaisor.action.DEBUG_RESIZE --ei width $width --ei height $height --ei millihz $rate | Out-Null
    $confirmed=$false
    for($attempt=0;$attempt -lt 40;$attempt++){
        $log=(& $Adb logcat -d -T $started -s ViruaisorState) -join "`n"
        $ratePattern=if($rate){[string]$rate}else{'[0-9]+'}
        $latest=& $Adb logcat -d -s ViruaisorDisplay
        $last=($latest | Select-String DISPLAY_PRESENTED | Select-Object -Last 1).Line
        # Rate-only changes acknowledge a new mode without a new size callback.
        if($log -match "DISPLAY_MODE_CONFIRMED width=$width height=$height millihz=$ratePattern\b" -and
           $last -match "width=$width height=$height generation=\d+"){$confirmed=$true;break}
        Start-Sleep -Milliseconds 500
    }
    if(!$confirmed){throw "No confirmed frame for $mode"}
    Start-Sleep -Milliseconds 500
    $capture=Join-Path $OutDir ("{0}-{1}" -f $reports.Count,$mode)
    & "$PSScriptRoot/assert-surface-frame.ps1" -OutDir $capture -Adb $Adb
    if($LASTEXITCODE -ne 0){throw "Pixel validation failed for $mode"}
    $vmAfter=(& $Adb shell pidof crosvm_default).Trim()
    if($vmAfter -ne $vmBefore){throw 'VM restarted during modeset'}
    $reports+=@{mode=$mode;pid=$vmAfter;pixels=(Get-Content (Join-Path $capture 'frame-report.json') -Raw | ConvertFrom-Json)}
}
$reports | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 (Join-Path $OutDir 'resize-report.json')
Write-Output "PASS: $($Modes -join ' -> '); VM PID $vmBefore unchanged"
