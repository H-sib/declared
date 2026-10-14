# Sweeps one API level on a fresh emulator, then removes the AVD (and the image unless -KeepImage) to bound disk use.
# Usage: .\tools\sweep.ps1 -Api 29 -Image 'system-images;android-29;google_apis;x86_64'
param(
    [Parameter(Mandatory = $true)][int]$Api,
    [Parameter(Mandatory = $true)][string]$Image,
    [switch]$KeepImage
)
$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'env.ps1') | Out-Null
$root = 'C:\Users\lordhasib\AndroidLab'
$env:ANDROID_AVD_HOME = "$root\avd"
$adb = "$root\sdk\platform-tools\adb.exe"
$sdkm = "$root\sdk\cmdline-tools\latest\bin\sdkmanager.bat"
$avdm = "$root\sdk\cmdline-tools\latest\bin\avdmanager.bat"
$runner = Join-Path $PSScriptRoot 'runner.py'
$name = "lab$Api"
$serial = 'emulator-5554'
$logDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'results\raw'
New-Item -ItemType Directory -Force $logDir | Out-Null

function Log($m) { Write-Output ("[{0}] {1}" -f $Api, $m) }

# sdkmanager writes progress bars and status to stderr; convert every line to a string before filtering.
function Sdk($argsText) {
    cmd /c "`"$sdkm`" $argsText" 2>&1 | ForEach-Object { "$_" } |
        Where-Object { $_ -notmatch '^\[=*\s*\]' -and $_.Trim() -ne '' -and $_ -notmatch 'deprecated|android-cli|Android CLI|cmdline-tools directory' }
}

Log "installing image $Image"
Sdk "--install `"$Image`"" | Select-Object -Last 3 | ForEach-Object { Log "$_" }
if (-not (Test-Path "$root\sdk\system-images")) { Log 'no system-images directory, aborting'; exit 1 }

Log 'creating AVD'
'no' | & $avdm create avd -n $name -k $Image -d pixel_6 --force 2>&1 | Select-Object -Last 2 | ForEach-Object { Log "$_" }

# avdmanager defaults the data partition to 10 GB, and the emulator allocates all of it while running.
# The lab apps need a few hundred MB, so cap it to keep the peak disk use near the approved budget.
$cfg = "$root\avd\$name.avd\config.ini"
if (Test-Path $cfg) {
    (Get-Content $cfg) -replace '^disk\.dataPartition\.size=.*$', 'disk.dataPartition.size=3G' | Set-Content $cfg -Encoding ASCII
    Log 'data partition capped at 3G'
}

Log 'starting emulator'
$emuArgs = @('-avd', $name, '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot', '-gpu', 'swiftshader_indirect', '-port', '5554')
$emu = Start-Process -FilePath "$root\sdk\emulator\emulator.exe" -ArgumentList $emuArgs -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "$logDir\emu-$Api.out.log" -RedirectStandardError "$logDir\emu-$Api.err.log"

$booted = $false
$deadline = (Get-Date).AddMinutes(12)
while ((Get-Date) -lt $deadline) {
    $devs = (& $adb devices 2>&1 | Out-String)
    if ($devs -match 'emulator-5554\s+device') {
        $b = (& $adb -s $serial shell getprop sys.boot_completed 2>&1 | Out-String).Trim()
        if ($b -eq '1') {
            # sys.boot_completed can flip to 1 before system services are usable (seen on the API 37 image).
            $ready = $false
            for ($i = 0; $i -lt 36; $i++) {
                $p = (& $adb -s $serial shell pm path android 2>&1 | Out-String)
                $a = (& $adb -s $serial shell cmd activity get-current-user 2>&1 | Out-String)
                if ($p -match 'package:' -and $a -notmatch "Can't find service|error") { $ready = $true; break }
                Start-Sleep -Seconds 5
            }
            if ($ready) { Start-Sleep -Seconds 10; $booted = $true; break }
        }
    }
    if ($emu.HasExited) { break }
    Start-Sleep -Seconds 5
}
Log "booted=$booted"

if ($booted) {
    Log ("android " + (& $adb -s $serial shell getprop ro.build.version.release) + " api " + (& $adb -s $serial shell getprop ro.build.version.sdk))
    python $runner install --serial $serial --what all 2>&1 | Where-Object { $_ -match 'Fail|Error|error' } | ForEach-Object { Log "install: $_" }
    # The role list must be ONE quoted argument. 'a11y, notif' (with spaces) becomes separate arguments in PowerShell,
    # the runner rejects it, and an earlier version of this script hid that error and ran with nothing enabled.
    python $runner setup --serial $serial --disable 'a11y,notif,admin,ime,perms' 2>&1 | Where-Object { $_ -match 'error|usage|unrecognized|Exception|Traceback' } | ForEach-Object { Log "setup(disable): $_" }
    foreach ($flavor in 'q', 'a') {
        python $runner run --serial $serial --flavor $flavor --label baseline-empty --state "emulator api$Api; no fixtures enabled" 2>&1 | ForEach-Object { Log "baseline/$flavor : $_" }
    }
    python $runner setup --serial $serial --enable 'a11y,notif,admin,ime,perms' 2>&1 | Where-Object { $_ -match 'error|usage|unrecognized|Exception|Traceback' } | ForEach-Object { Log "setup(enable): $_" }
    foreach ($flavor in 'q', 'a') {
        python $runner run --serial $serial --flavor $flavor --label all-enabled-adb --state "emulator api$Api; fixtures a11y,notif,admin,ime,perms enabled via adb" 2>&1 | ForEach-Object { Log "enabled/$flavor : $_" }
    }
}

Log 'stopping emulator'
& $adb -s $serial emu kill 2>&1 | Out-Null
if (-not $emu.WaitForExit(60000)) { Stop-Process -Id $emu.Id -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 3

Log 'removing AVD'
& $avdm delete avd -n $name 2>&1 | Select-Object -Last 1 | ForEach-Object { Log "$_" }
if (-not $KeepImage) {
    Log 'removing image'
    Sdk "--uninstall `"$Image`"" | Select-Object -Last 1 | ForEach-Object { Log "$_" }
}
Log ("done; AndroidLab is now {0:N1} GB" -f ((Get-ChildItem $root -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum / 1GB))
