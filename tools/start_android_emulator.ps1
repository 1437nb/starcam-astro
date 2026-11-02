#Requires -Version 7.0
param(
    [ValidateSet(28,34)][int]$Api = 34,
    [switch]$Headless,
    [string]$SdkPath = 'C:\dev\android-sdk',
    [string]$AvdHome = 'C:\dev\android-avd'
)
$ErrorActionPreference = 'Stop'
$env:ANDROID_AVD_HOME = $AvdHome
$emulator = Join-Path $SdkPath 'emulator\emulator.exe'
$adb = Join-Path $SdkPath 'platform-tools\adb.exe'
$name = "StarCam_API$Api"
$port = if ($Api -eq 34) { 5554 } else { 5556 }
if (-not (Test-Path -LiteralPath $emulator)) { throw "Android Emulator is missing: $emulator" }
if (-not (Test-Path -LiteralPath (Join-Path $AvdHome "$name.avd\config.ini"))) { throw "AVD is missing: $name. See docs/117." }
$state = & $adb -s "emulator-$port" get-state 2>$null
if ($state -eq 'device') {
    Write-Output "$name is already running on emulator-$port. To open a window after a headless run, stop this AVD first with adb -s emulator-$port emu kill."
    exit 0
}
$lab = Join-Path (Split-Path -Parent $PSScriptRoot) 'code\app\build\device-lab'
[System.IO.Directory]::CreateDirectory($lab) | Out-Null
$id = [guid]::NewGuid().ToString('N')
$options = @('-avd',$name,'-port',"$port",'-no-boot-anim','-no-snapshot','-gpu','software','-memory','2048','-cores','4','-no-audio','-camera-back','emulated','-camera-front','emulated')
if ($Headless) { $options += '-no-window' }
$window = if ($Headless) { 'Hidden' } else { 'Normal' }
$process = Start-Process -FilePath $emulator -ArgumentList $options -WindowStyle $window -PassThru -RedirectStandardOutput (Join-Path $lab "emulator-$id.stdout.log") -RedirectStandardError (Join-Path $lab "emulator-$id.stderr.log")
Write-Output "$name starting: PID=$($process.Id), serial=emulator-$port. Reports and logs: $lab"
