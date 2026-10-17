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

Add-Type -AssemblyName System.Drawing
$bitmap = New-Object System.Drawing.Bitmap($imagePath)
$lit = 0
try {
    if ($coords[0] -lt 0 -or $coords[1] -lt 0 -or $coords[2] -gt $bitmap.Width -or $coords[3] -gt $bitmap.Height) {
        throw 'UI bounds exceed screenshot; retry after layout settles'
    }
    for ($iy = 1; $iy -le 20; $iy++) {
        for ($ix = 1; $ix -le 20; $ix++) {
            # Central 80% avoids edges, shadows and navigation-bar clipping.
            $x = $coords[0] + [int](($coords[2]-$coords[0])*(0.1+0.8*$ix/21))
            $y = $coords[1] + [int](($coords[3]-$coords[1])*(0.1+0.8*$iy/21))
            $pixel = $bitmap.GetPixel($x, $y)
            if ([int]$pixel.R + [int]$pixel.G + [int]$pixel.B -gt 12) { $lit++ }
        }
    }
} finally { $bitmap.Dispose() }
Write-Output "Surface bounds=$($surface.bounds); non-black samples=$lit/400; evidence=$imagePath"
if ($lit -lt 4) { Write-Output 'RED: Surface viewport is black'; exit 1 }
Write-Output 'GREEN: Non-black Surface content; inspect the image to confirm guest frames, not just attachment'
