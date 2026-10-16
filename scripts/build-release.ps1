param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $JavaHome) { $JavaHome = 'C:\Program Files\Java\jdk-17' }
$keytool = Join-Path $JavaHome 'bin/keytool.exe'
if (-not (Test-Path -LiteralPath $keytool)) { throw 'JDK 17 keytool is required. Pass -JavaHome.' }
$signingDir = Join-Path $projectRoot '.signing'
$configPath = Join-Path $signingDir 'release.json'
$taskPrevious = @{}
foreach ($name in @('JAVA_HOME','STUDIO_KEYSTORE','STUDIO_STORE_PASSWORD','STUDIO_KEY_ALIAS','STUDIO_KEY_PASSWORD')) { $taskPrevious[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:JAVA_HOME = $JavaHome
    if (-not $env:STUDIO_KEYSTORE) {
        if (-not (Test-Path -LiteralPath $signingDir)) {
            New-Item -ItemType Directory -Path $signingDir | Out-Null
            $acl = Get-Acl -LiteralPath $signingDir
            $acl.SetAccessRuleProtection($true, $false)
            foreach ($principal in @([Security.Principal.WindowsIdentity]::GetCurrent().Name, 'SYSTEM')) {
                $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($principal,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
            }
            Set-Acl -LiteralPath $signingDir -AclObject $acl
        }
        if (-not (Test-Path -LiteralPath $configPath)) {
            $secretBytes = New-Object byte[] 32
            $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $rng.GetBytes($secretBytes) } finally { $rng.Dispose() }
            $password = [Convert]::ToBase64String($secretBytes)
            $keystorePath = Join-Path $signingDir 'document-studio-upload.p12'
            if (Test-Path -LiteralPath $keystorePath) { throw 'Existing key has no configuration. Restore its release.json; do not replace the key.' }
            $env:STUDIO_STORE_PASSWORD = $password
            $env:STUDIO_KEY_PASSWORD = $password
            $oldPreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
            & $keytool -genkeypair -keystore $keystorePath -storetype PKCS12 -alias studioUpload -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=Document Studio Upload, OU=Local Build, O=Document Studio, C=KR' -storepass:env STUDIO_STORE_PASSWORD -keypass:env STUDIO_KEY_PASSWORD 2>&1 | Out-File (Join-Path $signingDir 'key-generation.log') -Encoding UTF8 -Width 400
            $keyExit = $LASTEXITCODE; $ErrorActionPreference = $oldPreference
            if ($keyExit -ne 0) { throw 'Signing key generation failed. Check the private key-generation log.' }
            @{ keystore=$keystorePath; alias='studioUpload'; storePassword=$password; keyPassword=$password } | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
            Write-Output 'Created a local signing key in .signing. Back up both the key and release.json privately.'
        }
        $config = Get-Content -LiteralPath $configPath -Encoding UTF8 -Raw | ConvertFrom-Json
        $env:STUDIO_KEYSTORE = $config.keystore
        $env:STUDIO_STORE_PASSWORD = $config.storePassword
        $env:STUDIO_KEY_ALIAS = $config.alias
        $env:STUDIO_KEY_PASSWORD = $config.keyPassword
    }
    $outputDir = Join-Path $projectRoot 'artifacts'
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    Push-Location $projectRoot
    try {
        $oldPreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        & .\gradlew.bat :app:assembleRelease :app:bundleRelease --console=plain 2>&1 | Out-File (Join-Path $outputDir 'release-build.log') -Encoding UTF8 -Width 400
        $buildExit = $LASTEXITCODE; $ErrorActionPreference = $oldPreference
        if ($buildExit -ne 0) { throw 'Release build failed. Check artifacts/release-build.log.' }
        Copy-Item -LiteralPath app/build/outputs/apk/release/app-release.apk -Destination (Join-Path $outputDir 'document-studio-release.apk') -Force
        Copy-Item -LiteralPath app/build/outputs/bundle/release/app-release.aab -Destination (Join-Path $outputDir 'document-studio-release.aab') -Force
    } finally { Pop-Location }
    Write-Output 'Built signed APK and AAB in artifacts. No store upload was performed.'
} finally {
    foreach ($name in $taskPrevious.Keys) { [Environment]::SetEnvironmentVariable($name,$taskPrevious[$name],'Process') }
}

