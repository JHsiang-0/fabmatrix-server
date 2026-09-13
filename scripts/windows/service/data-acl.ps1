function Invoke-FabMatrixIcacls {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    & icacls.exe $Path @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "ACL update failed ($LASTEXITCODE): $Path $($Arguments -join ' ')"
    }
}

function Set-FabMatrixDirectoryAcl {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [switch]$UsersReadOnly
    )

    New-Item -ItemType Directory -Force -Path $Path | Out-Null
    $system = '*S-1-5-18:(OI)(CI)F'
    $administrators = '*S-1-5-32-544:(OI)(CI)F'

    # Bootstrap only the final privileged principals while inherited access still permits traversal.
    Invoke-FabMatrixIcacls -Path $Path -Arguments @('/grant:r', $system, $administrators, '/T', '/Q')
    Invoke-FabMatrixIcacls -Path $Path -Arguments @('/inheritance:r', '/T', '/Q')

    # Remove broad explicit allow and deny ACEs left by older installers before granting the final policy.
    foreach ($sid in @('*S-1-5-32-545', '*S-1-5-11', '*S-1-1-0')) {
        Invoke-FabMatrixIcacls -Path $Path -Arguments @('/remove:g', $sid, '/T', '/Q')
        Invoke-FabMatrixIcacls -Path $Path -Arguments @('/remove:d', $sid, '/T', '/Q')
    }

    # Existing objects need direct ACEs. An inheritable ACE on a directory is
    # not a reliable write grant for an already-created ordinary file (the
    # WinSW service log failure is one example), so grant every current object
    # explicitly before restoring inheritance for future children.
    $directGrants = @(
        '*S-1-5-18:F',
        '*S-1-5-32-544:F'
    )
    if ($UsersReadOnly) { $directGrants += '*S-1-5-32-545:RX' }
    Invoke-FabMatrixIcacls -Path $Path -Arguments (@('/grant:r') + $directGrants + @('/T', '/Q'))

    # Re-add inheritance only to existing directories. This keeps the direct
    # file ACEs above while preserving the policy for files created later.
    $directories = @((Get-Item -LiteralPath $Path -Force -ErrorAction Stop)) + @(
        Get-ChildItem -LiteralPath $Path -Force -Directory -Recurse -ErrorAction Stop
    )
    $inheritedGrants = @(
        '*S-1-5-18:(OI)(CI)F',
        '*S-1-5-32-544:(OI)(CI)F'
    )
    if ($UsersReadOnly) { $inheritedGrants += '*S-1-5-32-545:(OI)(CI)RX' }
    foreach ($directory in $directories) {
        Invoke-FabMatrixIcacls -Path $directory.FullName -Arguments (@('/grant') + $inheritedGrants + @('/Q'))
    }
}

function Repair-FabMatrixInstallerDataAccess {
    param([Parameter(Mandatory = $true)][string]$DataRoot)

    if (-not (Get-Command takeown.exe -ErrorAction SilentlyContinue) -or
        -not (Get-Command icacls.exe -ErrorAction SilentlyContinue)) {
        throw 'takeown.exe and icacls.exe are required to repair legacy FabMatrix data permissions.'
    }

    $root = [IO.Path]::GetFullPath($DataRoot).TrimEnd('\')
    foreach ($relative in @('config', 'database', 'backups', 'files', 'logs')) {
        $path = Join-Path $root $relative
        if (-not (Test-Path -LiteralPath $path -PathType Container)) { continue }

        # Older installers could leave child files with a DACL that even blocked the
        # elevated upgrade process. Ownership/access is repaired only for the five
        # known data directories; Set-FabMatrixDataAcl applies the final policy later.
        & takeown.exe '/F' $path '/A' '/R' '/D' 'Y' | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Legacy data ownership repair failed ($LASTEXITCODE): $path"
        }

        # An explicit deny ACE wins over an added allow ACE. Reset every known data
        # subtree to its parent inheritance first, then grant the two privileged
        # principals. Do not use /C here: a skipped child must abort the upgrade.
        Invoke-FabMatrixIcacls -Path $path -Arguments @('/reset', '/T', '/Q')
        Invoke-FabMatrixIcacls -Path $path -Arguments @('/grant:r',
            '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F', '/T', '/Q')
    }
}

function Set-FabMatrixDataAcl {
    param([Parameter(Mandatory = $true)][string]$DataRoot)

    if (-not (Get-Command icacls.exe -ErrorAction SilentlyContinue)) {
        throw 'icacls.exe is required to protect FabMatrix data.'
    }

    $root = [IO.Path]::GetFullPath($DataRoot).TrimEnd('\')
    foreach ($relative in @('config', 'database', 'backups')) {
        Set-FabMatrixDirectoryAcl -Path (Join-Path $root $relative)
    }
    foreach ($relative in @('files', 'logs')) {
        Set-FabMatrixDirectoryAcl -Path (Join-Path $root $relative) -UsersReadOnly
    }
}

function Set-FabMatrixRuntimeMetadataAcl {
    param([Parameter(Mandatory = $true)][string]$InstallDir)

    if (-not (Get-Command icacls.exe -ErrorAction SilentlyContinue)) {
        throw 'icacls.exe is required to publish FabMatrix runtime metadata safely.'
    }

    $runtimeMetadataDir = Join-Path ([IO.Path]::GetFullPath($InstallDir)) 'service-state'
    Set-FabMatrixDirectoryAcl -Path $runtimeMetadataDir -UsersReadOnly

    $metadataPath = Join-Path $runtimeMetadataDir 'effective-port.txt'
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
        throw "FabMatrix effective port metadata is missing: $metadataPath"
    }

    # The recursive directory hardening removes inheritance from existing files, so publish
    # an explicit file ACE for ordinary tray users after the directory policy is final.
    Invoke-FabMatrixIcacls -Path $metadataPath -Arguments @('/grant:r',
        '*S-1-5-18:F', '*S-1-5-32-544:F', '*S-1-5-32-545:RX', '/Q')
}
