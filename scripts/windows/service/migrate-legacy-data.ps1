param(
    [Parameter(Mandatory = $true)][string]$DataRoot,
    [string]$LegacyDataRoot = 'C:\ProgramData\Farm',
    [string]$ResultPath = ''
)

$ErrorActionPreference = 'Stop'
$newRoot = [IO.Path]::GetFullPath($DataRoot).TrimEnd('\')
$legacyRoot = [IO.Path]::GetFullPath($LegacyDataRoot).TrimEnd('\')
. (Join-Path $PSScriptRoot 'portable-config.ps1')
if ($newRoot.Equals($legacyRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Legacy and new data roots must be different.'
}

function Write-FabMatrixLegacyMigrationResult {
    param(
        [Parameter(Mandatory = $true)][bool]$Migrated,
        [Parameter(Mandatory = $true)][string]$Reason,
        [string]$BackupPath = ''
    )

    $result = [ordered]@{
        SchemaVersion = 1
        Migrated = $Migrated
        Reason = $Reason
        DataRoot = $newRoot
        LegacyDataRoot = $legacyRoot
        BackupPath = $BackupPath
    }
    $json = $result | ConvertTo-Json -Compress
    if ([string]::IsNullOrWhiteSpace($ResultPath)) {
        Write-Output $json
        return
    }

    $resolvedResultPath = [IO.Path]::GetFullPath($ResultPath)
    $resultDirectory = Split-Path -Parent $resolvedResultPath
    [IO.Directory]::CreateDirectory($resultDirectory) | Out-Null
    $tempPath = Join-Path $resultDirectory ('.fabmatrix-legacy-migration-' + [guid]::NewGuid().ToString('N') + '.tmp')
    $backupPath = Join-Path $resultDirectory ('.fabmatrix-legacy-migration-' + [guid]::NewGuid().ToString('N') + '.bak')
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    try {
        [IO.File]::WriteAllText($tempPath, $json + [Environment]::NewLine, $utf8)
        if (Test-Path -LiteralPath $resolvedResultPath -PathType Leaf) {
            [IO.File]::Replace($tempPath, $resolvedResultPath, $backupPath, $true)
        } else {
            [IO.File]::Move($tempPath, $resolvedResultPath)
        }
    } finally {
        if (Test-Path -LiteralPath $tempPath) { Remove-Item -LiteralPath $tempPath -Force }
        if (Test-Path -LiteralPath $backupPath) { Remove-Item -LiteralPath $backupPath -Force }
    }
}

function Test-DirectoryHasContent([string]$Path) {
    return (Test-Path -LiteralPath $Path -PathType Container) -and
        ($null -ne (Get-ChildItem -LiteralPath $Path -Force -ErrorAction Stop | Select-Object -First 1))
}

$newDb = Join-Path $newRoot 'database\farm.db'
$newConfig = Join-Path $newRoot 'config\application.yml'
$newFiles = Join-Path $newRoot 'files'
if ((Test-Path -LiteralPath $newDb -PathType Leaf) -or
    (Test-Path -LiteralPath $newConfig -PathType Leaf) -or
    (Test-DirectoryHasContent $newFiles)) {
    Write-FabMatrixLegacyMigrationResult -Migrated $false -Reason 'NewDataRootInitialized'
    return
}

$legacyDb = Join-Path $legacyRoot 'data\farm.db'
$legacyUploads = Join-Path $legacyRoot 'uploads'
$legacyConfig = Join-Path $legacyRoot 'config'
if (-not (Test-Path -LiteralPath $legacyDb -PathType Leaf) -and
    -not (Test-DirectoryHasContent $legacyUploads) -and
    -not (Test-DirectoryHasContent $legacyConfig)) {
    Write-FabMatrixLegacyMigrationResult -Migrated $false -Reason 'NoLegacyDataDetected'
    return
}

$stamp = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$migrationBackup = Join-Path $legacyRoot "backups\pre-fabmatrix-migration-$stamp"
$staging = Join-Path (Split-Path -Parent $newRoot) ('.FabMatrix-migration-' + [guid]::NewGuid().ToString('N'))
$moved = New-Object System.Collections.Generic.List[string]

try {
    New-Item -ItemType Directory -Force -Path $migrationBackup | Out-Null
    if (Test-Path -LiteralPath $legacyDb -PathType Leaf) {
        New-Item -ItemType Directory -Force -Path (Join-Path $migrationBackup 'data') | Out-Null
        Copy-Item -LiteralPath $legacyDb -Destination (Join-Path $migrationBackup 'data\farm.db') -Force
    }
    if (Test-Path -LiteralPath $legacyUploads -PathType Container) {
        Copy-Item -LiteralPath $legacyUploads -Destination (Join-Path $migrationBackup 'uploads') -Recurse -Force
    }
    if (Test-Path -LiteralPath $legacyConfig -PathType Container) {
        Copy-Item -LiteralPath $legacyConfig -Destination (Join-Path $migrationBackup 'config') -Recurse -Force
    }
    if ((Test-Path -LiteralPath $legacyDb -PathType Leaf) -and
        -not (Test-Path -LiteralPath (Join-Path $migrationBackup 'data\farm.db') -PathType Leaf)) {
        throw 'Legacy SQLite migration backup verification failed.'
    }

    New-Item -ItemType Directory -Force -Path $staging | Out-Null
    if (Test-Path -LiteralPath $legacyDb -PathType Leaf) {
        New-Item -ItemType Directory -Force -Path (Join-Path $staging 'database') | Out-Null
        Copy-Item -LiteralPath $legacyDb -Destination (Join-Path $staging 'database\farm.db') -Force
    }
    if (Test-Path -LiteralPath $legacyUploads -PathType Container) {
        Copy-Item -LiteralPath $legacyUploads -Destination (Join-Path $staging 'files') -Recurse -Force
    } else {
        New-Item -ItemType Directory -Force -Path (Join-Path $staging 'files') | Out-Null
    }
    if (Test-Path -LiteralPath $legacyConfig -PathType Container) {
        Copy-Item -LiteralPath $legacyConfig -Destination (Join-Path $staging 'config') -Recurse -Force
        Set-FabMatrixPortableConfig (Join-Path $staging 'config\application.yml')
    }

    New-Item -ItemType Directory -Force -Path $newRoot | Out-Null
    foreach ($relative in @('database', 'files', 'config')) {
        $source = Join-Path $staging $relative
        if (-not (Test-Path -LiteralPath $source)) { continue }
        $destination = Join-Path $newRoot $relative
        if (Test-Path -LiteralPath $destination) {
            if (Test-DirectoryHasContent $destination) { throw "Migration target is no longer empty: $destination" }
            Remove-Item -LiteralPath $destination -Force
        }
        Move-Item -LiteralPath $source -Destination $destination
        $moved.Add($relative)
    }
} catch {
    for ($index = $moved.Count - 1; $index -ge 0; $index--) {
        $relative = $moved[$index]
        $destination = Join-Path $newRoot $relative
        $source = Join-Path $staging $relative
        if ((Test-Path -LiteralPath $destination) -and -not (Test-Path -LiteralPath $source)) {
            Move-Item -LiteralPath $destination -Destination $source -ErrorAction SilentlyContinue
        }
    }
    throw "Legacy Farm migration failed; legacy data and migration backup were retained. $($_.Exception.Message)"
} finally {
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue }
}

Write-FabMatrixLegacyMigrationResult -Migrated $true -Reason 'LegacyDataMigrated' -BackupPath $migrationBackup
