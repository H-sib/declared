# Runs the emulator tripwire experiment (K6, K7) on several Android versions, one image at a time, and deletes each
# AVD and image afterwards so disk use stays bounded. Emulator only.
#   .\tools\tripwire_sweep.ps1 -Apis 29,31,33,35,36,37
param([int[]]$Apis = @(29, 31, 33, 35, 36, 37))
$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'env.ps1') | Out-Null
$lab = Split-Path -Parent $PSScriptRoot
$root = 'C:\Users\lordhasib\AndroidLab'
$adb = "$root\sdk\platform-tools\adb.exe"
$logDir = Join-Path $lab 'results\raw'
New-Item -ItemType Directory -Force $logDir | Out-Null

foreach ($api in $Apis) {
    $image = if ($api -eq 37) { 'system-images;android-37.0;google_apis;x86_64' } else { "system-images;android-$api;google_apis;x86_64" }
    $ready = Join-Path $logDir "emu-$api.ready"
    Remove-Item -LiteralPath $ready -Force -ErrorAction SilentlyContinue
    Write-Output ("=== API {0}: {1} ===" -f $api, (Get-Date).ToString('HH:mm:ss'))

    $emuScript = Join-Path $PSScriptRoot 'emu.ps1'
    $startArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$emuScript`"", '-Action', 'start', '-Api', $api, '-Image', $image)
    $p = Start-Process -FilePath 'powershell.exe' -ArgumentList $startArgs -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logDir "emu-start-$api.log") -RedirectStandardError (Join-Path $logDir "emu-start-$api.err.log")

    $deadline = (Get-Date).AddMinutes(25)
    while ((Get-Date) -lt $deadline -and -not (Test-Path $ready) -and -not $p.HasExited) { Start-Sleep -Seconds 10 }

    if (Test-Path $ready) {
        Write-Output "[$api] emulator ready; installing probe"
        python (Join-Path $PSScriptRoot 'runner.py') install --serial emulator-5554 --what probe 2>&1 | Where-Object { $_ -match 'Fail|Error|error' } | ForEach-Object { Write-Output "[$api] install: $_" }
        python -u (Join-Path $PSScriptRoot 'tripwire_emulator.py') --serial emulator-5554 --pin 1234 --api $api 2>&1 | ForEach-Object { Write-Output "[$api] $_" }
        Write-Output "[$api] experiment exit code: $LASTEXITCODE"
    } else {
        Write-Output "[$api] emulator did not become ready; skipping"
    }

    & (Join-Path $PSScriptRoot 'emu.ps1') -Action stop -Api $api -Image $image 2>&1 | ForEach-Object { Write-Output "[$api] $_" }
    if (-not $p.HasExited) { $null = $p.WaitForExit(60000) }
    if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    Write-Output ("[{0}] done at {1}; AndroidLab is now {2:N1} GB" -f $api, (Get-Date).ToString('HH:mm:ss'), ((Get-ChildItem $root -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum / 1GB))
}
Write-Output 'TRIPWIRE SWEEP COMPLETE'
