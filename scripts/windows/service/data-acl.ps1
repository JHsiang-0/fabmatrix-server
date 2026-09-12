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

    $grants = @(
        $system,
        $administrators
    )
    if ($UsersReadOnly) { $grants += '*S-1-5-32-545:(OI)(CI)RX' }
    Invoke-FabMatrixIcacls -Path $Path -Arguments (@('/grant:r') + $grants + @('/T', '/Q'))
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
