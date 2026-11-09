param([string[]]$Tasks = @(':domain:test', ':app:assembleDebug', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
Push-Location $projectPath
try {
    $localJdkPath = Join-Path $projectPath '.tools\jdk17'
    if (Test-Path -LiteralPath $localJdkPath) {
        $env:JAVA_HOME = (Get-ChildItem -LiteralPath $localJdkPath -Directory | Select-Object -First 1).FullName
    }
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 17 installation, or run scripts\Setup.ps1.' }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
    if (-not (Test-Path -LiteralPath $env:ANDROID_HOME)) { throw 'Install Android SDK platform 36 and set ANDROID_HOME.' }
    $env:DEBUG = ''
    & .\gradlew.bat @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
} finally { Pop-Location }
