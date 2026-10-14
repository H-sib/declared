# Runs sweep.ps1 for every API level, one after another, keeping disk use bounded (one image at a time).
# API 34 keeps its image because it matches the S21 FE and is reused. A plain list is used on purpose:
# integer lookups on an ordered dictionary are positional in PowerShell.
$targets = @(
    @{ Api = 34; Image = 'system-images;android-34;google_apis;x86_64'; Keep = $true },
    @{ Api = 29; Image = 'system-images;android-29;google_apis;x86_64'; Keep = $false },
    @{ Api = 31; Image = 'system-images;android-31;google_apis;x86_64'; Keep = $false },
    @{ Api = 33; Image = 'system-images;android-33;google_apis;x86_64'; Keep = $false },
    @{ Api = 35; Image = 'system-images;android-35;google_apis;x86_64'; Keep = $false },
    @{ Api = 36; Image = 'system-images;android-36;google_apis;x86_64'; Keep = $false },
    @{ Api = 37; Image = 'system-images;android-37.0;google_apis;x86_64'; Keep = $false }
)
foreach ($t in $targets) {
    if ($t.Keep) {
        & (Join-Path $PSScriptRoot 'sweep.ps1') -Api $t.Api -Image $t.Image -KeepImage
    } else {
        & (Join-Path $PSScriptRoot 'sweep.ps1') -Api $t.Api -Image $t.Image
    }
}
Write-Output 'ALL SWEEPS COMPLETE'
