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

    $root = [IO.Path]::GetPathRoot($resolved)
    if ([string]::IsNullOrWhiteSpace($root) -or $root -notmatch '^[A-Za-z]:\\$') {
        throw "$Description is not an ordinary local-drive path: $resolved"
    }
    if ([string]::Equals($resolved, $root, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description must not be a drive root: $resolved"
    }
    return $resolved.TrimEnd('\')
}

function Test-FabMatrixPathWithin([string]$Path, [string]$ParentPath, [switch]$AllowEqual) {
    $resolvedPath = ConvertTo-FabMatrixPurgeAbsolutePath $Path 'Path'
    $resolvedParent = ConvertTo-FabMatrixPurgeAbsolutePath $ParentPath 'Parent path'
    if ($AllowEqual -and [string]::Equals($resolvedPath, $resolvedParent, [StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }
    return $resolvedPath.StartsWith($resolvedParent + '\', [StringComparison]::OrdinalIgnoreCase)
}

function Assert-FabMatrixNotReparsePoint($Item, [string]$Description) {
    if (($Item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Refusing $Description because it contains a reparse point: $($Item.FullName)"
    }
}

function Assert-FabMatrixNoReparsePointsInPath([string]$Path, [string]$Description) {
    $resolved = ConvertTo-FabMatrixPurgeAbsolutePath $Path $Description
    $root = [IO.Path]::GetPathRoot($resolved)

    if (Test-Path -LiteralPath $root) {
        $rootItem = Get-Item -LiteralPath $root -Force -ErrorAction Stop
        Assert-FabMatrixNotReparsePoint $rootItem $Description
    }

    $current = $root
    $relative = $resolved.Substring($root.Length)
    foreach ($component in @($relative.Split([char]'\', [StringSplitOptions]::RemoveEmptyEntries))) {
        $current = [IO.Path]::Combine($current, $component)
        if (-not (Test-Path -LiteralPath $current)) { break }
        $item = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if (-not [string]::Equals($item.Name, $component, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing $Description because it contains a non-canonical path component: $current"
        }
        Assert-FabMatrixNotReparsePoint $item $Description
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
    $installPath = Assert-FabMatrixNoReparsePointsInPath $InstallDir 'InstallDir'
    $dataPath = Assert-FabMatrixNoReparsePointsInPath $DataRoot 'DataRoot'
    if ([string]::Equals($installPath, $dataPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'DataRoot must not equal InstallDir.'
    }

    if (-not [string]::IsNullOrWhiteSpace($TestRoot)) {
        $testPath = Assert-FabMatrixNoReparsePointsInPath $TestRoot 'Test root'
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
    return [pscustomobject]@{ InstallDir = $installPath; DataRoot = $dataPath }
}

function Remove-FabMatrixTreeNodeSafely([string]$Path, [string]$ValidatedDataRoot) {
    $resolved = Assert-FabMatrixNoReparsePointsInPath $Path 'Purge node'
    if (-not (Test-FabMatrixPathWithin $resolved $ValidatedDataRoot -AllowEqual)) {
        throw "Purge node escaped the validated DataRoot: $resolved"
    }
    if (-not (Test-Path -LiteralPath $resolved)) { return }

    $item = Get-Item -LiteralPath $resolved -Force -ErrorAction Stop
    Assert-FabMatrixNotReparsePoint $item 'Purge node'
    if ($item.PSIsContainer) {
        foreach ($child in @(Get-ChildItem -LiteralPath $resolved -Force -ErrorAction Stop)) {
            Remove-FabMatrixTreeNodeSafely $child.FullName $ValidatedDataRoot
        }

        $finalDirectory = Get-Item -LiteralPath $resolved -Force -ErrorAction Stop
        Assert-FabMatrixNotReparsePoint $finalDirectory 'Purge directory'
        if (-not $finalDirectory.PSIsContainer) {
            throw "Purge directory changed type while being removed: $resolved"
        }
        if (@(Get-ChildItem -LiteralPath $resolved -Force -ErrorAction Stop).Count -ne 0) {
            throw "Purge directory changed while being removed and is no longer empty: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Force -ErrorAction Stop
    } else {
        $finalFile = Get-Item -LiteralPath $resolved -Force -ErrorAction Stop
        Assert-FabMatrixNotReparsePoint $finalFile 'Purge file'
        if ($finalFile.PSIsContainer) {
            throw "Purge file changed type while being removed: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Force -ErrorAction Stop
    }
}

function Remove-FabMatrixLocalData([string]$InstallDir, [string]$DataRoot, [string]$TestRoot = '') {
    $paths = Assert-FabMatrixPurgePaths $InstallDir $DataRoot $TestRoot
    if (-not (Test-Path -LiteralPath $paths.DataRoot)) {
        Write-Output "Business data root does not exist; nothing to purge: $($paths.DataRoot)"
        return
    }
    try {
        Remove-FabMatrixTreeNodeSafely $paths.DataRoot $paths.DataRoot
    } catch {
        throw "Business data purge failed. Data may remain at '$($paths.DataRoot)': $($_.Exception.Message)"
    }
    if (Test-Path -LiteralPath $paths.DataRoot) {
        throw "Business data purge did not remove the complete data root; remaining data location: $($paths.DataRoot)"
    }
    Write-Output "Business data permanently deleted from: $($paths.DataRoot)"
}

function Remove-FabMatrixPurgeSelfTestLink([string]$Path, [string]$DedicatedRoot) {
    $resolved = ConvertTo-FabMatrixPurgeAbsolutePath $Path 'Self-test link'
    if (-not (Test-FabMatrixPathWithin $resolved $DedicatedRoot)) {
        throw "Refusing to remove a self-test link outside the dedicated root: $resolved"
    }
    if (-not (Test-Path -LiteralPath $resolved)) { return }
    $item = Get-Item -LiteralPath $resolved -Force -ErrorAction Stop
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) {
        throw "Refusing to remove a self-test path that is not a reparse point: $resolved"
    }
    [IO.Directory]::Delete($resolved)
}

function Remove-FabMatrixPurgeSelfTestRoot([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    $resolved = ConvertTo-FabMatrixPurgeAbsolutePath $Path 'Self-test root'
    $tempRoot = ConvertTo-FabMatrixPurgeAbsolutePath ([IO.Path]::GetTempPath()) 'Temporary root'
    if (-not [string]::Equals([IO.Path]::GetDirectoryName($resolved), $tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^FabMatrixPurgeSelfTest-[0-9a-f]+$') {
        throw "Refusing to remove an unexpected self-test root: $resolved"
    }
    if (Test-Path -LiteralPath $resolved) { Remove-FabMatrixTreeNodeSafely $resolved $resolved }
}

function New-FabMatrixPurgeFixture([string]$DataPath, [string]$Marker = 'purge-self-test') {
    foreach ($directory in @((Join-Path $DataPath 'config'), (Join-Path $DataPath 'database'),
            (Join-Path $DataPath 'files'), (Join-Path $DataPath 'logs'), (Join-Path $DataPath 'backups'))) {
        [IO.Directory]::CreateDirectory($directory) | Out-Null
    }
    [IO.File]::WriteAllText((Join-Path $DataPath 'database\farm.db'), $Marker)
}

function Assert-FabMatrixPurgeRejected([scriptblock]$Action, [string]$Description) {
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -notmatch 'reparse point') {
            throw "$Description failed for the wrong reason: $($_.Exception.Message)"
        }
        return
    }
    throw "$Description unexpectedly succeeded."
}

function Invoke-FabMatrixPurgeSelfTest {
    $selfTestRoot = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixPurgeSelfTest-' + [guid]::NewGuid().ToString('N'))
    $installPath = Join-Path $selfTestRoot 'install'
    $dataPath = Join-Path $installPath 'data'
    $workingPath = Join-Path $selfTestRoot 'different-working-directory'
    $knownLinks = New-Object System.Collections.Generic.List[string]
    try {
        [IO.Directory]::CreateDirectory($workingPath) | Out-Null

        New-FabMatrixPurgeFixture $dataPath 'different-cwd'
        Push-Location -LiteralPath $workingPath
        try {
            Remove-FabMatrixLocalData $installPath $dataPath $selfTestRoot | Out-Null
        } finally {
            Pop-Location
        }
        if (Test-Path -LiteralPath $dataPath) { throw 'Different-working-directory purge left the data root behind.' }
        if (-not (Test-Path -LiteralPath $installPath)) { throw 'Safe purge removed InstallDir.' }
        Write-Output 'Different working directory purge self-test passed.'

        New-FabMatrixPurgeFixture $dataPath 'descendant-junction'
        $descendantExternal = Join-Path $selfTestRoot 'descendant-external'
        [IO.Directory]::CreateDirectory($descendantExternal) | Out-Null
        $descendantSentinel = Join-Path $descendantExternal 'sentinel.txt'
        [IO.File]::WriteAllText($descendantSentinel, 'must-survive')
        $descendantLink = Join-Path $dataPath 'files\external-link'
        New-Item -ItemType Junction -Path $descendantLink -Target $descendantExternal -ErrorAction Stop | Out-Null
        $knownLinks.Add($descendantLink)
        Assert-FabMatrixPurgeRejected { Remove-FabMatrixLocalData $installPath $dataPath $selfTestRoot | Out-Null } 'Descendant junction purge'
        if (-not (Test-Path -LiteralPath $descendantSentinel)) { throw 'Descendant junction purge changed the external sentinel.' }
        Remove-FabMatrixPurgeSelfTestLink $descendantLink $selfTestRoot
        [void]$knownLinks.Remove($descendantLink)
        Remove-FabMatrixLocalData $installPath $dataPath $selfTestRoot | Out-Null
        Write-Output 'Descendant junction rejection self-test passed.'

        $dataRootExternal = Join-Path $selfTestRoot 'data-root-external'
        New-FabMatrixPurgeFixture $dataRootExternal 'data-root-junction'
        $dataRootSentinel = Join-Path $dataRootExternal 'database\farm.db'
        New-Item -ItemType Junction -Path $dataPath -Target $dataRootExternal -ErrorAction Stop | Out-Null
        $knownLinks.Add($dataPath)
        Assert-FabMatrixPurgeRejected { Remove-FabMatrixLocalData $installPath $dataPath $selfTestRoot | Out-Null } 'DataRoot root junction purge'
        if (-not (Test-Path -LiteralPath $dataRootSentinel)) { throw 'DataRoot root junction purge changed the external sentinel.' }
        Remove-FabMatrixPurgeSelfTestLink $dataPath $selfTestRoot
        [void]$knownLinks.Remove($dataPath)
        Write-Output 'DataRoot root junction rejection self-test passed.'

        $installTarget = Join-Path $selfTestRoot 'install-target'
        $installTargetData = Join-Path $installTarget 'data'
        New-FabMatrixPurgeFixture $installTargetData 'install-root-junction'
        $installLink = Join-Path $selfTestRoot 'install-link'
        New-Item -ItemType Junction -Path $installLink -Target $installTarget -ErrorAction Stop | Out-Null
        $knownLinks.Add($installLink)
        Assert-FabMatrixPurgeRejected {
            Remove-FabMatrixLocalData $installLink (Join-Path $installLink 'data') $selfTestRoot | Out-Null
        } 'InstallDir root junction purge'
        if (-not (Test-Path -LiteralPath (Join-Path $installTargetData 'database\farm.db'))) {
            throw 'InstallDir root junction purge changed the target sentinel.'
        }
        Remove-FabMatrixPurgeSelfTestLink $installLink $selfTestRoot
        [void]$knownLinks.Remove($installLink)
        Write-Output 'InstallDir root junction rejection self-test passed.'

        $ancestorTarget = Join-Path $selfTestRoot 'ancestor-target'
        $ancestorInstall = Join-Path $ancestorTarget 'nested-install'
        New-FabMatrixPurgeFixture (Join-Path $ancestorInstall 'data') 'install-ancestor-junction'
        $ancestorLink = Join-Path $selfTestRoot 'ancestor-link'
        New-Item -ItemType Junction -Path $ancestorLink -Target $ancestorTarget -ErrorAction Stop | Out-Null
        $knownLinks.Add($ancestorLink)
        Assert-FabMatrixPurgeRejected {
            $linkedInstall = Join-Path $ancestorLink 'nested-install'
            Remove-FabMatrixLocalData $linkedInstall (Join-Path $linkedInstall 'data') $selfTestRoot | Out-Null
        } 'InstallDir ancestor junction purge'
        if (-not (Test-Path -LiteralPath (Join-Path $ancestorInstall 'data\database\farm.db'))) {
            throw 'InstallDir ancestor junction purge changed the target sentinel.'
        }
        Remove-FabMatrixPurgeSelfTestLink $ancestorLink $selfTestRoot
        [void]$knownLinks.Remove($ancestorLink)
        Write-Output 'InstallDir ancestor junction rejection self-test passed.'

        try {
            Assert-FabMatrixPurgePaths $installPath $installPath $selfTestRoot | Out-Null
            throw 'InstallDir equality was not rejected.'
        } catch {
            if ($_.Exception.Message -notmatch 'must not equal') { throw }
        }
        Write-Output 'FabMatrix Local data purge self-test passed.'
    } finally {
        foreach ($link in @($knownLinks)) {
            if (Test-Path -LiteralPath $link) { Remove-FabMatrixPurgeSelfTestLink $link $selfTestRoot }
        }
        Remove-FabMatrixPurgeSelfTestRoot $selfTestRoot
    }
}

if ($SelfTest) {
    Invoke-FabMatrixPurgeSelfTest
} else {
    Remove-FabMatrixLocalData $InstallDir $DataRoot
}
