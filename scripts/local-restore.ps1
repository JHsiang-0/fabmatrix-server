param(
  [Parameter(Mandatory = $true)][string]$BackupDir,
  [string]$DataDir = "./data",
  [Parameter(Mandatory = $true)][ValidateSet("RESTORE")][string]$Confirm
)

$ErrorActionPreference = "Stop"
$backupDb = Join-Path $BackupDir "farm.db"
if (-not (Test-Path $backupDb -PathType Leaf)) { throw "备份目录中缺少 farm.db: $BackupDir" }
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
$preserved = Join-Path $DataDir "pre-restore-$stamp"
New-Item -ItemType Directory -Force -Path $preserved | Out-Null
$dataDb = Join-Path $DataDir "farm.db"
if (Test-Path $dataDb -PathType Leaf) { Copy-Item $dataDb (Join-Path $preserved "farm.db") }
$dataFiles = Join-Path $DataDir "files"
if (Test-Path $dataFiles -PathType Container) { Move-Item $dataFiles (Join-Path $preserved "files") }
Copy-Item $backupDb $dataDb -Force
$backupFiles = Join-Path $BackupDir "files"
if (Test-Path $backupFiles -PathType Container) { Copy-Item $backupFiles $dataFiles -Recurse -Force }
Write-Host "Local Edition restore completed: $DataDir"
Write-Host "Previous data preserved at: $preserved"
