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

# winget / scoop edit the *persistent* (registry) PATH, but this running session keeps its
# old copy — so a freshly installed tool looks "missing" until the shell is restarted.
# Rebuild $env:Path from the Machine+User registry values plus the well-known per-user
# tool dirs, so the rest of the installer can see what we just installed.
function Refresh-Path {
    $parts = @(
        [Environment]::GetEnvironmentVariable('Path', 'Machine'),
        [Environment]::GetEnvironmentVariable('Path', 'User'),
        (Join-Path $HOME '.local\bin'),                          # uv (astral installer)
        (Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Links'),  # winget shims
        (Join-Path $HOME 'scoop\shims')                          # scoop shims
    ) | Where-Object { $_ }
    $env:Path = ($parts -join ';')
}

function Is-Admin {
    $p = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    return $p.IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)
}

# Returns $true if the command is on PATH after the attempt.
function Winget-Install($id, $cmd, $label) {
    if (-not $hasWinget) { return $false }
    try { winget install --id $id -e --accept-source-agreements --accept-package-agreements }
    catch { Warn "winget couldn't install $label ($($_.Exception.Message))." }
    Refresh-Path
    return (Have $cmd)
}

# Bootstrap scoop on demand — our admin-free fallback for tools winget can't place (mpv).
# Honours an elevated shell via -RunAsAdmin so it never aborts mid-run.
function Ensure-Scoop {
    if (Have 'scoop') { return $true }
    Info 'Installing scoop (user-scoped package manager, no admin needed)...'
    try {
        $installer = [scriptblock]::Create((Invoke-RestMethod -Uri 'https://get.scoop.sh'))
        if (Is-Admin) { & $installer -RunAsAdmin } else { & $installer }
    } catch { Warn "scoop bootstrap failed: $($_.Exception.Message)"; return $false }
    Refresh-Path
    return (Have 'scoop')
}

# uv (Python env manager) — winget first, official installer as the fallback.
if (Have 'uv') {
    Ok "uv already installed: $((Get-Command uv).Source)"
} else {
    Info 'Installing uv (Python manager)...'
    if (-not (Winget-Install 'astral-sh.uv' 'uv' 'uv')) {
        Info 'Falling back to the official uv installer...'
        try { Invoke-RestMethod -Uri 'https://astral.sh/uv/install.ps1' | Invoke-Expression }
        catch { Warn "uv installer failed: $($_.Exception.Message)" }
        Refresh-Path
    }
    if (Have 'uv') { Ok "uv installed: $((Get-Command uv).Source)" }
    else { Warn 'uv still not on PATH — open a new terminal and re-run install.ps1.' }
}

# mpv (player) — REQUIRED. Installed automatically: there is no reliable winget id for mpv,
# so we bootstrap scoop (+ git + the extras bucket where mpv lives) to guarantee a working
# player in a single installer run.
if (Have 'mpv') {
    Ok "mpv already installed: $((Get-Command mpv).Source)"
} else {
    Info 'mpv not found — installing it automatically (this may bootstrap scoop + git)...'
    if (Ensure-Scoop) {
        # The extras bucket needs git; git ships in scoop's default main bucket.
        if (-not (Have 'git')) { Info 'Installing git (needed for scoop buckets)...'; scoop install git; Refresh-Path }
        try { scoop bucket add extras } catch { }  # no-op if already added
        scoop install mpv
        Refresh-Path
    }
    if (Have 'mpv') {
        Ok "mpv installed: $((Get-Command mpv).Source)"
    } else {
        Warn 'Automatic mpv install failed. Install it from https://mpv.io (or `scoop install mpv`)'
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
Refresh-Path  # pick up uv if winget just placed it but the session PATH was stale
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

# Pin MPV_BIN to the resolved mpv on first run so the login daemon finds it even if the
# session PATH differs (e.g. scoop shims not yet picked up). Blank falls back to PATH.
$mpvBin = if (Have 'mpv') { (Get-Command mpv).Source } else { '' }

# Canonical settings: key | default | comment. Re-running backfills any missing.
$settings = @(
    @('PORT',            '4100',    'Port the server / phone remote listens on.'),
    @('WATCHED_PERCENT', '90',      'Auto-mark an episode watched on AniList once playback passes this %.'),
    @('MPV_BIN',         $mpvBin,   'Full path to mpv.exe (leave blank to use PATH).'),
    @('BROWSER',         '',        'Full path to a kiosk browser .exe (blank = auto-detect Firefox/Edge/Chrome).')
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
Step 'Windows Firewall — let your phone reach the server on the LAN'
# --------------------------------------------------------------------------- #
# The server listens on 0.0.0.0, but Windows Defender Firewall blocks inbound by default,
# so the PC's own kiosk (localhost) works while the phone hangs forever. Open the port for
# Private/Domain networks (home/work Wi-Fi) — NOT Public.
$port = Conf-Get 'PORT'; if ([string]::IsNullOrWhiteSpace($port)) { $port = '4100' }
$fwName = "Shou (TCP $port)"
if (Get-NetFirewallRule -DisplayName $fwName -ErrorAction SilentlyContinue) {
    Ok "Firewall rule already present: $fwName"
} else {
    # New-NetFirewallRule needs admin; elevate just this one command if we aren't already.
    $fwCmd = "New-NetFirewallRule -DisplayName '$fwName' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $port -Profile Private,Domain | Out-Null"
    try {
        if (Is-Admin) {
            Invoke-Expression $fwCmd
        } else {
            Info 'Adding the rule needs admin — accept the UAC prompt...'
            Start-Process powershell -Verb RunAs -Wait -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $fwCmd)
        }
    } catch { Warn "Firewall step failed: $($_.Exception.Message)" }
    if (Get-NetFirewallRule -DisplayName $fwName -ErrorAction SilentlyContinue) {
        Ok "Opened TCP $port for Private/Domain networks."
    } else {
        Warn 'Could not add the firewall rule. Run this in an elevated PowerShell:'
        Warn "  $fwCmd"
        Warn 'Also confirm your Wi-Fi is set to a Private network (not Public).'
    }
}

# --------------------------------------------------------------------------- #
Step 'Start the server now?'
# --------------------------------------------------------------------------- #
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
