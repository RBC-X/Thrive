param(
    [int]$Port = 4000
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Security
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $projectRoot "backend"
$stateDir = Join-Path $env:LOCALAPPDATA "ThriveServer"
$secretPath = Join-Path $stateDir "account-key.dpapi"
$backupSecretPath = Join-Path $stateDir "backup-key.dpapi"
$databasePath = Join-Path $stateDir "thrive-accounts.sqlite"
$backupDir = Join-Path $stateDir "anonymous-backups"

New-Item -ItemType Directory -Force -Path $stateDir, $backupDir | Out-Null

function Get-OrCreateProtectedKey([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        $plainKey = New-Object byte[] 32
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($plainKey) } finally { $rng.Dispose() }
        try {
            $protectedKey = [System.Security.Cryptography.ProtectedData]::Protect(
                $plainKey,
                $null,
                [System.Security.Cryptography.DataProtectionScope]::CurrentUser
            )
            [System.IO.File]::WriteAllBytes($Path, $protectedKey)
        } finally {
            [Array]::Clear($plainKey, 0, $plainKey.Length)
        }
    }

    $protectedBytes = [System.IO.File]::ReadAllBytes($Path)
    return [System.Security.Cryptography.ProtectedData]::Unprotect(
        $protectedBytes,
        $null,
        [System.Security.Cryptography.DataProtectionScope]::CurrentUser
    )
}

$keyBytes = Get-OrCreateProtectedKey $secretPath
$backupKeyBytes = Get-OrCreateProtectedKey $backupSecretPath

try {
    $env:THRIVE_DATA_ENCRYPTION_KEY = [Convert]::ToBase64String($keyBytes)
    $env:THRIVE_BACKUP_ENCRYPTION_KEY = -join ($backupKeyBytes | ForEach-Object { $_.ToString("x2") })
    $env:THRIVE_ACCOUNT_DB = $databasePath
    $env:THRIVE_BACKUP_DIR = $backupDir
    $env:HOST = "127.0.0.1"
    $env:PORT = $Port.ToString()
    Set-Location -LiteralPath $backendDir
    $node = Join-Path $env:ProgramFiles "nodejs\node.exe"
    if (-not (Test-Path -LiteralPath $node)) {
        $node = (Get-Command node.exe -ErrorAction Stop).Source
    }
    & $node server.js
} finally {
    [Array]::Clear($keyBytes, 0, $keyBytes.Length)
    [Array]::Clear($backupKeyBytes, 0, $backupKeyBytes.Length)
    Remove-Item Env:THRIVE_DATA_ENCRYPTION_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:THRIVE_BACKUP_ENCRYPTION_KEY -ErrorAction SilentlyContinue
}
