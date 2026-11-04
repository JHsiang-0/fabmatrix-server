$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$targetRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'target'))
$root = Join-Path $targetRoot ('maintenance-test-' + [guid]::NewGuid().ToString('N'))
if (-not ([IO.Path]::GetFullPath($root).StartsWith($targetRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase))) {
  throw 'Test directory is outside target.'
}
try {
  $tools = Join-Path $root 'tools'
  $source = Join-Path $root 'source'
  $destination = Join-Path $root 'destination'
  New-Item -ItemType Directory -Force -Path $tools, (Join-Path $source 'database'), (Join-Path $source 'config'), (Join-Path $source 'files'), $destination | Out-Null
  Copy-Item -LiteralPath (Join-Path $projectRoot 'scripts\local-backup.ps1'), (Join-Path $projectRoot 'scripts\local-restore.ps1'), (Join-Path $PSScriptRoot 'service\portable-config.ps1') -Destination $tools
  # Exercise helper scope without changing ACLs on the test runner's directory.
  Set-Content -LiteralPath (Join-Path $tools 'data-acl.ps1') -Value 'function Set-FabMatrixDataAcl { param([string]$DataRoot) Set-Content -LiteralPath (Join-Path $DataRoot "acl-called.txt") -Value "yes" }' -Encoding UTF8
  Set-Content -LiteralPath (Join-Path $source 'database\farm.db') -Value 'fixture database' -Encoding UTF8
  Set-Content -LiteralPath (Join-Path $source 'files\sample.txt') -Value 'fixture file' -Encoding UTF8
  @'
spring:
  datasource:
    url: 'jdbc:sqlite:C:/fixture/database/farm.db'
farm:
  local:
    storage-path: 'C:/fixture/files'
logging:
  file:
    name: 'C:/fixture/logs/farm.log'
'@ | Set-Content -LiteralPath (Join-Path $source 'config\application.yml') -Encoding UTF8
  & (Join-Path $tools 'local-backup.ps1') -DataRoot $source -ServiceName 'FabMatrixMaintenanceTestNoService'
  $backups = @(Get-ChildItem -LiteralPath (Join-Path $source 'backups') -Directory)
  if ($backups.Count -ne 1) { throw 'Expected exactly one fixture backup.' }
  & (Join-Path $tools 'local-restore.ps1') -BackupDir $backups[0].FullName -DataRoot $destination -Confirm RESTORE -ServiceName 'FabMatrixMaintenanceTestNoService'
  if (-not (Test-Path -LiteralPath (Join-Path $destination 'acl-called.txt'))) { throw 'Restore did not invoke the ACL helper.' }
  if ((Get-Content -Raw (Join-Path $destination 'database\farm.db')).Trim() -ne 'fixture database') { throw 'Database fixture was not restored.' }
  if ((Get-Content -Raw (Join-Path $destination 'files\sample.txt')).Trim() -ne 'fixture file') { throw 'File fixture was not restored.' }
  if (-not (Get-Content -Raw (Join-Path $destination 'config\application.yml')).Contains('jdbc:sqlite:${FARM_DATA_DIR}/database/farm.db')) { throw 'Configuration was not normalized.' }
  Write-Host 'Maintenance backup and restore helper scope: PASS'
} finally {
  if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
