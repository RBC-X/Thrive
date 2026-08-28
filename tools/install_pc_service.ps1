param(
    [switch]$StartNow
)

$ErrorActionPreference = "Stop"
$launcher = Join-Path $PSScriptRoot "start_backend_secure.ps1"
$tunnelWatchdog = Join-Path $PSScriptRoot "thrive_tunnel_watchdog.ps1"

$backendAction = New-ScheduledTaskAction -Execute "powershell.exe" -Argument (
    "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$launcher`""
)
$backendTrigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -RestartCount 10 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)
Register-ScheduledTask -TaskName "ThriveBackend" -Action $backendAction -Trigger $backendTrigger -Settings $settings -Description "Encrypted local Thrive API" -Force | Out-Null

# The tunnel watchdog republishes a fresh quick-tunnel URL to the latest GitHub
# release whenever Wi-Fi or the public IP changes. It is intentionally separate
# from the database process so either side can restart independently.
$tunnelAction = New-ScheduledTaskAction -Execute "powershell.exe" -Argument (
    "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$tunnelWatchdog`""
)
Register-ScheduledTask -TaskName "ThriveTunnel" -Action $tunnelAction -Trigger $backendTrigger -Settings $settings -Description "Thrive HTTPS tunnel watchdog" -Force | Out-Null

if ($StartNow) {
    Start-ScheduledTask -TaskName "ThriveBackend"
    if (Get-ScheduledTask -TaskName "ThriveTunnel" -ErrorAction SilentlyContinue) {
        Start-ScheduledTask -TaskName "ThriveTunnel"
    }
}

Write-Output "ThriveBackend installed. TunnelInstalled=True"
