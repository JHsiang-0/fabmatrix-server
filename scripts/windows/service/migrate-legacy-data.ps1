param(
    [Parameter(Mandatory = $true)][string]$DataRoot,
    [string]$LegacyDataRoot = 'C:\ProgramData\Farm'
)

$ErrorActionPreference = 'Stop'
$newRoot = [IO.Path]::GetFullPath($DataRoot).TrimEnd('\')
$legacyRoot = [IO.Path]::GetFullPath($LegacyDataRoot).TrimEnd('\')
. (Join-Path $PSScriptRoot 'portable-config.ps1')
if ($newRoot.Equals($legacyRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Legacy and new data roots must be different.'
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
    Write-Output "New data root is already initialized; legacy data was not copied: $newRoot"
    return
}

$legacyDb = Join-Path $legacyRoot 'data\farm.db'
$legacyUploads = Join-Path $legacyRoot 'uploads'
$legacyConfig = Join-Path $legacyRoot 'config'
if (-not (Test-Path -LiteralPath $legacyDb -PathType Leaf) -and
    -not (Test-DirectoryHasContent $legacyUploads) -and
    -not (Test-DirectoryHasContent $legacyConfig)) {
    Write-Output "No legacy Farm data detected: $legacyRoot"
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

Write-Output "Legacy Farm data copied to: $newRoot"
Write-Output "Migration backup retained at: $migrationBackup"
