# remotectl one-shot Windows installer.
#
# Run directly:
#   irm https://raw.githubusercontent.com/tejgokani/remotectl/main/scripts/install.ps1 | iex
#
# Run with a relay (pairs immediately instead of printing instructions):
#   &([scriptblock]::Create((irm https://raw.githubusercontent.com/tejgokani/remotectl/main/scripts/install.ps1))) -Relay wss://your-relay
#
# What it does, every time it's run: downloads the latest release, installs it to the same
# fixed path regardless of version, puts that path on this user's PATH permanently, and either
# reconnects an already-paired laptop or pairs a new one. Safe to re-run to update.

[CmdletBinding()]
param(
    [string]$Relay
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'

if (-not [Environment]::Is64BitOperatingSystem) {
    throw "remotectl's Windows build is 64-bit only; this machine is reported as 32-bit."
}

$installDir = Join-Path $env:LOCALAPPDATA 'Programs\remotectl'
Write-Host "Fetching the latest remotectl release..."
$release = Invoke-RestMethod 'https://api.github.com/repos/tejgokani/remotectl/releases/latest' -Headers @{ 'User-Agent' = 'remotectl-installer' } -UseBasicParsing
$asset = $release.assets | Where-Object { $_.name -like '*-windows-x64.zip' } | Select-Object -First 1
if (-not $asset) { throw "No Windows build found in release $($release.tag_name)" }

$zip = Join-Path $env:TEMP "remotectl-$($release.tag_name).zip"
Write-Host "Downloading $($release.tag_name)..."
Invoke-WebRequest $asset.browser_download_url -OutFile $zip -UseBasicParsing

# Re-running this script to update must not fail because the OLD remotectl.exe is still running
# (as the scheduled task, or in a terminal someone left open) and holding its own file locked.
# Best effort throughout: the task may not exist yet, or nothing may be running — and in that
# ordinary case, both tools print an "ERROR: ... not found" line to stderr and exit non-zero.
# Under $ErrorActionPreference = 'Stop', Windows PowerShell promotes that stderr line into a
# *terminating* error, which would abort this entire script on the most common case of all: a
# first-time install with nothing to stop. `*> $null` redirects the stream but does not stop the
# promotion from happening first, so only try/catch reliably swallows it.
try { & schtasks /end /tn remotectl 2>&1 | Out-Null } catch {}
try { & taskkill /IM remotectl.exe /F 2>&1 | Out-Null } catch {}

$extractTmp = Join-Path $env:TEMP "remotectl-extract-$PID"
if (Test-Path $extractTmp) { Remove-Item $extractTmp -Recurse -Force }
Expand-Archive $zip -DestinationPath $extractTmp -Force

# The zip's top-level folder name carries the version (remotectl-vX.Y.Z-windows-x64), which would
# otherwise change $installDir on every upgrade and break anything that points at the old path
# (a shortcut, a note to a friend, the PATH entry itself). Install to a fixed path instead.
$inner = Get-ChildItem $extractTmp | Select-Object -First 1
if (Test-Path $installDir) { Remove-Item $installDir -Recurse -Force }
New-Item -ItemType Directory -Force $installDir | Out-Null
Move-Item (Join-Path $inner.FullName '*') $installDir -Force
Remove-Item $extractTmp, $zip -Recurse -Force

$exe = Join-Path $installDir 'remotectl.exe'
# The download carries Windows' mark-of-the-web; without this, every run shows a SmartScreen
# prompt the first time (SmartScreen still may warn once, since the binary isn't code-signed).
Unblock-File $exe

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (($userPath -split ';') -notcontains $installDir) {
    [Environment]::SetEnvironmentVariable('Path', "$userPath;$installDir", 'User')
    Write-Host "Added $installDir to your PATH (new terminal windows will see it; this one already does)."
}
if (($env:Path -split ';') -notcontains $installDir) {
    $env:Path += ";$installDir"
}

Write-Host "`nInstalled remotectl $($release.tag_name) to $installDir`n"

# From here on we're just running remotectl.exe itself, which prints its own clear messages and
# is expected to exit non-zero in perfectly normal situations (a `pair` that times out waiting for
# a phone, for instance). Without relaxing this, the same stderr-promotion problem worked around
# above would replace that clean message with a wall of PowerShell error text.
$ErrorActionPreference = 'Continue'

$configPath = Join-Path $env:USERPROFILE '.remotectl\config.json'
if (Test-Path $configPath) {
    Write-Host "Already paired — reconnecting the agent with the latest build..."
    & $exe install
} elseif ($Relay) {
    & $exe pair --relay $Relay
} else {
    Write-Host "Now pair a phone:`n  remotectl pair --relay wss://your-relay`n(open a NEW terminal window first, so it picks up the updated PATH)"
}
