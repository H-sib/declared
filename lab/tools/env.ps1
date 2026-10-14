# Dot-source this in a PowerShell session: . .\tools\env.ps1
# Sets session-only variables for the lab toolchain installed under C:\Users\lordhasib\AndroidLab.
# Nothing is written to the system or user environment.
$root = 'C:\Users\lordhasib\AndroidLab'
$jdk = (Get-ChildItem "$root\jdk" -Directory | Select-Object -First 1).FullName
$gradle = (Get-ChildItem "$root\gradle" -Directory | Select-Object -First 1).FullName

$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = "$root\sdk"
$env:ANDROID_SDK_ROOT = "$root\sdk"
$env:GRADLE_USER_HOME = "$root\gradle-home"
$env:ADB = "$root\sdk\platform-tools\adb.exe"

# JDK 17.0.20 creates its NIO selector pipe from a Windows Unix-domain socket under TEMP. Windows rejects the
# 8.3 short path form (C:\Users\LORDHA~1\...) for that, which makes Gradle fail with
# "Unable to establish loopback connection". A normal long path avoids it.
New-Item -ItemType Directory -Force "$root\tmp" | Out-Null
$env:TEMP = "$root\tmp"
$env:TMP = "$root\tmp"
$env:Path = "$jdk\bin;$gradle\bin;$root\sdk\platform-tools;$root\sdk\cmdline-tools\latest\bin;$root\sdk\emulator;" + $env:Path

# Gradle reads sdk.dir from local.properties (ignored by .gitignore).
$lab = Split-Path -Parent $PSScriptRoot
$sdkDir = ("$root\sdk").Replace('\', '\\').Replace(':', '\:')
Set-Content -Path (Join-Path $lab 'local.properties') -Value "sdk.dir=$sdkDir" -Encoding ASCII

Write-Output "JAVA_HOME=$env:JAVA_HOME"
Write-Output "ANDROID_HOME=$env:ANDROID_HOME"
Write-Output "GRADLE_USER_HOME=$env:GRADLE_USER_HOME"
