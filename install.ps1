<#
  ┌──────────────────────────────────────────────┐
  │   朱   S H O U   ·   I N S T A L L E R  (Win)  │
  │   your anime, controlled from your phone       │
  └──────────────────────────────────────────────┘

  Phone-controlled AniList "Currently Watching" launcher — Windows installer.
  Idempotent: safe to re-run. Run it from inside the repo:
      powershell -ExecutionPolicy Bypass -File .\install.ps1
#>
$ErrorActionPreference = 'Stop'

# --------------------------------------------------------------------------- #
#  Pretty output
# --------------------------------------------------------------------------- #
$script:STEP = 0
function Step($m) { $script:STEP++; Write-Host "`n▸ [$script:STEP] $m" -ForegroundColor Cyan }
function Info($m) { Write-Host "   • $m" -ForegroundColor Blue }
function Ok($m)   { Write-Host "   ✓ $m" -ForegroundColor Green }
function Warn($m) { Write-Host "   ! $m" -ForegroundColor Yellow }
function Die($m)  { Write-Host "`n✗ $m" -ForegroundColor Red; exit 1 }
function AskYes($q) {
    $r = Read-Host "   ? $q [Y/n]"
    return [string]::IsNullOrWhiteSpace($r) -or $r -match '^[Yy]'
}

Write-Host @'

   ┌──────────────────────────────────────────────┐
   │   朱   S H O U   ·   I N S T A L L E R  (Win)  │
   │   your anime, controlled from your phone       │
   └──────────────────────────────────────────────┘
'@ -ForegroundColor Magenta

# --------------------------------------------------------------------------- #
#  Paths
# --------------------------------------------------------------------------- #
$RepoDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$AppDir    = Join-Path $RepoDir 'shou'
$ConfDir   = Join-Path $HOME '.config\shou'
$ConfFile  = Join-Path $ConfDir 'shou.conf'
$Daemon    = Join-Path $RepoDir 'shou_daemon.ps1'

# --------------------------------------------------------------------------- #
Step 'Pre-flight checks'
# --------------------------------------------------------------------------- #
if (-not (Test-Path (Join-Path $AppDir 'server.py'))) {
    Die "Can't find shou\server.py next to this script. Run it from inside the repo."
}
Ok "Repo at $RepoDir"
$hasWinget = [bool](Get-Command winget -ErrorAction SilentlyContinue)
if (-not $hasWinget) { Warn 'winget not found — dependency auto-install will be skipped (install them yourself).' }

# --------------------------------------------------------------------------- #
Step 'Installing dependencies'
# --------------------------------------------------------------------------- #
function Have($name) { [bool](Get-Command $name -ErrorAction SilentlyContinue) }

function Winget-Install($id, $label) {
    if (-not $hasWinget) { Warn "Install $label manually (winget unavailable)."; return }
    if (AskYes "Install $label with winget?") {
        try { winget install --id $id -e --accept-source-agreements --accept-package-agreements }
        catch { Warn "winget couldn't install $label — install it manually." }
    }
}

# uv (Python env manager)
if (Have 'uv') { Ok 'uv already installed.' }
else { Info 'uv (Python manager) is required.'; Winget-Install 'astral-sh.uv' 'uv' }

# mpv (player). No single canonical winget id across machines — prefer scoop if present.
if (Have 'mpv') {
    Ok 'mpv already installed.'
} elseif (Have 'scoop') {
    if (AskYes 'Install mpv with scoop?') { scoop install mpv }
} else {
    Info 'mpv was not found on PATH.'
    Winget-Install 'mpv' 'mpv'
    if (-not (Have 'mpv')) {
        Warn 'If mpv still isn''t on PATH, install it from https://mpv.io (or `scoop install mpv`)'
        Warn 'and set MPV_BIN in shou.conf to the full path of mpv.exe.'
    }
}

