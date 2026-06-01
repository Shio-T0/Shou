<#
  Shou daemon (Windows): keeps the server running (restart-on-crash).
  Launched at login via a Startup-folder shortcut or Task Scheduler so it can
  spawn the browser kiosk + mpv in the user's desktop session.
#>
$ErrorActionPreference = 'SilentlyContinue'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$AppDir    = Join-Path $ScriptDir 'shou'
$ConfDir   = Join-Path $HOME '.config\shou'
$Log       = Join-Path $ConfDir 'shou.log'
New-Item -ItemType Directory -Force -Path $ConfDir | Out-Null

# Read PORT from shou.conf (KEY="value" lines); default 4100.
$Port = 4100
$Conf = Join-Path $ConfDir 'shou.conf'
if (Test-Path $Conf) {
    foreach ($line in Get-Content $Conf) {
        if ($line -match '^\s*PORT\s*=\s*"?(\d+)"?') { $Port = [int]$Matches[1] }
    }
}

# Don't start a second daemon if the server is already answering.
try {
    Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -UseBasicParsing -TimeoutSec 3 | Out-Null
    "[{0}] Shou already running on :{1}; daemon exiting" -f (Get-Date), $Port | Add-Content $Log
    exit 0
} catch { }

while ($true) {
    "[{0}] starting Shou server" -f (Get-Date) | Add-Content $Log
    & uv run --project "$AppDir" python "$AppDir\server.py" *>> $Log
    "[{0}] server exited (code {1}), restarting in 2s" -f (Get-Date), $LASTEXITCODE | Add-Content $Log
    Start-Sleep -Seconds 2
}
