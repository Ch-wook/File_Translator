param(
    [string]$AndroidSdk = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$JavaHome = 'C:/Program Files/Java/jdk-17'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$outputDir = Join-Path $projectRoot 'artifacts'
$apkPath = Join-Path $outputDir 'document-studio-release.apk'
$aabPath = Join-Path $outputDir 'document-studio-release.aab'
$buildTools = Join-Path $AndroidSdk 'build-tools/35.0.0'
$previousJava = $env:JAVA_HOME
Add-Type -AssemblyName System.IO.Compression.FileSystem
try {
    $env:JAVA_HOME = $JavaHome
    $signature = & (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $alignment = & (Join-Path $buildTools 'zipalign.exe') -c -P 16 -v 4 $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'APK 16 KB ZIP alignment failed.' }
    $jarsigner = & (Join-Path $JavaHome 'bin/jarsigner.exe') -verify $aabPath 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'AAB signature verification failed.' }
    $permissions = & (Join-Path $buildTools 'aapt2.exe') dump permissions $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'Manifest inspection failed.' }
    if (($permissions -join "`n") -match 'android.permission.(INTERNET|MANAGE_EXTERNAL_STORAGE|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE)') { throw 'Unexpected network/global storage permission.' }
    $manifest = & (Join-Path $buildTools 'aapt2.exe') dump xmltree $apkPath --file AndroidManifest.xml
    if ($LASTEXITCODE -ne 0) { throw 'Manifest inspection failed.' }
    if (($manifest -join "`n") -match 'kr.documentstudio.(faults|testfiles|officeprobe)|FailingProvider|OfficeProbeProvider|android:debuggable.*0xffffffff') { throw 'Debug component found in release manifest.' }
    $libraries = @()
    $apk = [IO.Compression.ZipFile]::OpenRead($apkPath)
    $aab = [IO.Compression.ZipFile]::OpenRead($aabPath)
    try {
        foreach ($entry in $apk.Entries | Where-Object { $_.FullName -match '^lib/.+\.so$' }) {
            $memory = [IO.MemoryStream]::new()
            $stream = $entry.Open()
            try { $stream.CopyTo($memory); $bytes=$memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
            if ($bytes.Length -lt 64 -or $bytes[0] -ne 127 -or $bytes[1] -ne 69 -or $bytes[2] -ne 76 -or $bytes[3] -ne 70 -or $bytes[4] -notin @(1,2) -or $bytes[5] -ne 1) { throw "Unsupported ELF: $($entry.FullName)" }
            $is64 = $bytes[4] -eq 2
            $offset = if ($is64) {[BitConverter]::ToUInt64($bytes,32)} else {[BitConverter]::ToUInt32($bytes,28)}
            $entrySize = [BitConverter]::ToUInt16($bytes, $(if ($is64) {54} else {42}))
            $count = [BitConverter]::ToUInt16($bytes, $(if ($is64) {56} else {44}))
            $requiredAlignment = if ($is64) {16384} else {4096}
            $loads = @()
            for ($i=0; $i -lt $count; $i++) {
                $header = [int]($offset+$entrySize*$i)
                if ([BitConverter]::ToUInt32($bytes,$header) -eq 1) {
                    $fileOffset=if ($is64) {[BitConverter]::ToUInt64($bytes,$header+8)} else {[BitConverter]::ToUInt32($bytes,$header+4)}
                    $address=if ($is64) {[BitConverter]::ToUInt64($bytes,$header+16)} else {[BitConverter]::ToUInt32($bytes,$header+8)}
                    $align=if ($is64) {[BitConverter]::ToUInt64($bytes,$header+48)} else {[BitConverter]::ToUInt32($bytes,$header+28)}
                    if ($align -lt $requiredAlignment -or $fileOffset%$requiredAlignment -ne $address%$requiredAlignment) { throw "ELF LOAD alignment failed: $($entry.FullName)" }
                    $loads += $align
                }
            }
            $bundleEntry=$aab.GetEntry('base/'+$entry.FullName)
            if (-not $bundleEntry) { throw 'Native library missing from AAB.' }
            $digest=[Security.Cryptography.SHA256]::Create()
            $bundleStream=$bundleEntry.Open()
            try {
                $apkHash=[BitConverter]::ToString($digest.ComputeHash($bytes))
                $aabHash=[BitConverter]::ToString($digest.ComputeHash($bundleStream))
                if ($apkHash -ne $aabHash) { throw 'APK and AAB native libraries differ.' }
            } finally { $bundleStream.Dispose(); $digest.Dispose() }
            if ($loads.Count -eq 0) { throw 'ELF has no LOAD segment.' }
            $libraries += [ordered]@{path=$entry.FullName; bits=$(if ($is64) {64} else {32}); requiredAlignment=$requiredAlignment; loadAlignments=$loads; matchesBundle=$true}
        }
    } finally { $apk.Dispose(); $aab.Dispose() }
    $certificate = $signature | Where-Object {$_ -match 'Signer #1 certificate SHA-256 digest:'} | Select-Object -First 1
    $report = [ordered]@{verifiedAtUtc=[DateTime]::UtcNow.ToString('o'); apkSha256=(Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash; aabSha256=(Get-FileHash -LiteralPath $aabPath -Algorithm SHA256).Hash; apkSignature='apksigner verified'; certificate=$certificate; bundleSignature=($jarsigner -join "`n"); zip16KbAligned=$true; nativeLibraries=$libraries; actual16KbDeviceTested=$false; physicalDeviceTested=$false; playUploaded=$false}
    $reportDir = Join-Path $outputDir 'test-results'
    New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $reportDir 'release-verification.json') -Encoding UTF8
    Write-Output "PASS: signed APK/AAB, release manifest, 16 KB ZIP and 64-bit ELF alignment; $($libraries.Count) native libraries match. Physical 16 KB execution not tested."
} finally { $env:JAVA_HOME = $previousJava }
