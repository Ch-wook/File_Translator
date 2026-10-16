param(
    [string]$DeviceId = '',
    [switch]$SkipDevice,
    [ValidateRange(1024,16384)][int]$JavaHeapMiB = 3072
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$evidence = Join-Path $repo "artifacts/acceptance-$stamp"
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$previousSerial = $env:ANDROID_SERIAL
$started = Get-Date
$buildExit = 1
$failure = $null
$device = $null
Push-Location -LiteralPath $repo
try {
    if (!$SkipDevice) {
        $adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe' } else { (Get-Command adb -ErrorAction Stop).Source }
        if (!$DeviceId) {
            $connected = @(& $adb devices | Select-String '^\S+\s+device$' | ForEach-Object { ($_.Line -split '\s+')[0] })
            if ($connected.Count -ne 1) { throw '검사할 기기 한 대를 연결하거나 -DeviceId로 지정하세요.' }
            $DeviceId = $connected[0]
        }
        $env:ANDROID_SERIAL = $DeviceId
        $device = [ordered]@{
            serial = $DeviceId
            api = (& $adb -s $DeviceId shell getprop ro.build.version.sdk | Out-String).Trim()
            abi = (& $adb -s $DeviceId shell getprop ro.product.cpu.abi | Out-String).Trim()
            page_size = (& $adb -s $DeviceId shell getconf PAGESIZE | Out-String).Trim()
            size = (& $adb -s $DeviceId shell wm size | Out-String).Trim()
            density = (& $adb -s $DeviceId shell wm density | Out-String).Trim()
        }
    }
    # cleanTest prevents an old JVM report from standing in for this run.
    $tasks = @(':core:cleanTest', ':core:test', ':app:lintDebug', ':app:assembleDebug', ':app:assembleDebugAndroidTest')
    if (!$SkipDevice) { $tasks += ':app:connectedDebugAndroidTest' }
    # Windows PowerShell 5 turns native stderr warnings into ErrorRecords.
    # The native exit code, XML results and lint determine the gate below.
    $ErrorActionPreference = 'Continue'
    try {
        & .\gradlew.bat @tasks --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx${JavaHeapMiB}m -XX:ActiveProcessorCount=4 -Dfile.encoding=UTF-8" '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --console=plain 2>&1 | Tee-Object -FilePath (Join-Path $evidence 'gradle.log')
        $buildExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = 'Stop' }
} catch {
    $failure = $_.Exception.Message
} finally {
    $env:ANDROID_SERIAL = $previousSerial
    $suites = @()
    $groups = @('jvm')
    if (!$SkipDevice) { $groups += 'android' }
    foreach ($group in $groups) {
        $source = if ($group -eq 'jvm') { 'core/build/test-results/test' } else { 'app/build/outputs/androidTest-results/connected/debug' }
        $target = Join-Path $evidence $group
        New-Item -ItemType Directory -Path $target -Force | Out-Null
        $reports = @(Get-ChildItem -LiteralPath (Join-Path $repo $source) -Filter 'TEST-*.xml' -Recurse -ErrorAction SilentlyContinue)
        foreach ($report in $reports) {
            # If Gradle never reached a test, retain the old report as evidence
            # but don't count it as a new successful execution.
            $fresh = $report.LastWriteTime -ge $started
            [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw -Encoding UTF8
            $root = $xml.DocumentElement
            Copy-Item -LiteralPath $report.FullName -Destination (Join-Path $target $report.Name)
            $suites += [ordered]@{
                group = $group; name = $root.GetAttribute('name'); fresh = $fresh
                tests = [int]$root.GetAttribute('tests'); failures = [int]$root.GetAttribute('failures')
                errors = [int]$root.GetAttribute('errors'); skipped = [int]$root.GetAttribute('skipped')
            }
        }
    }
    $lint = [ordered]@{ errors = $null; warnings = $null }
    $lintPath = Join-Path $repo 'app/build/reports/lint-results-debug.xml'
    if (Test-Path -LiteralPath $lintPath) {
        [xml]$lintXml = Get-Content -LiteralPath $lintPath -Raw -Encoding UTF8
        $lint.errors = @($lintXml.SelectNodes('//issue[@severity="Error" or @severity="Fatal"]')).Count
        $lint.warnings = @($lintXml.SelectNodes('//issue[@severity="Warning"]')).Count
        Copy-Item -LiteralPath $lintPath -Destination (Join-Path $evidence 'lint.xml')
        Copy-Item -LiteralPath (Join-Path $repo 'app/build/reports/lint-results-debug.html') -Destination (Join-Path $evidence 'lint.html') -ErrorAction SilentlyContinue
    }
    $freshSuites = @($suites | Where-Object { $_.fresh })
    $counts = [ordered]@{}
    foreach ($key in @('tests','failures','errors','skipped')) { $counts[$key] = [int](($freshSuites | ForEach-Object { $_[$key] } | Measure-Object -Sum).Sum) }
    $jvmRan = @($freshSuites | Where-Object { $_.group -eq 'jvm' -and $_.tests -gt 0 }).Count -gt 0
    $androidRan = @($freshSuites | Where-Object { $_.group -eq 'android' -and $_.tests -gt 0 }).Count -gt 0
    $passed = $buildExit -eq 0 -and !$failure -and $jvmRan -and ($SkipDevice -or $androidRan) -and $lint.errors -eq 0 -and $counts.failures -eq 0 -and $counts.errors -eq 0 -and $counts.skipped -eq 0
    $apk = Join-Path $repo 'app/build/outputs/apk/debug/app-debug.apk'
    $apkHash = if (Test-Path -LiteralPath $apk) { (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash } else { $null }
    $summary = [ordered]@{
        started = $started.ToString('o'); finished = (Get-Date).ToString('o')
        source_commit = (& git rev-parse HEAD | Out-String).Trim()
        source_dirty = @(& git status --porcelain).Count -gt 0
        status = if (!$passed) { 'failed' } elseif ($SkipDevice) { 'partial-device-not-tested' } else { 'passed' }
        full_automated_gate = $passed -and !$SkipDevice
        build_exit = $buildExit; failure = $failure; device = $device
        counts = $counts; suites = $suites; lint = $lint; debug_apk_sha256 = $apkHash
        scope = 'Automated debug checks only. See docs/FEATURE_ACCEPTANCE.md for unsupported features, physical-device and external-compatibility checks.'
    }
    [IO.File]::WriteAllText((Join-Path $evidence 'summary.json'), ($summary | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Pop-Location
}
Write-Host "검증 상태: $($summary.status), 검사 $($counts.tests)개, 실패 $($counts.failures), 오류 $($counts.errors), 건너뛰기 $($counts.skipped)"
Write-Host "근거: $evidence"
if (!$passed) { if ($failure) { Write-Host $failure }; exit 1 }
