param(
    [string]$InstallDir = '',
    [string]$DataRoot = '',
    [string]$LegacyDataRoot = '',
    [string]$DiagnosticsRoot = '',
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

function Test-FabMatrixDirectoryHasContent([string]$Path, [string]$Description) {
    $resolved = Assert-FabMatrixNoReparsePointsInPath $Path $Description
    return (Test-Path -LiteralPath $resolved -PathType Container) -and
        ($null -ne (Get-ChildItem -LiteralPath $resolved -Force -ErrorAction Stop | Select-Object -First 1))
}

function Test-FabMatrixLegacyFarmLayout([string]$LegacyPath) {
    if (-not (Test-Path -LiteralPath $LegacyPath)) { return $false }
    $legacyDb = Assert-FabMatrixNoReparsePointsInPath (Join-Path $LegacyPath 'data\farm.db') 'Legacy database marker'
    $legacyUploads = Join-Path $LegacyPath 'uploads'
    $legacyConfig = Join-Path $LegacyPath 'config'
    return (Test-Path -LiteralPath $legacyDb -PathType Leaf) -or
        (Test-FabMatrixDirectoryHasContent $legacyUploads 'Legacy uploads marker') -or
        (Test-FabMatrixDirectoryHasContent $legacyConfig 'Legacy config marker')
}

function Assert-FabMatrixPurgePaths(
    [string]$InstallDir,
    [string]$DataRoot,
    [string]$LegacyDataRoot,
    [string]$DiagnosticsRoot,
    [string]$TestRoot = '') {
    $installPath = Assert-FabMatrixNoReparsePointsInPath $InstallDir 'InstallDir'
    $dataPath = Assert-FabMatrixNoReparsePointsInPath $DataRoot 'DataRoot'
    $legacyPath = Assert-FabMatrixNoReparsePointsInPath $LegacyDataRoot 'LegacyDataRoot'
    $diagnosticsPath = Assert-FabMatrixNoReparsePointsInPath $DiagnosticsRoot 'DiagnosticsRoot'
    if ([string]::Equals($installPath, $dataPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'DataRoot must not equal InstallDir.'
    }
    foreach ($candidate in @($legacyPath, $diagnosticsPath)) {
        if ([string]::Equals($candidate, $installPath, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Purge targets must not equal InstallDir.'
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($TestRoot)) {
        $testPath = Assert-FabMatrixNoReparsePointsInPath $TestRoot 'Test root'
        $testCommonData = Join-Path $testPath 'common-data'
        $portableDataPath = Join-Path $installPath 'data'
        $sharedDataPath = Join-Path $testCommonData 'FabMatrix'
        if (-not (Test-FabMatrixPathWithin $installPath $testPath) -or
            -not (Test-FabMatrixPathWithin $dataPath $testPath) -or
            (-not [string]::Equals($dataPath, $portableDataPath, [StringComparison]::OrdinalIgnoreCase) -and
                -not [string]::Equals($dataPath, $sharedDataPath, [StringComparison]::OrdinalIgnoreCase)) -or
            -not [string]::Equals($legacyPath, (Join-Path $testCommonData 'Farm'), [StringComparison]::OrdinalIgnoreCase) -or
            -not [string]::Equals($diagnosticsPath, $sharedDataPath, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Self-test paths must be confined to the dedicated temporary test root.'
        }
    } else {
        $expectedPath = Get-FabMatrixExpectedDataRoot $installPath
        $commonData = [Environment]::GetFolderPath([Environment+SpecialFolder]::CommonApplicationData)
        if ([string]::IsNullOrWhiteSpace($commonData)) { $commonData = $env:ProgramData }
        $expectedLegacyPath = ConvertTo-FabMatrixPurgeAbsolutePath (Join-Path $commonData 'Farm') 'Expected legacy root'
        $expectedDiagnosticsPath = ConvertTo-FabMatrixPurgeAbsolutePath (Join-Path $commonData 'FabMatrix') 'Expected diagnostics root'
        if (-not [string]::Equals($dataPath, $expectedPath, [StringComparison]::OrdinalIgnoreCase)) {
            throw "DataRoot is outside the established Local Edition policy path. Expected: $expectedPath; actual: $dataPath"
        }
        if (-not [string]::Equals($legacyPath, $expectedLegacyPath, [StringComparison]::OrdinalIgnoreCase)) {
            throw "LegacyDataRoot is outside the established legacy policy path. Expected: $expectedLegacyPath; actual: $legacyPath"
        }
        if (-not [string]::Equals($diagnosticsPath, $expectedDiagnosticsPath, [StringComparison]::OrdinalIgnoreCase)) {
            throw "DiagnosticsRoot is outside the established diagnostics policy path. Expected: $expectedDiagnosticsPath; actual: $diagnosticsPath"
        }
    }

    if ((Test-Path -LiteralPath $legacyPath) -and -not (Test-FabMatrixLegacyFarmLayout $legacyPath)) {
        throw "LegacyDataRoot does not match the legacy Farm layout and will not be deleted: $legacyPath"
    }
    $targets = New-Object System.Collections.Generic.List[string]
    foreach ($candidate in @($dataPath, $legacyPath, $diagnosticsPath)) {
        if (-not ($targets | Where-Object { [string]::Equals($_, $candidate, [StringComparison]::OrdinalIgnoreCase) })) {
            [void]$targets.Add($candidate)
        }
    }
    return [pscustomobject]@{
        InstallDir = $installPath
        DataRoot = $dataPath
        LegacyDataRoot = $legacyPath
        DiagnosticsRoot = $diagnosticsPath
        Targets = @($targets)
    }
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

function Remove-FabMatrixLocalData(
    [string]$InstallDir,
    [string]$DataRoot,
    [string]$LegacyDataRoot,
    [string]$DiagnosticsRoot,
    [string]$TestRoot = '') {
    $paths = Assert-FabMatrixPurgePaths $InstallDir $DataRoot $LegacyDataRoot $DiagnosticsRoot $TestRoot
    foreach ($target in $paths.Targets) {
        if (-not (Test-Path -LiteralPath $target)) {
            Write-Output "Purge target does not exist; nothing to remove: $target"
            continue
        }
        try {
            Remove-FabMatrixTreeNodeSafely $target $target
        } catch {
            throw "FabMatrix complete purge failed. Data may remain at '$target'. All controlled locations: $($paths.Targets -join '; '). $($_.Exception.Message)"
        }
        if (Test-Path -LiteralPath $target) {
            throw "FabMatrix complete purge did not remove target '$target'. All controlled locations: $($paths.Targets -join '; ')"
        }
        Write-Output "FabMatrix controlled data permanently deleted from: $target"
    }
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

function New-FabMatrixLegacyPurgeFixture([string]$LegacyPath, [string]$Marker = 'legacy-purge-self-test') {
    foreach ($directory in @((Join-Path $LegacyPath 'config'), (Join-Path $LegacyPath 'data'),
            (Join-Path $LegacyPath 'uploads'), (Join-Path $LegacyPath 'logs'), (Join-Path $LegacyPath 'backups'))) {
        [IO.Directory]::CreateDirectory($directory) | Out-Null
    }
    [IO.File]::WriteAllText((Join-Path $LegacyPath 'data\farm.db'), $Marker)
    [IO.File]::WriteAllText((Join-Path $LegacyPath 'config\application.yml'), "server:`n  port: 8080`n")
}

function New-FabMatrixDiagnosticsPurgeFixture([string]$DiagnosticsPath) {
    $installerDiagnostics = Join-Path $DiagnosticsPath 'installer'
    [IO.Directory]::CreateDirectory($installerDiagnostics) | Out-Null
    [IO.File]::WriteAllText((Join-Path $installerDiagnostics 'service-install.log'), 'diagnostics-purge-self-test')
}

function Assert-FabMatrixPurgeRejectedMatching([scriptblock]$Action, [string]$Pattern, [string]$Description) {
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -notmatch $Pattern) {
            throw "$Description failed for the wrong reason: $($_.Exception.Message)"
        }
        return
    }
    throw "$Description unexpectedly succeeded."
}

function Assert-FabMatrixPurgeRejected([scriptblock]$Action, [string]$Description) {
    Assert-FabMatrixPurgeRejectedMatching $Action 'reparse point' $Description
}

function Invoke-FabMatrixPurgeSelfTest {
    $selfTestRoot = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixPurgeSelfTest-' + [guid]::NewGuid().ToString('N'))
    $installPath = Join-Path $selfTestRoot 'install'
    $dataPath = Join-Path $installPath 'data'
    $commonDataPath = Join-Path $selfTestRoot 'common-data'
    $legacyPath = Join-Path $commonDataPath 'Farm'
    $diagnosticsPath = Join-Path $commonDataPath 'FabMatrix'
    $workingPath = Join-Path $selfTestRoot 'different-working-directory'
    $knownLinks = New-Object System.Collections.Generic.List[string]
    try {
        [IO.Directory]::CreateDirectory($workingPath) | Out-Null

        New-FabMatrixPurgeFixture $dataPath 'different-cwd'
        New-FabMatrixLegacyPurgeFixture $legacyPath
        New-FabMatrixDiagnosticsPurgeFixture $diagnosticsPath
        Push-Location -LiteralPath $workingPath
        try {
            Remove-FabMatrixLocalData $installPath $dataPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        } finally {
            Pop-Location
        }
        if (Test-Path -LiteralPath $dataPath) { throw 'Different-working-directory purge left the data root behind.' }
        if (Test-Path -LiteralPath $legacyPath) { throw 'Multi-target purge left the legacy root behind.' }
        if (Test-Path -LiteralPath $diagnosticsPath) { throw 'Multi-target purge left the diagnostics root behind.' }
        if (-not (Test-Path -LiteralPath $installPath)) { throw 'Safe purge removed InstallDir.' }
        Write-Output 'Different working directory multi-target purge self-test passed.'
        Remove-FabMatrixLocalData $installPath $dataPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        Write-Output 'Missing multi-target idempotency self-test passed.'

        New-FabMatrixPurgeFixture $dataPath 'descendant-junction'
        $descendantExternal = Join-Path $selfTestRoot 'descendant-external'
        [IO.Directory]::CreateDirectory($descendantExternal) | Out-Null
        $descendantSentinel = Join-Path $descendantExternal 'sentinel.txt'
        [IO.File]::WriteAllText($descendantSentinel, 'must-survive')
        $descendantLink = Join-Path $dataPath 'files\external-link'
        New-Item -ItemType Junction -Path $descendantLink -Target $descendantExternal -ErrorAction Stop | Out-Null
        [void]$knownLinks.Add($descendantLink)
        Assert-FabMatrixPurgeRejected {
            Remove-FabMatrixLocalData $installPath $dataPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        } 'Descendant junction purge'
        if (-not (Test-Path -LiteralPath $descendantSentinel)) { throw 'Descendant junction purge changed the external sentinel.' }
        Remove-FabMatrixPurgeSelfTestLink $descendantLink $selfTestRoot
        [void]$knownLinks.Remove($descendantLink)
        Remove-FabMatrixLocalData $installPath $dataPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        Write-Output 'Descendant junction rejection self-test passed.'

        $dataRootExternal = Join-Path $selfTestRoot 'data-root-external'
        New-FabMatrixPurgeFixture $dataRootExternal 'data-root-junction'
        $dataRootSentinel = Join-Path $dataRootExternal 'database\farm.db'
        New-Item -ItemType Junction -Path $dataPath -Target $dataRootExternal -ErrorAction Stop | Out-Null
        [void]$knownLinks.Add($dataPath)
        Assert-FabMatrixPurgeRejected {
            Remove-FabMatrixLocalData $installPath $dataPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        } 'DataRoot root junction purge'
        if (-not (Test-Path -LiteralPath $dataRootSentinel)) { throw 'DataRoot root junction purge changed the external sentinel.' }
        Remove-FabMatrixPurgeSelfTestLink $dataPath $selfTestRoot
        [void]$knownLinks.Remove($dataPath)
        Write-Output 'DataRoot root junction rejection self-test passed.'

        $installTarget = Join-Path $selfTestRoot 'install-target'
        $installTargetData = Join-Path $installTarget 'data'
        New-FabMatrixPurgeFixture $installTargetData 'install-root-junction'
        $installLink = Join-Path $selfTestRoot 'install-link'
        New-Item -ItemType Junction -Path $installLink -Target $installTarget -ErrorAction Stop | Out-Null
        [void]$knownLinks.Add($installLink)
        Assert-FabMatrixPurgeRejected {
            Remove-FabMatrixLocalData $installLink (Join-Path $installLink 'data') $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
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
        [void]$knownLinks.Add($ancestorLink)
        Assert-FabMatrixPurgeRejected {
            $linkedInstall = Join-Path $ancestorLink 'nested-install'
            Remove-FabMatrixLocalData $linkedInstall (Join-Path $linkedInstall 'data') $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        } 'InstallDir ancestor junction purge'
        if (-not (Test-Path -LiteralPath (Join-Path $ancestorInstall 'data\database\farm.db'))) {
            throw 'InstallDir ancestor junction purge changed the target sentinel.'
        }
        Remove-FabMatrixPurgeSelfTestLink $ancestorLink $selfTestRoot
        [void]$knownLinks.Remove($ancestorLink)
        Write-Output 'InstallDir ancestor junction rejection self-test passed.'

        $sharedInstall = Join-Path $selfTestRoot 'shared-data-install'
        [IO.Directory]::CreateDirectory($sharedInstall) | Out-Null
        New-FabMatrixPurgeFixture $diagnosticsPath 'duplicate-target'
        Remove-FabMatrixLocalData $sharedInstall $diagnosticsPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        if (Test-Path -LiteralPath $diagnosticsPath) { throw 'Duplicate current/diagnostics target was not removed exactly once.' }
        Write-Output 'Duplicate current and diagnostics target self-test passed.'

        [IO.Directory]::CreateDirectory($legacyPath) | Out-Null
        $thirdPartyMarker = Join-Path $legacyPath 'third-party.txt'
        [IO.File]::WriteAllText($thirdPartyMarker, 'must-not-delete')
        Assert-FabMatrixPurgeRejectedMatching {
            Remove-FabMatrixLocalData $installPath $dataPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        } 'legacy Farm layout' 'Unrecognized legacy root purge'
        if (-not (Test-Path -LiteralPath $thirdPartyMarker)) { throw 'Unrecognized legacy root was changed.' }
        Remove-Item -LiteralPath $thirdPartyMarker -Force -ErrorAction Stop
        $legacyConfig = Join-Path $legacyPath 'config'
        [IO.Directory]::CreateDirectory($legacyConfig) | Out-Null
        [IO.File]::WriteAllText((Join-Path $legacyConfig 'application.yml'), "server:`n  port: 8080`n")
        Remove-FabMatrixLocalData $installPath $dataPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        Write-Output 'Legacy Farm layout rejection and acceptance self-test passed.'

        $legacyRootExternal = Join-Path $selfTestRoot 'legacy-root-external'
        New-FabMatrixLegacyPurgeFixture $legacyRootExternal 'legacy-root-junction'
        $legacyRootSentinel = Join-Path $legacyRootExternal 'data\farm.db'
        New-Item -ItemType Junction -Path $legacyPath -Target $legacyRootExternal -ErrorAction Stop | Out-Null
        [void]$knownLinks.Add($legacyPath)
        Assert-FabMatrixPurgeRejected {
            Remove-FabMatrixLocalData $installPath $dataPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        } 'Legacy root junction purge'
        if (-not (Test-Path -LiteralPath $legacyRootSentinel)) { throw 'Legacy root junction changed its target.' }
        Remove-FabMatrixPurgeSelfTestLink $legacyPath $selfTestRoot
        [void]$knownLinks.Remove($legacyPath)
        Write-Output 'Legacy root junction rejection self-test passed.'

        New-FabMatrixLegacyPurgeFixture $legacyPath 'legacy-descendant-junction'
        $legacyDescendantExternal = Join-Path $selfTestRoot 'legacy-descendant-external'
        [IO.Directory]::CreateDirectory($legacyDescendantExternal) | Out-Null
        $legacyDescendantSentinel = Join-Path $legacyDescendantExternal 'sentinel.txt'
        [IO.File]::WriteAllText($legacyDescendantSentinel, 'must-survive')
        $legacyDescendantLink = Join-Path $legacyPath 'uploads\external-link'
        New-Item -ItemType Junction -Path $legacyDescendantLink -Target $legacyDescendantExternal -ErrorAction Stop | Out-Null
        [void]$knownLinks.Add($legacyDescendantLink)
        Assert-FabMatrixPurgeRejected {
            Remove-FabMatrixLocalData $installPath $dataPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        } 'Legacy descendant junction purge'
        if (-not (Test-Path -LiteralPath $legacyDescendantSentinel)) { throw 'Legacy descendant junction changed its target.' }
        Remove-FabMatrixPurgeSelfTestLink $legacyDescendantLink $selfTestRoot
        [void]$knownLinks.Remove($legacyDescendantLink)
        New-FabMatrixLegacyPurgeFixture $legacyPath 'legacy-descendant-retry'
        Remove-FabMatrixLocalData $installPath $dataPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        Write-Output 'Legacy descendant junction rejection self-test passed.'

        $diagnosticsRootExternal = Join-Path $selfTestRoot 'diagnostics-root-external'
        New-FabMatrixDiagnosticsPurgeFixture $diagnosticsRootExternal
        $diagnosticsRootSentinel = Join-Path $diagnosticsRootExternal 'installer\service-install.log'
        New-Item -ItemType Junction -Path $diagnosticsPath -Target $diagnosticsRootExternal -ErrorAction Stop | Out-Null
        [void]$knownLinks.Add($diagnosticsPath)
        Assert-FabMatrixPurgeRejected {
            Remove-FabMatrixLocalData $installPath $dataPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        } 'Diagnostics root junction purge'
        if (-not (Test-Path -LiteralPath $diagnosticsRootSentinel)) { throw 'Diagnostics root junction changed its target.' }
        Remove-FabMatrixPurgeSelfTestLink $diagnosticsPath $selfTestRoot
        [void]$knownLinks.Remove($diagnosticsPath)
        Write-Output 'Diagnostics root junction rejection self-test passed.'

        New-FabMatrixDiagnosticsPurgeFixture $diagnosticsPath
        $diagnosticsDescendantExternal = Join-Path $selfTestRoot 'diagnostics-descendant-external'
        [IO.Directory]::CreateDirectory($diagnosticsDescendantExternal) | Out-Null
        $diagnosticsDescendantSentinel = Join-Path $diagnosticsDescendantExternal 'sentinel.txt'
        [IO.File]::WriteAllText($diagnosticsDescendantSentinel, 'must-survive')
        $diagnosticsDescendantLink = Join-Path $diagnosticsPath 'installer\external-link'
        New-Item -ItemType Junction -Path $diagnosticsDescendantLink -Target $diagnosticsDescendantExternal -ErrorAction Stop | Out-Null
        [void]$knownLinks.Add($diagnosticsDescendantLink)
        Assert-FabMatrixPurgeRejected {
            Remove-FabMatrixLocalData $installPath $dataPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        } 'Diagnostics descendant junction purge'
        if (-not (Test-Path -LiteralPath $diagnosticsDescendantSentinel)) { throw 'Diagnostics descendant junction changed its target.' }
        Remove-FabMatrixPurgeSelfTestLink $diagnosticsDescendantLink $selfTestRoot
        [void]$knownLinks.Remove($diagnosticsDescendantLink)
        Remove-FabMatrixLocalData $installPath $dataPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        Write-Output 'Diagnostics descendant junction rejection self-test passed.'

        if (Test-Path -LiteralPath $commonDataPath) { Remove-Item -LiteralPath $commonDataPath -Force -ErrorAction Stop }
        $commonAncestorExternal = Join-Path $selfTestRoot 'common-ancestor-external'
        New-FabMatrixLegacyPurgeFixture (Join-Path $commonAncestorExternal 'Farm') 'common-ancestor-junction'
        New-FabMatrixDiagnosticsPurgeFixture (Join-Path $commonAncestorExternal 'FabMatrix')
        New-Item -ItemType Junction -Path $commonDataPath -Target $commonAncestorExternal -ErrorAction Stop | Out-Null
        [void]$knownLinks.Add($commonDataPath)
        Assert-FabMatrixPurgeRejected {
            Remove-FabMatrixLocalData $installPath $dataPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
        } 'Legacy ancestor junction purge'
        Assert-FabMatrixPurgeRejected {
            Assert-FabMatrixNoReparsePointsInPath $diagnosticsPath 'DiagnosticsRoot' | Out-Null
        } 'Diagnostics ancestor junction validation'
        Remove-FabMatrixPurgeSelfTestLink $commonDataPath $selfTestRoot
        [void]$knownLinks.Remove($commonDataPath)
        Write-Output 'Legacy and diagnostics ancestor junction rejection self-test passed.'

        try {
            Assert-FabMatrixPurgePaths $installPath $installPath $legacyPath $diagnosticsPath $selfTestRoot | Out-Null
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
    Remove-FabMatrixLocalData $InstallDir $DataRoot $LegacyDataRoot $DiagnosticsRoot
}
