<#
  Shou — Windows uninstaller. Stops the server, removes the Startup shortcut, and
  optionally deletes your config + virtualenv. Does NOT remove uv / mpv / browsers
  (those are general-purpose tools you may use elsewhere).
      powershell -ExecutionPolicy Bypass -File .\uninstall.ps1
#>
$ErrorActionPreference = 'SilentlyContinue'

function Step($m) { Write-Host "`n▸ $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "   ✓ $m" -ForegroundColor Green }
function Info($m) { Write-Host "   • $m" -ForegroundColor Blue }
function AskYes($q) { $r = Read-Host "   ? $q [Y/n]"; return [string]::IsNullOrWhiteSpace($r) -or $r -match '^[Yy]' }

$RepoDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$AppDir  = Join-Path $RepoDir 'shou'
$ConfDir = Join-Path $HOME '.config\shou'

Write-Host "`n朱 Shou — uninstall`n" -ForegroundColor Magenta

Step 'Stopping the server + player'
# Stop the daemon loop + the python server + our mpv (any running instances).
Get-CimInstance Win32_Process |
    Where-Object { $_.CommandLine -match 'shou_daemon\.ps1' -or $_.CommandLine -match 'shou\\server\.py' -or $_.CommandLine -match 'shou-mpv' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Ok 'Stopped Shou processes (if any were running).'

Step 'Removing autostart shortcut'
$lnk = Join-Path ([Environment]::GetFolderPath('Startup')) 'Shou.lnk'
if (Test-Path $lnk) { Remove-Item $lnk -Force; Ok "Removed $lnk" } else { Info 'No Startup shortcut found.' }

Step 'Config + virtualenv'
if ((Test-Path $ConfDir) -and (AskYes "Delete your config + tokens at $ConfDir ?")) {
    Remove-Item $ConfDir -Recurse -Force; Ok 'Config removed.'
} else { Info "Kept $ConfDir" }

$venv = Join-Path $AppDir '.venv'
if ((Test-Path $venv) -and (AskYes "Delete the Python virtualenv at $venv ?")) {
    Remove-Item $venv -Recurse -Force; Ok 'Virtualenv removed.'
} else { Info "Kept $venv" }

Write-Host "`n✓ Shou uninstalled. The repo folder itself was left in place." -ForegroundColor Green
Write-Host '  (uv, mpv and your browser were left installed.)'
