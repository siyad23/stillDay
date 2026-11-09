param([switch]$Visible)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$avdRootPath = Join-Path $projectPath '.android\avd'
$pixelAvdPath = Join-Path $avdRootPath 'Stillday_Pixel_7.avd'
$logPath = Join-Path $projectPath '.tools'
$systemImagePath = Join-Path $sdkPath 'system-images\android-36\google_apis_playstore\x86_64'
if (-not (Test-Path -LiteralPath $systemImagePath)) { throw 'Install the Android 36 Google Play x86_64 system image in Android Studio SDK Manager.' }
New-Item -ItemType Directory -Force -Path $pixelAvdPath, $logPath | Out-Null
$utf8Encoding = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText((Join-Path $avdRootPath 'Stillday_Pixel_7.ini'), "avd.ini.encoding=UTF-8`npath=$pixelAvdPath`ntarget=android-36`n", $utf8Encoding)
$configText = @"
AvdId=Stillday_Pixel_7
avd.ini.displayname=Stillday Pixel 7
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=2048
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
hw.device.name=pixel_7
hw.device.manufacturer=Google
hw.keyboard=yes
hw.mainKeys=no
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.audioInput=no
hw.battery=yes
hw.camera.back=none
hw.camera.front=none
disk.dataPartition.size=3G
image.sysdir.1=$systemImagePath\
tag.id=google_apis_playstore
tag.display=Google Play
PlayStore.enabled=true
skin.dynamic=yes
skin.name=1080x2400
vm.heapSize=256
fastboot.forceColdBoot=yes
"@
[System.IO.File]::WriteAllText((Join-Path $pixelAvdPath 'config.ini'), $configText, $utf8Encoding)
$env:ANDROID_AVD_HOME = $avdRootPath
& "$sdkPath\platform-tools\adb.exe" start-server
$emulatorArguments = @('-avd', 'Stillday_Pixel_7', '-no-audio', '-no-snapshot')
if (-not $Visible) { $emulatorArguments += '-no-window' }
$windowStyle = if ($Visible) { 'Normal' } else { 'Hidden' }
Start-Process -FilePath "$sdkPath\emulator\emulator.exe" -ArgumentList $emulatorArguments -WindowStyle $windowStyle -RedirectStandardOutput (Join-Path $logPath 'emulator.log') -RedirectStandardError (Join-Path $logPath 'emulator-error.log')
Write-Host 'Pixel 7 emulator started: 1080 x 2400, 420 dpi. Run connected tests after it finishes booting.'
