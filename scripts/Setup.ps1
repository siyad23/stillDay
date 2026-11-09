$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$toolsPath = Join-Path $projectPath '.tools'
$jdkTargetPath = Join-Path $toolsPath 'jdk17'
if (-not (Test-Path -LiteralPath $jdkTargetPath)) {
    New-Item -ItemType Directory -Force -Path $toolsPath | Out-Null
    $jdkPackage = (Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse')[0].binary.package
    $jdkZipPath = Join-Path $toolsPath 'jdk17.zip'
    Write-Host 'Downloading a project-local Eclipse Temurin JDK 17...'
    Invoke-WebRequest -Uri $jdkPackage.link -OutFile $jdkZipPath -UseBasicParsing
    if ((Get-FileHash -LiteralPath $jdkZipPath -Algorithm SHA256).Hash -ne $jdkPackage.checksum) { throw 'JDK checksum verification failed.' }
    Expand-Archive -LiteralPath $jdkZipPath -DestinationPath $jdkTargetPath
}
Write-Host 'JDK ready. Android SDK platform 36 is required; the emulator also needs the Android 36 Google Play x86_64 image.'
Write-Host 'Build with: .\scripts\Build.ps1'
