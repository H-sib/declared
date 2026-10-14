# Starts one emulator and keeps running until it exits (run it as a background task), or stops/cleans it up.
#   Start:  .\tools\emu.ps1 -Action start -Api 34 -Image 'system-images;android-34;google_apis;x86_64'
#   Stop:   .\tools\emu.ps1 -Action stop  -Api 34 -Image '...' [-KeepImage]
param(
    [Parameter(Mandatory = $true)][ValidateSet('start', 'stop')][string]$Action,
    [int]$Api = 34,
    [string]$Image = 'system-images;android-34;google_apis;x86_64',
    [switch]$KeepImage
)
$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'env.ps1') | Out-Null
$root = 'C:\Users\lordhasib\AndroidLab'
$env:ANDROID_AVD_HOME = "$root\avd"
$adb = "$root\sdk\platform-tools\adb.exe"
$sdkm = "$root\sdk\cmdline-tools\latest\bin\sdkmanager.bat"
$avdm = "$root\sdk\cmdline-tools\latest\bin\avdmanager.bat"
$name = "lab$Api"
$serial = 'emulator-5554'
$logDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'results\raw'
$ready = Join-Path $logDir "emu-$Api.ready"
New-Item -ItemType Directory -Force $logDir | Out-Null

function Log($m) { Write-Output ("[emu {0}] {1}" -f $Api, $m) }
function Sdk($argsText) {
    cmd /c "`"$sdkm`" $argsText" 2>&1 | ForEach-Object { "$_" } |
        Where-Object { $_ -notmatch '^\[=*\s*\]' -and $_.Trim() -ne '' -and $_ -notmatch 'deprecated|android-cli|Android CLI|cmdline-tools directory' }
}

if ($Action -eq 'stop') {
    & $adb -s $serial emu kill 2>&1 | Out-Null
    Start-Sleep -Seconds 8
    Remove-Item -LiteralPath $ready -Force -ErrorAction SilentlyContinue
    & $avdm delete avd -n $name 2>&1 | Select-Object -Last 1 | ForEach-Object { Log "$_" }
    if (-not $KeepImage) { Sdk "--uninstall `"$Image`"" | Select-Object -Last 1 | ForEach-Object { Log "$_" } }
    Log 'stopped and cleaned up'
    exit 0
}

Remove-Item -LiteralPath $ready -Force -ErrorAction SilentlyContinue
Log "installing image $Image"
Sdk "--install `"$Image`"" | Select-Object -Last 2 | ForEach-Object { Log "$_" }
if (-not (Test-Path "$root\avd\$name.avd")) {
    'no' | & $avdm create avd -n $name -k $Image -d pixel_6 --force 2>&1 | Out-Null
}
$cfg = "$root\avd\$name.avd\config.ini"
if (Test-Path $cfg) {
    (Get-Content $cfg) -replace '^disk\.dataPartition\.size=.*$', 'disk.dataPartition.size=3G' | Set-Content $cfg -Encoding ASCII
}
Log 'starting emulator'
$emuArgs = @('-avd', $name, '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot', '-gpu', 'swiftshader_indirect', '-port', '5554')
$emu = Start-Process -FilePath "$root\sdk\emulator\emulator.exe" -ArgumentList $emuArgs -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "$logDir\emu-$Api.out.log" -RedirectStandardError "$logDir\emu-$Api.err.log"

$booted = $false
$deadline = (Get-Date).AddMinutes(12)
while ((Get-Date) -lt $deadline -and -not $emu.HasExited) {
    $devs = (& $adb devices 2>&1 | Out-String)
    if ($devs -match 'emulator-5554\s+device') {
        $b = (& $adb -s $serial shell getprop sys.boot_completed 2>&1 | Out-String).Trim()
        if ($b -eq '1') {
            $ok = $false
            for ($i = 0; $i -lt 36; $i++) {
                $p = (& $adb -s $serial shell pm path android 2>&1 | Out-String)
                $a = (& $adb -s $serial shell cmd activity get-current-user 2>&1 | Out-String)
                if ($p -match 'package:' -and $a -notmatch "Can't find service|error") { $ok = $true; break }
                Start-Sleep -Seconds 5
            }
            if ($ok) { Start-Sleep -Seconds 10; $booted = $true; break }
        }
    }
    Start-Sleep -Seconds 5
}
Log "booted=$booted"
if ($booted) { Set-Content -Path $ready -Value (Get-Date).ToString('s') -Encoding ASCII }
$emu.WaitForExit()
Log 'emulator exited'
