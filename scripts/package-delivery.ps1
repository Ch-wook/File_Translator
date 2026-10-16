param()
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$outputDir = Join-Path $projectRoot 'artifacts'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

$debug = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
foreach ($required in @($debug, (Join-Path $outputDir 'document-studio-release.apk'), (Join-Path $outputDir 'document-studio-release.aab'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Build debug and signed release first: $required" }
}
$newestInput = @('app/src/main','core/src/main','officeruntime/src/main') | ForEach-Object { Get-ChildItem -LiteralPath (Join-Path $projectRoot $_) -File -Recurse } | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
foreach ($required in @($debug, (Join-Path $outputDir 'document-studio-release.apk'), (Join-Path $outputDir 'document-studio-release.aab'))) {
    if ((Get-Item -LiteralPath $required).LastWriteTimeUtc -lt $newestInput.LastWriteTimeUtc) { throw "Stale build: $required" }
}
Copy-Item -LiteralPath $debug -Destination (Join-Path $outputDir 'document-studio-debug.apk') -Force

$reportDir = Join-Path $outputDir 'test-results'
New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
$suites = @()
foreach ($source in @('core/build/test-results/test','app/build/outputs/androidTest-results/connected/debug')) {
    $reports = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot $source) -Filter 'TEST-*.xml' -File)
    if ($reports.Count -eq 0) { throw "Run tests before packaging: $source" }
    foreach ($report in $reports) {
        [xml]$document = Get-Content -LiteralPath $report.FullName -Encoding UTF8 -Raw
        $suite = $document.testsuite
        if ([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) { throw "Test suite is not fully passing: $($report.Name)" }
        $suites += [ordered]@{name=[string]$suite.name; tests=[int]$suite.tests; failures=[int]$suite.failures; errors=[int]$suite.errors}
        Copy-Item -LiteralPath $report.FullName -Destination $reportDir -Force
    }
}
$lintPath = Join-Path $projectRoot 'app/build/reports/lint-results-debug.xml'
[xml]$lint = Get-Content -LiteralPath $lintPath -Encoding UTF8 -Raw
$blocking = @($lint.issues.issue | Where-Object { $_.severity -in @('Error','Fatal') })
if ($blocking.Count -gt 0) { throw 'Lint contains errors.' }
Copy-Item -LiteralPath $lintPath -Destination $reportDir -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'app/build/reports/lint-results-debug.html') -Destination $reportDir -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'app/build/reports/lint-results-debug.txt') -Destination $reportDir -Force
foreach ($report in @('core/build/reports/tests/test','app/build/reports/androidTests/connected/debug')) {
    $name = if ($report.StartsWith('core/')) { 'jvm-report' } else { 'android-report' }
    $destination = Join-Path $reportDir $name
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Copy-Item -Path ((Join-Path $projectRoot $report) + '/*') -Destination $destination -Recurse -Force
}
$version = [regex]::Match((Get-Content -LiteralPath (Join-Path $projectRoot 'app/build.gradle.kts') -Raw), 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$total = ($suites | ForEach-Object { $_['tests'] } | Measure-Object -Sum).Sum
[ordered]@{ version=$version; packagedAtUtc=[DateTime]::UtcNow.ToString('o'); tests=$total; suites=$suites; lintErrors=$blocking.Count; lintWarnings=@($lint.issues.issue | Where-Object {$_.severity -eq 'Warning'}).Count; device='Android 15 / API 35 x86_64 emulator; physical device not tested' } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $reportDir 'summary.json') -Encoding UTF8

$files = @('README.md','.gitignore','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat','app/build.gradle.kts','core/build.gradle.kts','officeruntime/build.gradle.kts','officeruntime/PROVENANCE.md') | ForEach-Object { Get-Item -LiteralPath (Join-Path $projectRoot $_) }
foreach ($directory in @('app/src','core/src','officeruntime/src','officeruntime/licenses','gradle','docs','scripts')) { $files += Get-ChildItem -LiteralPath (Join-Path $projectRoot $directory) -File -Recurse }
$archivePath = Join-Path $outputDir 'document-studio-source.zip'
$temporary = Join-Path $outputDir ('source-' + [guid]::NewGuid().ToString() + '.zip')
$archive = [IO.Compression.ZipFile]::Open($temporary, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in ($files | Sort-Object FullName -Unique)) {
        if (-not $file.FullName.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Source path escaped the project.' }
        $relative = $file.FullName.Substring($projectRoot.Length+1).Replace('\','/')
        if ($relative -match '(^|/)(\.signing|\.tools|build|artifacts)(/|$)|(^|/)(local\.properties|release\.json)$|\.(jks|keystore|p12)$') { throw "Private/generated input rejected: $relative" }
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $relative, [IO.Compression.CompressionLevel]::Optimal)
    }
} finally { $archive.Dispose() }
$inspection = [IO.Compression.ZipFile]::OpenRead($temporary)
try { if ($inspection.Entries.Count -lt 50) { throw 'Incomplete source archive.' }; $entryCount=$inspection.Entries.Count } finally { $inspection.Dispose() }
Move-Item -LiteralPath $temporary -Destination $archivePath -Force

$hashes = @('document-studio-debug.apk','document-studio-release.apk','document-studio-release.aab','document-studio-source.zip') | ForEach-Object {
    $hash = Get-FileHash -LiteralPath (Join-Path $outputDir $_) -Algorithm SHA256
    $hash.Hash.ToLowerInvariant() + '  ' + $_
}
$hashes | Set-Content -LiteralPath (Join-Path $outputDir 'SHA256SUMS.txt') -Encoding ASCII
Write-Output "Packaged version $version; $total passing tests; $entryCount source entries. Signing secrets excluded."

