param([Parameter(Mandatory=$true)][string]$Apk)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repo=Split-Path -Parent $PSScriptRoot
$policy=[IO.File]::ReadAllText((Join-Path $repo 'app/src/main/java/pro/darc/viruaisor/root/RootOverlayArtifactPolicy.kt'))
$expected=[regex]::Match($policy,'DESKTOP_SHA256 = "([a-f0-9]{64})"').Groups[1].Value
$expectedBytes=[long][regex]::Match($policy,'DESKTOP_BYTES = ([0-9]+)L').Groups[1].Value
$zip=[IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Apk))
try {
    foreach ($name in @('libviruaisor_native.so','libviruaisor_storage.so','libviruaisor_storage_launcher.so','libvenus_host_probe.so','libvenus_overlay_probe.so','libvenus_crosvm.so','libvenus_desktop_crosvm.so','librootfs_e2fsck.so','librootfs_resize2fs.so','librootfs_debugfs.so','librootfs_copy.so')) {
        $entry=$zip.GetEntry("lib/arm64-v8a/$name")
        if ($null -eq $entry) { throw "Missing arm64 native artifact: $name" }
        $stream=$entry.Open()
        try {
            $header=New-Object byte[] 20
            if ($stream.Read($header,0,20) -ne 20 -or $header[0] -ne 127 -or $header[1] -ne 69 -or $header[2] -ne 76 -or
                $header[3] -ne 70 -or $header[4] -ne 2 -or $header[5] -ne 1 -or $header[18] -ne 183 -or $header[19] -ne 0) {
                throw "Not an AArch64 ELF: $name"
            }
        } finally { $stream.Dispose() }
        if ($name -eq 'libvenus_desktop_crosvm.so') {
            $stream=$entry.Open(); $sha=[Security.Cryptography.SHA256]::Create()
            try { $actual=([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
            finally { $stream.Dispose(); $sha.Dispose() }
            if ($actual -ne $expected -or $entry.Length -ne $expectedBytes) { throw 'APK desktop runtime does not match its source pin' }
        }
        if ($name -like 'librootfs_*') {
            $source=Join-Path $repo "app/src/main/jniLibs/arm64-v8a/$name"
            $stream=$entry.Open(); $sha=[Security.Cryptography.SHA256]::Create()
            try { $actual=([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
            finally { $stream.Dispose(); $sha.Dispose() }
            if ($actual -ne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()) {
                throw "APK ext4 tool differs from its built artifact: $name"
            }
        }
    }
    if ($null -eq $zip.GetEntry('assets/third_party/rootfs-tools-notices.txt')) { throw 'Missing e2fsprogs notices' }
} finally { $zip.Dispose() }
Write-Output 'PASS: eleven arm64 ELF artifacts present; desktop pin and ext4 tool bytes verified; e2fsprogs notices included.'