# Browser for the kiosk: Edge ships with Windows, so a kiosk browser almost always exists.
$browser = $null
foreach ($p in @(
    "$env:ProgramFiles\Mozilla Firefox\firefox.exe",
    "${env:ProgramFiles(x86)}\Mozilla Firefox\firefox.exe",
    "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
    "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
    "$env:ProgramFiles\Google\Chrome\Application\chrome.exe")) {
    if (Test-Path $p) { $browser = $p; break }
}
if ($browser) { Ok "Kiosk browser: $browser" }
else { Warn 'No Firefox/Edge/Chrome found — install one for the kiosk display.' }

# --------------------------------------------------------------------------- #
Step 'Installing the Python environment (uv)'
# --------------------------------------------------------------------------- #
if (-not (Have 'uv')) { Die 'uv is required but missing (install it in the previous step).' }
Push-Location $AppDir
try { uv sync } finally { Pop-Location }
Ok 'Python virtualenv synced from uv.lock.'

# --------------------------------------------------------------------------- #
Step 'Configuration'
# --------------------------------------------------------------------------- #
New-Item -ItemType Directory -Force -Path $ConfDir | Out-Null

function Conf-Has($k) { (Test-Path $ConfFile) -and (Select-String -Path $ConfFile -Pattern "^\s*$k=" -Quiet) }
function Conf-Get($k) {
    if (-not (Test-Path $ConfFile)) { return '' }
    $m = Select-String -Path $ConfFile -Pattern "^\s*$k=(.*)$" | Select-Object -First 1
    if (-not $m) { return '' }
    return $m.Matches[0].Groups[1].Value.Trim().Trim('"').Trim("'")
}
function Conf-Set($k, $v) {
    if (Conf-Has $k) {
        (Get-Content $ConfFile) -replace "^\s*$k=.*", "$k=`"$v`"" | Set-Content $ConfFile -Encoding utf8
    } else {
        "$k=`"$v`"" | Add-Content $ConfFile -Encoding utf8
    }
}

if (-not (Test-Path $ConfFile)) {
@'
# Shou configuration  —  %USERPROFILE%\.config\shou\shou.conf
# KEY="value" lines. Re-running install.ps1 adds any new keys without
# overwriting ones you've set. Tokens below are managed automatically:
#   REMOTE_TOKEN  — auto-generated by the server on first launch
#   ANILIST_TOKEN — written by shou_auth.ps1 (optional AniList write access)
'@ | Set-Content $ConfFile -Encoding utf8
    Ok "Created $ConfFile"
}

# ANILIST_USER — prompt only if unset/placeholder.
$curUser = Conf-Get 'ANILIST_USER'
if ([string]::IsNullOrWhiteSpace($curUser) -or $curUser -eq 'CHANGE_ME') {
    $u = Read-Host '   ? Your PUBLIC AniList username (Settings → make list public)'
    if ([string]::IsNullOrWhiteSpace($u)) { $u = 'CHANGE_ME' }
    if (-not (Conf-Has 'ANILIST_USER')) {
        '# Your AniList username — the list MUST be public (Settings → Profile).' | Add-Content $ConfFile -Encoding utf8
    }
    Conf-Set 'ANILIST_USER' $u
    if ($u -eq 'CHANGE_ME') { Warn 'ANILIST_USER left as a placeholder — set it later.' }
} else {
    Ok "AniList user: $curUser"
}

# Canonical settings: key | default | comment. Re-running backfills any missing.
$settings = @(
    @('PORT',            '4100', 'Port the server / phone remote listens on.'),
    @('WATCHED_PERCENT', '90',   'Auto-mark an episode watched on AniList once playback passes this %.'),
    @('MPV_BIN',         '',     'Full path to mpv.exe (leave blank to use PATH).'),
    @('BROWSER',         '',     'Full path to a kiosk browser .exe (blank = auto-detect Firefox/Edge/Chrome).')
)
$added = @()
foreach ($s in $settings) {
    $k, $def, $cmt = $s
    if (Conf-Has $k) { Info "$k = $(Conf-Get $k)" }
    else { "# $cmt" | Add-Content $ConfFile -Encoding utf8; Conf-Set $k $def; $added += "$k=$def" }
}
if ($added.Count -gt 0) { Ok "Added missing setting(s): $($added -join ', ')" } else { Ok 'All settings present.' }

