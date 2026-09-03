param(
  [string]$DataDir = "./data",
  [string]$BackupDir = "./backups"
)

$ErrorActionPreference = "Stop"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$target = Join-Path $BackupDir "farm-local-$stamp"
New-Item -ItemType Directory -Force -Path $target | Out-Null

Copy-Item -Path (Join-Path $DataDir "farm.db") -Destination $target -ErrorAction Stop
if (Test-Path (Join-Path $DataDir "files")) {
  Copy-Item -Recurse -Path (Join-Path $DataDir "files") -Destination $target
}
Write-Host "Local Edition backup created: $target"
