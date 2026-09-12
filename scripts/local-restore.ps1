param(
  [Parameter(Mandatory = $true)][string]$BackupDir,
  [Alias('DataDir')][string]$DataRoot = './data',
  [Parameter(Mandatory = $true)][ValidateSet('RESTORE')][string]$Confirm,
  [string]$ServiceName = 'FabMatrixServer'
)

$ErrorActionPreference = 'Stop'
$dataPath = [IO.Path]::GetFullPath($DataRoot)
$backupPath = [IO.Path]::GetFullPath($BackupDir)
$service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($null -ne $service -and $service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
  throw "为避免覆盖正在使用的 SQLite，请先停止服务 $ServiceName，再执行恢复。脚本不会自动停止服务。"
}

$backupDb = Join-Path $backupPath 'database\farm.db'
$backupConfig = Join-Path $backupPath 'config\application.yml'
$backupFiles = Join-Path $backupPath 'files'
foreach ($required in @($backupDb, $backupConfig)) {
  if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "备份目录中缺少必需文件: $required" }
}
if (-not (Test-Path -LiteralPath $backupFiles -PathType Container)) { throw "备份目录中缺少必需目录: $backupFiles" }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$preserved = Join-Path $dataPath "backups\pre-restore-$stamp"
New-Item -ItemType Directory -Force -Path $preserved | Out-Null
foreach ($relative in @('database', 'config', 'files')) {
  $current = Join-Path $dataPath $relative
  if (Test-Path -LiteralPath $current) {
    Copy-Item -LiteralPath $current -Destination (Join-Path $preserved $relative) -Recurse -Force
  }
}

New-Item -ItemType Directory -Force -Path (Join-Path $dataPath 'database'), (Join-Path $dataPath 'config'), (Join-Path $dataPath 'files') | Out-Null
Copy-Item -LiteralPath $backupDb -Destination (Join-Path $dataPath 'database\farm.db') -Force
Copy-Item -LiteralPath $backupConfig -Destination (Join-Path $dataPath 'config\application.yml') -Force
Get-ChildItem -LiteralPath $backupFiles -Force | Copy-Item -Destination (Join-Path $dataPath 'files') -Recurse -Force
Write-Host "Local Edition restore completed: $dataPath"
Write-Host "Previous config/database/files preserved at: $preserved"
Write-Host 'Consistency: FabMatrixServer was required to be stopped; stale files are retained rather than deleted.'
