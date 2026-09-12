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

function Import-MaintenanceHelper([string]$Name) {
  foreach ($candidate in @(
      (Join-Path $PSScriptRoot $Name),
      (Join-Path $PSScriptRoot "windows\service\$Name"))) {
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
      . $candidate
      return
    }
  }
  throw "Maintenance helper is missing: $Name"
}

Import-MaintenanceHelper 'portable-config.ps1'
Import-MaintenanceHelper 'data-acl.ps1'

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
  Set-FabMatrixPortableConfig (Join-Path $staging 'config\application.yml')

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
  Set-FabMatrixDataAcl -DataRoot $dataPath
} catch {
  $restoreError = $_.Exception.Message
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
  try { Set-FabMatrixDataAcl -DataRoot $dataPath } catch { }
  throw "Restore failed; original data rollback was attempted. Retain staging/pre-restore evidence. $restoreError"
} finally {
  if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue }
}

Write-Host "Local Edition restore completed: $dataPath"
Write-Host "Previous config/database/files moved to: $preserved"
Write-Host 'Consistency: FabMatrixServer was stopped; data was fully replaced and the Windows private/read-only ACL policy was reapplied.'
