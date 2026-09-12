param(
  [Alias('DataDir')][string]$DataRoot = './data',
  [Alias('BackupDir')][string]$BackupRoot = '',
  [string]$ServiceName = 'FabMatrixServer'
)

$ErrorActionPreference = 'Stop'
$dataPath = [IO.Path]::GetFullPath($DataRoot)
$backupPath = if ([string]::IsNullOrWhiteSpace($BackupRoot)) { Join-Path $dataPath 'backups' } else { [IO.Path]::GetFullPath($BackupRoot) }

$service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($null -ne $service -and $service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
  throw "为保证 SQLite 一致性，请先停止服务 $ServiceName，再执行备份。脚本不会自动停止服务。"
}
$database = Join-Path $dataPath 'database\farm.db'
$files = Join-Path $dataPath 'files'
$config = Join-Path $dataPath 'config\application.yml'
foreach ($required in @($database, $config)) {
  if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "统一数据根缺少必需文件: $required" }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$target = Join-Path $backupPath "farm-local-$stamp"
New-Item -ItemType Directory -Force -Path $target, (Join-Path $target 'database'), (Join-Path $target 'config') | Out-Null
Copy-Item -LiteralPath $database -Destination (Join-Path $target 'database\farm.db') -Force
Copy-Item -LiteralPath $config -Destination (Join-Path $target 'config\application.yml') -Force
if (Test-Path -LiteralPath $files -PathType Container) {
  Copy-Item -LiteralPath $files -Destination (Join-Path $target 'files') -Recurse -Force
} else {
  New-Item -ItemType Directory -Force -Path (Join-Path $target 'files') | Out-Null
}
Write-Host "Local Edition backup created: $target"
Write-Host 'Consistency: FabMatrixServer was required to be stopped; database, files and generated config were copied.'
