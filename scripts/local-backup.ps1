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
  throw "Stop service $ServiceName before backup to keep SQLite consistent. This script does not stop it."
}
$database = Join-Path $dataPath 'database\farm.db'
$files = Join-Path $dataPath 'files'
$config = Join-Path $dataPath 'config\application.yml'
foreach ($required in @($database, $config)) {
  if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required data file is missing: $required" }
}

function Set-PortableDataRoot([string]$Path, [string]$SourceRoot) {
  $content = [IO.File]::ReadAllText($Path)
  $portableRoot = '${FARM_DATA_DIR}'
  $windowsRoot = $SourceRoot.TrimEnd('\')
  $forwardRoot = ($windowsRoot -replace '\\', '/')
  $content = $content.Replace($windowsRoot, $portableRoot).Replace($forwardRoot, $portableRoot)
  $content = [regex]::Replace($content, '(?im)^(\s*url:\s*).*jdbc:sqlite:.*$', {
      param($match) $match.Groups[1].Value + "'jdbc:sqlite:$portableRoot/database/farm.db'"
    })
  $content = [regex]::Replace($content, '(?im)^(\s*storage-path:\s*).+$', {
      param($match) $match.Groups[1].Value + "'$portableRoot/files'"
    })
  $content = [regex]::Replace($content, '(?im)^(\s*name:\s*).*[\\/]logs[\\/]farm\.log[''\"]?\s*$', {
      param($match) $match.Groups[1].Value + "'$portableRoot/logs/farm.log'"
    })
  $utf8 = New-Object System.Text.UTF8Encoding($false)
  [IO.File]::WriteAllText($Path, $content, $utf8)
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$target = Join-Path $backupPath "farm-local-$stamp"
New-Item -ItemType Directory -Force -Path $target, (Join-Path $target 'database'), (Join-Path $target 'config') | Out-Null
Copy-Item -LiteralPath $database -Destination (Join-Path $target 'database\farm.db') -Force
Copy-Item -LiteralPath $config -Destination (Join-Path $target 'config\application.yml') -Force
Set-PortableDataRoot (Join-Path $target 'config\application.yml') $dataPath
if (Test-Path -LiteralPath $files -PathType Container) {
  Copy-Item -LiteralPath $files -Destination (Join-Path $target 'files') -Recurse -Force
} else {
  New-Item -ItemType Directory -Force -Path (Join-Path $target 'files') | Out-Null
}
Write-Host "Local Edition backup created: $target"
Write-Host 'Consistency: FabMatrixServer was required to be stopped; database, files and generated config were copied.'
