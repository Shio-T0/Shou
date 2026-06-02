<#
  Shou — AniList write access setup (one-time, Windows).
  Obtains an OAuth access token via AniList's authorization-code "PIN" flow and
  stores it as ANILIST_TOKEN in ~/.config/shou/shou.conf, enabling Shou to mark
  episodes watched automatically. Re-run anytime the token expires (~1 year).
#>
$ErrorActionPreference = 'Stop'

$ConfDir  = Join-Path $HOME '.config\shou'
$Conf     = Join-Path $ConfDir 'shou.conf'
$Redirect = 'https://anilist.co/api/v2/oauth/pin'
New-Item -ItemType Directory -Force -Path $ConfDir | Out-Null

Write-Host "`n🎌 AniList write access — Shou`n" -ForegroundColor Cyan
Write-Host "1. Open https://anilist.co/settings/developer and create a client:"
Write-Host "      Name: Shou   ·   Redirect URL: $Redirect`n"

$cid = Read-Host '   AniList Client ID'
$csecretSecure = Read-Host '   AniList Client Secret' -AsSecureString
$csecret = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($csecretSecure))
if ([string]::IsNullOrWhiteSpace($cid) -or [string]::IsNullOrWhiteSpace($csecret)) {
    Write-Host '✗ Client ID and Secret are required.' -ForegroundColor Yellow; exit 1
}

$authUrl = "https://anilist.co/api/v2/oauth/authorize?client_id=$cid&redirect_uri=$Redirect&response_type=code"
Write-Host "`n2. Opening this URL — log in, click Authorise, then copy the code it shows:"
Write-Host "   $authUrl" -ForegroundColor Cyan
try { Start-Process $authUrl } catch { }

$code = Read-Host "`n   Paste the code (PIN)"
if ([string]::IsNullOrWhiteSpace($code)) {
    Write-Host '✗ No code entered.' -ForegroundColor Yellow; exit 1
}

$body = @{
    grant_type    = 'authorization_code'
    client_id     = $cid
    client_secret = $csecret
    redirect_uri  = $Redirect
    code          = $code
} | ConvertTo-Json

try {
    $resp = Invoke-RestMethod -Method Post -Uri 'https://anilist.co/api/v2/oauth/token' `
        -ContentType 'application/json' -Body $body
    $token = $resp.access_token
} catch {
    Write-Host "`n✗ Could not get a token. AniList replied:`n$($_.Exception.Message)" -ForegroundColor Yellow
    exit 1
}

if ([string]::IsNullOrWhiteSpace($token)) {
    Write-Host "`n✗ AniList returned no access_token." -ForegroundColor Yellow; exit 1
}

# Replace an existing ANILIST_TOKEN line, or append one.
$line = "ANILIST_TOKEN=`"$token`""
if ((Test-Path $Conf) -and (Select-String -Path $Conf -Pattern '^ANILIST_TOKEN=' -Quiet)) {
    (Get-Content $Conf) -replace '^ANILIST_TOKEN=.*', $line | Set-Content $Conf -Encoding utf8
} else {
    "`n# AniList OAuth token (write access — auto-mark episodes watched)." | Add-Content $Conf -Encoding utf8
    $line | Add-Content $Conf -Encoding utf8
}

Write-Host "`n✓ Saved ANILIST_TOKEN to $Conf" -ForegroundColor Green
Write-Host '  Restart the daemon to enable auto-progress (close it, then run shou_daemon.ps1).'
