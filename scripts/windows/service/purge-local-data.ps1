param(
    [string]$InstallDir = '',
    [string]$DataRoot = '',
    [switch]$SelfTest,
    [string]$TestRoot = ''
)

$ErrorActionPreference = 'Stop'

function ConvertTo-FabMatrixPurgeAbsolutePath([string]$Path, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw "$Description must be a non-empty absolute path." }
    if ($Path.IndexOfAny([char[]]'*?[]') -ge 0) { throw "$Description must not contain wildcard characters: $Path" }
    $normalizedInput = $Path.Replace('/', '\')
    if ($normalizedInput -notmatch '^[A-Za-z]:\\') {
        throw "$Description must be an ordinary local-drive absolute path: $Path"
    }
    try {
        $resolved = [IO.Path]::GetFullPath($normalizedInput)
    } catch {
        throw "Unable to resolve $Description`: $Path"
    }
    $resolved = $resolved.TrimEnd('\')
    $root = [IO.Path]::GetPathRoot($resolved)
    if ($resolved -notmatch '^[A-Za-z]:\\' -or
        [string]::IsNullOrWhiteSpace($root) -or
        $root -notmatch '^[A-Za-z]:\\$') {
        throw "$Description is not an ordinary local-drive path: $resolved"
    }
    if ([string]::Equals($resolved, $root.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description must not be a drive root: $resolved"
    }
    return $resolved
}

function Test-FabMatrixPathWithin([string]$Path, [string]$ParentPath) {
    $resolvedPath = (ConvertTo-FabMatrixPurgeAbsolutePath $Path 'Path').TrimEnd('\')
    $resolvedParent = (ConvertTo-FabMatrixPurgeAbsolutePath $ParentPath 'Parent path').TrimEnd('\')
    return $resolvedPath.StartsWith($resolvedParent + '\', [StringComparison]::OrdinalIgnoreCase)
}

function Assert-FabMatrixNoReparsePointsInPath([string]$Path, [string]$Description) {
    $resolved = ConvertTo-FabMatrixPurgeAbsolutePath $Path $Description
    $root = [IO.Path]::GetPathRoot($resolved)
    $current = $root.TrimEnd('\')
    $relative = $resolved.Substring($root.Length)
    $components = @($relative -split '[\\/]' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    foreach ($component in $components) {
        $current = [IO.Path]::Combine($current, $component)
        if (-not (Test-Path -LiteralPath $current)) { break }
        $item = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if (-not [string]::Equals($component, $item.Name, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing $Description because it contains a non-canonical path component: $current"
        }
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Refusing $Description because it contains a reparse point: $current"
        }
    }
    return $resolved
}

function Assert-FabMatrixNoReparsePointsInTree([string]$Path, [string]$Description) {
    $resolved = Assert-FabMatrixNoReparsePointsInPath $Path $Description
    if (-not (Test-Path -LiteralPath $resolved -PathType Container)) { return $resolved }
    foreach ($item in @(Get-ChildItem -LiteralPath $resolved -Force -Recurse -ErrorAction Stop)) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Refusing $Description because a descendant is a reparse point: $($item.FullName)"
        }
    }
    return $resolved
}

function Get-FabMatrixExpectedDataRoot([string]$InstallPath) {
    $commonData = [Environment]::GetFolderPath([Environment+SpecialFolder]::CommonApplicationData)
    if ([string]::IsNullOrWhiteSpace($commonData)) { $commonData = $env:ProgramData }
    $systemDrive = [IO.Path]::GetPathRoot([Environment]::SystemDirectory)
    $installDrive = [IO.Path]::GetPathRoot($InstallPath)
    if ([string]::Equals($installDrive, $systemDrive, [StringComparison]::OrdinalIgnoreCase)) {
        return ConvertTo-FabMatrixPurgeAbsolutePath (Join-Path $commonData 'FabMatrix') 'Expected data root'
    }
    return ConvertTo-FabMatrixPurgeAbsolutePath (Join-Path $InstallPath 'data') 'Expected data root'
}

function Assert-FabMatrixPurgePaths([string]$InstallDir, [string]$DataRoot, [string]$TestRoot = '') {
    $installPath = Assert-FabMatrixNoReparsePointsInTree $InstallDir 'InstallDir'
    $dataPath = ConvertTo-FabMatrixPurgeAbsolutePath $DataRoot 'DataRoot'
    if ([string]::Equals($installPath, $dataPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'DataRoot must not equal InstallDir.'
    }
    if (-not [string]::IsNullOrWhiteSpace($TestRoot)) {
        $testPath = ConvertTo-FabMatrixPurgeAbsolutePath $TestRoot 'Test root'
        if (-not (Test-FabMatrixPathWithin $installPath $testPath) -or
            -not (Test-FabMatrixPathWithin $dataPath $testPath) -or
            -not [string]::Equals($dataPath, (Join-Path $installPath 'data'), [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Self-test paths must be confined to the dedicated temporary test root.'
        }
    } else {
        $expectedPath = Get-FabMatrixExpectedDataRoot $installPath
        if (-not [string]::Equals($dataPath, $expectedPath, [StringComparison]::OrdinalIgnoreCase)) {
            throw "DataRoot is outside the established Local Edition policy path. Expected: $expectedPath; actual: $dataPath"
        }
    }
    $dataPath = Assert-FabMatrixNoReparsePointsInTree $dataPath 'DataRoot'
    return [pscustomobject]@{ InstallDir = $installPath; DataRoot = $dataPath }
}

function Remove-FabMatrixLocalData([string]$InstallDir, [string]$DataRoot, [string]$TestRoot = '') {
    $paths = Assert-FabMatrixPurgePaths $InstallDir $DataRoot $TestRoot
    if (-not (Test-Path -LiteralPath $paths.DataRoot)) {
        Write-Output "Business data root does not exist; nothing to purge: $($paths.DataRoot)"
        return
    }
    try {
        Remove-Item -LiteralPath $paths.DataRoot -Recurse -Force -ErrorAction Stop
    } catch {
        throw "Business data purge failed. Data may remain at '$($paths.DataRoot)': $($_.Exception.Message)"
    }
    if (Test-Path -LiteralPath $paths.DataRoot) {
        throw "Business data purge did not remove the complete data root; remaining data location: $($paths.DataRoot)"
    }
    Write-Output "Business data permanently deleted from: $($paths.DataRoot)"
}

function Remove-FabMatrixPurgeSelfTestRoot([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    $resolved = ConvertTo-FabMatrixPurgeAbsolutePath $Path 'Self-test root'
    $tempRoot = (ConvertTo-FabMatrixPurgeAbsolutePath ([IO.Path]::GetTempPath()) 'Temporary root').TrimEnd('\')
    if (-not [string]::Equals([IO.Path]::GetDirectoryName($resolved), $tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^FabMatrixPurgeSelfTest-[0-9a-f]+$') {
        throw "Refusing to remove an unexpected purge self-test root: $resolved"
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction Stop }
}

function Invoke-FabMatrixPurgeSelfTest {
    $selfTestRoot = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixPurgeSelfTest-' + [guid]::NewGuid().ToString('N'))
    $installPath = Join-Path $selfTestRoot 'install'
    $dataPath = Join-Path $installPath 'data'
    $externalPath = Join-Path $selfTestRoot 'external'
    $junctionPath = Join-Path $dataPath 'files'
    try {
        foreach ($directory in @((Join-Path $dataPath 'config'), (Join-Path $dataPath 'database'),
                (Join-Path $dataPath 'logs'), (Join-Path $dataPath 'backups'))) {
            [IO.Directory]::CreateDirectory($directory) | Out-Null
        }
        [IO.File]::WriteAllText((Join-Path $dataPath 'database\farm.db'), 'purge-self-test')
        [IO.Directory]::CreateDirectory($externalPath) | Out-Null
        [IO.File]::WriteAllText((Join-Path $externalPath 'sentinel.txt'), 'must-survive')
        $junctionCreated = $false
        try {
            New-Item -ItemType Junction -Path $junctionPath -Target $externalPath -ErrorAction Stop | Out-Null
            $junctionCreated = $true
        } catch {
            if ($_.Exception.Message -notmatch 'access|denied|privilege|not permitted|symbolic link') { throw }
            Write-Output "Reparse self-test skipped because junction creation is not permitted: $($_.Exception.Message)"
        }
        if ($junctionCreated) {
            try { Remove-FabMatrixLocalData $installPath $dataPath $selfTestRoot | Out-Null; throw 'Reparse self-test unexpectedly succeeded.' }
            catch { if ($_.Exception.Message -notmatch 'reparse point') { throw } }
            if (-not (Test-Path -LiteralPath (Join-Path $externalPath 'sentinel.txt'))) { throw 'Reparse self-test changed the external sentinel.' }
            [IO.Directory]::Delete($junctionPath)
        }
        Remove-FabMatrixLocalData $installPath $dataPath $selfTestRoot | Out-Null
        if (Test-Path -LiteralPath $dataPath) { throw 'Safe purge self-test left the data root behind.' }
        if (-not (Test-Path -LiteralPath $installPath)) { throw 'Safe purge self-test removed InstallDir.' }
        try { Assert-FabMatrixPurgePaths $installPath $installPath $selfTestRoot | Out-Null; throw 'InstallDir equality was not rejected.' }
        catch { if ($_.Exception.Message -notmatch 'must not equal InstallDir') { throw } }
        Write-Output 'FabMatrix Local data purge self-test passed.'
    } finally {
        if (Test-Path -LiteralPath $junctionPath) { [IO.Directory]::Delete($junctionPath) }
        Remove-FabMatrixPurgeSelfTestRoot $selfTestRoot
    }
}

if ($SelfTest) {
    Invoke-FabMatrixPurgeSelfTest
} else {
    Remove-FabMatrixLocalData $InstallDir $DataRoot
}
