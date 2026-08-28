param(
    [int]$Port = 4000,
    [int]$CheckEverySeconds = 30
)

$ErrorActionPreference = "Continue"
$projectRoot = Split-Path -Parent $PSScriptRoot
$stateDir = Join-Path $env:LOCALAPPDATA "ThriveServer"
$urlFile = Join-Path $stateDir "public-url.txt"
$pidFile = Join-Path $stateDir "cloudflared.pid"
$tunnelLog = Join-Path $stateDir "cloudflared.log"
$serviceLog = Join-Path $stateDir "watchdog.log"
$cloudflared = (Get-Command cloudflared.exe -ErrorAction Stop).Source
$gh = (Get-Command gh.exe -ErrorAction Stop).Source

New-Item -ItemType Directory -Force -Path $stateDir | Out-Null

function Write-ServiceLog([string]$Message) {
    Add-Content -LiteralPath $serviceLog -Value "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $Message"
}

function Test-Health([string]$BaseUrl, [int]$Timeout = 5) {
    try {
        $result = Invoke-RestMethod -Uri "$BaseUrl/api/v1/health" -TimeoutSec $Timeout
        return [bool]$result.ok
    } catch { return $false }
}

function Start-LocalBackend {
    if (Test-Health "http://127.0.0.1:$Port" 2) { return $true }
    Start-ScheduledTask -TaskName "ThriveBackend" -ErrorAction SilentlyContinue
    foreach ($attempt in 1..20) {
        Start-Sleep -Milliseconds 500
        if (Test-Health "http://127.0.0.1:$Port" 2) { return $true }
    }
    Write-ServiceLog "Local backend did not become healthy."
    return $false
}

function Stop-OwnedTunnel {
    if (-not (Test-Path -LiteralPath $pidFile)) { return }
    $ownedPid = [int](Get-Content -LiteralPath $pidFile -Raw)
    $process = Get-Process -Id $ownedPid -ErrorAction SilentlyContinue
    if ($process -and $process.Path -eq $cloudflared) { Stop-Process -Id $ownedPid -Force }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}

function Publish-Url([string]$Url) {
    $asset = Join-Path $env:TEMP "thrive-sync-url.txt"
    [IO.File]::WriteAllText($asset, "$Url`n")
    Push-Location $projectRoot
    try {
        $remote = (& git remote get-url origin).Trim()
        $repo = ($remote -replace '^.*github\.com[:/]', '') -replace '\.git$', ''
        $tag = (& $gh release list -R $repo --limit 1 --json tagName --jq '.[0].tagName // empty').Trim()
        if ($tag) {
            & $gh release upload $tag $asset --clobber -R $repo | Out-Null
            Write-ServiceLog "Published tunnel URL for $tag."
        }
    } finally { Pop-Location }
}

function Start-Tunnel {
    Stop-OwnedTunnel
    Remove-Item -LiteralPath $tunnelLog -Force -ErrorAction SilentlyContinue
    $process = Start-Process -FilePath $cloudflared -ArgumentList @(
        "tunnel", "--url", "http://127.0.0.1:$Port", "--no-autoupdate", "--logfile", $tunnelLog, "--loglevel", "info"
    ) -WindowStyle Hidden -PassThru
    [IO.File]::WriteAllText($pidFile, $process.Id.ToString())
    foreach ($attempt in 1..40) {
        Start-Sleep -Seconds 1
        if (-not (Test-Path -LiteralPath $tunnelLog)) { continue }
        $match = [regex]::Match((Get-Content -LiteralPath $tunnelLog -Raw), 'https://[a-z0-9-]+\.trycloudflare\.com')
        if ($match.Success -and (Test-Health $match.Value 8)) {
            [IO.File]::WriteAllText($urlFile, "$($match.Value)`n")
            Publish-Url $match.Value
            Write-ServiceLog "Public tunnel healthy."
            return $match.Value
        }
    }
    Write-ServiceLog "Cloudflare tunnel did not become healthy."
    return $null
}

Write-ServiceLog "Windows tunnel watchdog started."
while ($true) {
    if (Start-LocalBackend) {
        $currentUrl = if (Test-Path -LiteralPath $urlFile) { (Get-Content -LiteralPath $urlFile -Raw).Trim() } else { "" }
        if (-not $currentUrl -or -not (Test-Health $currentUrl 6)) {
            Start-Tunnel | Out-Null
        }
    }
    Start-Sleep -Seconds $CheckEverySeconds
}