# --------------------------------------------------------------------------- #
Step 'Autostart on login'
# --------------------------------------------------------------------------- #
$startup  = [Environment]::GetFolderPath('Startup')
$lnkPath  = Join-Path $startup 'Shou.lnk'
if (Test-Path $lnkPath) {
    Ok "Autostart shortcut already present in Startup."
} elseif (AskYes 'Add a Startup shortcut so Shou launches at login?') {
    $ps = (Get-Command powershell).Source
    $wsh = New-Object -ComObject WScript.Shell
    $lnk = $wsh.CreateShortcut($lnkPath)
    $lnk.TargetPath = $ps
    $lnk.Arguments  = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$Daemon`""
    $lnk.WorkingDirectory = $RepoDir
    $lnk.Description = 'Shou — phone-controlled AniList launcher'
    $lnk.Save()
    Ok "Added $lnkPath"
}

# --------------------------------------------------------------------------- #
Step 'AniList write access — auto-mark episodes watched (optional)'
# --------------------------------------------------------------------------- #
if (Conf-Has 'ANILIST_TOKEN') {
    Ok 'AniList token already configured.'
} else {
    Info 'Lets Shou tick episodes off on AniList automatically once you finish them.'
    Info 'Needs a one-time AniList API client + approval (write access).'
    if (AskYes 'Set this up now?') {
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $RepoDir 'shou_auth.ps1')
    } else {
        Warn 'Skipped — run .\shou_auth.ps1 later to enable it.'
    }
}

# --------------------------------------------------------------------------- #
Step 'Start the server now?'
# --------------------------------------------------------------------------- #
$port = Conf-Get 'PORT'; if ([string]::IsNullOrWhiteSpace($port)) { $port = '4100' }
function Server-Up { try { Invoke-WebRequest "http://127.0.0.1:$port/" -UseBasicParsing -TimeoutSec 2 | Out-Null; $true } catch { $false } }

if (Server-Up) {
    Ok "Server already answering on :$port."
} elseif (AskYes 'Launch the Shou daemon now?') {
    Start-Process powershell -ArgumentList @(
        '-NoProfile', '-WindowStyle', 'Hidden', '-ExecutionPolicy', 'Bypass', '-File', "`"$Daemon`""
    )
    for ($i = 0; $i -lt 20; $i++) { if (Server-Up) { break }; Start-Sleep -Milliseconds 500 }
    if (Server-Up) { Ok 'Server is up.' } else { Warn "Server didn't answer yet — check $ConfDir\shou.log" }
}

# --------------------------------------------------------------------------- #
#  Done — next steps
# --------------------------------------------------------------------------- #
$token = Conf-Get 'REMOTE_TOKEN'
$ip = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
       Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
       Select-Object -First 1).IPAddress
if (-not $ip) { $ip = '<this-pc-ip>' }

Write-Host "`n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Green
Write-Host "  Shou is installed. 🎌" -ForegroundColor Green
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`n" -ForegroundColor Green

Write-Host 'Phone web-remote:'
if ($token) {
    Write-Host "   http://${ip}:${port}/remote?k=$token" -ForegroundColor Cyan
    Info "Open it on your phone, then 'Add to Home screen' for a one-tap icon."
} else {
    Info 'Start the server once; it generates a token and prints the full /remote URL'
    Info "to $ConfDir\shou.log. Then add that URL to your phone's home screen."
}
if ((Conf-Get 'ANILIST_USER') -eq 'CHANGE_ME') {
    Write-Host "`n⚠ Remember to set ANILIST_USER in $ConfFile" -ForegroundColor Yellow
}
Write-Host ''
