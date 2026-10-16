param([string]$CachedApkDirectory = '')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$cache = Join-Path $projectRoot '.engine-cache'
$main = Join-Path $cache 'runtime/main'
$stamp = Join-Path $main 'prepared-v2.txt'
if ((Test-Path -LiteralPath $stamp) -and (Test-Path -LiteralPath (Join-Path $main 'jniLibs/arm64-v8a/libandroidapp.so')) -and (Test-Path -LiteralPath (Join-Path $main 'jniLibs/x86_64/libandroidapp.so'))) { Write-Output 'Pinned Office engine is ready.'; exit 0 }
New-Item -ItemType Directory -Path $main -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$artifacts = @(
    @{abi='x86_64'; sha='8EF8FC5AFF5AB4296A7F54D0293E49807CA6A606FFE47A7330B1F20A677DD544'},
    @{abi='arm64-v8a'; sha='554DF83F7748476E950867FFC5597F5C9074B8790F9D8D8AA5EEB807CC89FA86'}
)
foreach ($artifact in $artifacts) {
    $abi = $artifact.abi
    $short = if ($abi -eq 'arm64-v8a') { 'arm64' } else { $abi }
    $apk = Join-Path $cache "collabora-$abi.apk"
    $existing = if ($CachedApkDirectory) { Join-Path $CachedApkDirectory "collabora-$short.apk" } else { '' }
    if ($existing -and (Test-Path -LiteralPath $existing)) { $apk = [IO.Path]::GetFullPath($existing) }
    if (-not (Test-Path -LiteralPath $apk)) {
        $url = "https://www.collaboraoffice.com/downloads/Collabora-Office-Android-Snapshot/collabora-office-mobile-26.04.6.0-951-snapshot-$abi-2026-10-15.apk"
        Write-Output "Downloading pinned $abi Office runtime..."
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile "$apk.part"
        Move-Item -LiteralPath "$apk.part" -Destination $apk -Force
    }
    if ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -ne $artifact.sha) { throw "Office runtime SHA-256 mismatch: $abi" }
    $archive = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        foreach ($entry in $archive.Entries) {
            # ART profiles reference the upstream app's DEX, not this host's DEX.
            if ($entry.FullName.StartsWith('assets/dexopt/')) { continue }
            $relative = if ($entry.FullName.StartsWith("lib/$abi/")) { 'jniLibs/' + $entry.FullName.Substring(4) } elseif ($abi -eq 'x86_64' -and $entry.FullName.StartsWith('assets/')) { $entry.FullName } else { $null }
            if (-not $relative -or $relative.EndsWith('/')) { continue }
            $target = [IO.Path]::GetFullPath((Join-Path $main $relative))
            if (-not $target.StartsWith($main + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Office archive path escaped runtime.' }
            New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        }
    } finally { $archive.Dispose() }
}
$fonts = Join-Path $main 'assets/unpack/etc/fonts/fonts.conf'
$text = [IO.File]::ReadAllText($fonts).Replace('com.collabora.libreoffice', 'kr.documentstudio')
[IO.File]::WriteAllText($fonts, $text, [Text.UTF8Encoding]::new($false))
foreach ($name in @('baseline.prof','baseline.profm')) {
    $profile = Join-Path $main "assets/dexopt/$name"
    if (Test-Path -LiteralPath $profile) { Remove-Item -LiteralPath $profile -Force }
}
[IO.File]::WriteAllText($stamp, 'cf7b84af545e7112ee7ea69d7ae1ccbd2b38e4e9')
Write-Output 'Verified and extracted both Office ABIs.'
