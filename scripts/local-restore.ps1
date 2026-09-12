param(
  [Parameter(Mandatory = $true)][string]$BackupDir,
  [Alias('DataDir')][string]$DataRoot = './data',
  [Parameter(Mandatory = $true)][ValidateSet('RESTORE')][string]$Confirm,
  [string]$ServiceName = 'FabMatrixServer'
)

$ErrorActionPreference = 'Stop'
$dataPath = [IO.Path]::GetFullPath($DataRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)
$backupPath = [IO.Path]::GetFullPath($BackupDir)
$volumeRoot = [IO.Path]::GetPathRoot($dataPath).TrimEnd([IO.Path]::DirectorySeparatorChar)
if ($dataPath.Equals($volumeRoot, [StringComparison]::OrdinalIgnoreCase)) {
  throw 'DataRoot must not be a volume root.'
}

$service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($null -ne $service -and $service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
  throw "Stop service $ServiceName before restore. This script does not stop it."
}

$backupDb = Join-Path $backupPath 'database\farm.db'
$backupConfig = Join-Path $backupPath 'config\application.yml'
$backupFiles = Join-Path $backupPath 'files'
foreach ($required in @($backupDb, $backupConfig)) {
  if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required backup file is missing: $required" }
}
if (-not (Test-Path -LiteralPath $backupFiles -PathType Container)) { throw "Required backup directory is missing: $backupFiles" }

function Set-PortableLocalConfig([string]$Path) {
  $content = [IO.File]::ReadAllText($Path)
  $portableRoot = '${FARM_DATA_DIR}'
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

$stamp = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$preserved = Join-Path $dataPath "backups\pre-restore-$stamp"
$staging = Join-Path $dataPath ('.restore-staging-' + [guid]::NewGuid().ToString('N'))
$preservedNames = New-Object System.Collections.Generic.List[string]
$installedNames = New-Object System.Collections.Generic.List[string]

try {
  New-Item -ItemType Directory -Force -Path (Join-Path $staging 'database'), (Join-Path $staging 'config'), (Join-Path $staging 'files') | Out-Null
  Copy-Item -LiteralPath $backupDb -Destination (Join-Path $staging 'database\farm.db') -Force
  Copy-Item -LiteralPath $backupConfig -Destination (Join-Path $staging 'config\application.yml') -Force
  Get-ChildItem -LiteralPath $backupFiles -Force | Copy-Item -Destination (Join-Path $staging 'files') -Recurse -Force
  Set-PortableLocalConfig (Join-Path $staging 'config\application.yml')

  New-Item -ItemType Directory -Force -Path $preserved | Out-Null
  foreach ($relative in @('config', 'database', 'files')) {
    $current = Join-Path $dataPath $relative
    if (Test-Path -LiteralPath $current) {
      Move-Item -LiteralPath $current -Destination (Join-Path $preserved $relative)
      $preservedNames.Add($relative)
    }
  }
  foreach ($relative in @('config', 'database', 'files')) {
    Move-Item -LiteralPath (Join-Path $staging $relative) -Destination (Join-Path $dataPath $relative)
    $installedNames.Add($relative)
  }
} catch {
  for ($index = $installedNames.Count - 1; $index -ge 0; $index--) {
    $relative = $installedNames[$index]
    $installed = Join-Path $dataPath $relative
    if (Test-Path -LiteralPath $installed) { Remove-Item -LiteralPath $installed -Recurse -Force -ErrorAction SilentlyContinue }
  }
  for ($index = $preservedNames.Count - 1; $index -ge 0; $index--) {
    $relative = $preservedNames[$index]
    $saved = Join-Path $preserved $relative
    $current = Join-Path $dataPath $relative
    if ((Test-Path -LiteralPath $saved) -and -not (Test-Path -LiteralPath $current)) {
      Move-Item -LiteralPath $saved -Destination $current -ErrorAction SilentlyContinue
    }
  }
  throw "Restore failed; original data rollback was attempted. Retain staging/pre-restore evidence. $($_.Exception.Message)"
} finally {
  if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue }
}

Write-Host "Local Edition restore completed: $dataPath"
Write-Host "Previous config/database/files moved to: $preserved"
Write-Host 'Consistency: FabMatrixServer was stopped; config/database/files were fully replaced, including removal of stale SQLite WAL/SHM files.'
