# Windows device probe. Captures are private/local and must stay in the ignored debug directory.
param(
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$Adb = 'C:\Tools\Android\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$debugRoot = [System.IO.Path]::GetFullPath((Join-Path $repo '.pi/device-debug'))
$output = [System.IO.Path]::GetFullPath($OutDir)
if (-not $output.StartsWith($debugRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutDir must be a capture subdirectory under .pi/device-debug/'
}
[System.IO.Directory]::CreateDirectory($output) | Out-Null

# Read-only graphics/UI inspection; no root, policy changes, lockscreen bypass or fake host drawing.
$remoteUi = '/sdcard/Android/data/pro.darc.viruaisor/files/viruaisor-frame.xml'
& $Adb shell uiautomator dump $remoteUi | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'UI dump failed' }
$xmlPath = Join-Path $output 'frame-ui.xml'
& $Adb pull $remoteUi $xmlPath | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'UI dump pull failed' }
[xml]$ui = [System.IO.File]::ReadAllText($xmlPath)
$surface = $ui.SelectSingleNode('//node[(@class="android.view.SurfaceView" or @class="pro.darc.viruaisor.display.DesktopSurfaceView") and @package="pro.darc.viruaisor"]')
if ($null -eq $surface) { throw 'No visible app SurfaceView; open Viruaisor without bypassing lockscreen' }
$coords = @([regex]::Matches($surface.bounds, '[0-9]+') | ForEach-Object { [int]$_.Value })
if ($coords.Count -ne 4 -or $coords[2]-$coords[0] -lt 100 -or $coords[3]-$coords[1] -lt 100) {
    throw 'Invalid/too-small SurfaceView bounds'
}
$imagePath = Join-Path $output 'frame-probe.png'
$info = New-Object System.Diagnostics.ProcessStartInfo
$info.FileName = $Adb
$info.Arguments = 'exec-out screencap -p'
$info.UseShellExecute = $false
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
$p = [System.Diagnostics.Process]::Start($info)
try {
    $stderr = $p.StandardError.ReadToEndAsync()
    $file = [System.IO.File]::Create($imagePath)
    try { $p.StandardOutput.BaseStream.CopyTo($file) } finally { $file.Dispose() }
    $p.WaitForExit()
    if ($p.ExitCode -ne 0) { throw $stderr.Result }
} finally { $p.Dispose() }

$report = & node (Join-Path $repo 'scripts/assert-surface-pixels.cjs') $imagePath $xmlPath
$verdict = $LASTEXITCODE
$report | Set-Content -Encoding utf8 (Join-Path $output 'frame-report.json')
Write-Output $report
Write-Output "Evidence=$imagePath; physical panel colours still require visual verification"
exit $verdict
