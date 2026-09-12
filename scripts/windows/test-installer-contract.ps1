param(
    [switch]$SkipTrayCompile,
    [string]$PayloadPath = '',
    [string]$ReleaseInfoPath = ''
)

$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptRoot '..\..')).Path
$assets = Join-Path $scriptRoot 'assets'
$build = Join-Path $scriptRoot 'build-installer.ps1'
$contractDistRoot = Join-Path $projectRoot 'dist'

function Assert-Contains([string]$Path, [string]$Pattern, [string]$Description) {
    $content = Get-Content -Raw -LiteralPath $Path
    if ($content -notmatch $Pattern) { throw "Contract check failed: $Description ($Path)" }
}

function Assert-NotContains([string]$Path, [string]$Pattern, [string]$Description) {
    $content = Get-Content -Raw -LiteralPath $Path
    if ($content -match $Pattern) { throw "Contract check must not match: $Description ($Path)" }
}

function Assert-Exists([string]$Path, [string]$Description) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "Contract path missing: $Description ($Path)" }
}

function Assert-Throws([scriptblock]$Action, [string]$Description) {
    try {
        & $Action
    } catch {
        return
    }
    throw "Contract check expected failure: $Description"
}

function Assert-ThrowsMatching([scriptblock]$Action, [string]$Pattern, [string]$Description) {
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -notmatch $Pattern) {
            throw "Contract check failed for the wrong reason: $Description ($($_.Exception.Message))"
        }
        return
    }
    throw "Contract check expected failure: $Description"
}

function Remove-DistContractFixture([string]$Path) {
    $resolvedPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $resolvedDist = [IO.Path]::GetFullPath($contractDistRoot).TrimEnd('\')
    if (-not [string]::Equals([IO.Path]::GetDirectoryName($resolvedPath), $resolvedDist, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolvedPath) -notmatch '^installer-contract-[0-9a-z-]+$') {
        throw "Refusing to remove an unexpected dist contract fixture: $resolvedPath"
    }
    if (Test-Path -LiteralPath $resolvedPath) { Remove-Item -LiteralPath $resolvedPath -Recurse -Force }
}

function Remove-TempContractFixture([string]$Path) {
    $resolvedPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if (-not [string]::Equals([IO.Path]::GetDirectoryName($resolvedPath), $resolvedTemp, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolvedPath) -notmatch '^FabMatrixExternalOutputContract-[0-9a-f]+$') {
        throw "Refusing to remove an unexpected temp contract fixture: $resolvedPath"
    }
    if (Test-Path -LiteralPath $resolvedPath) { Remove-Item -LiteralPath $resolvedPath -Recurse -Force }
}

function Get-IcoDimensions([string]$Path) {
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 6 -or $bytes[0] -ne 0 -or $bytes[1] -ne 0 -or $bytes[2] -ne 1 -or $bytes[3] -ne 0) {
        throw "ICO header is invalid: $Path"
    }
    $count = [BitConverter]::ToUInt16($bytes, 4)
    if ($count -lt 1 -or $bytes.Length -lt 6 + (16 * $count)) { throw "ICO directory is invalid: $Path" }
    $dimensions = @()
    for ($index = 0; $index -lt $count; $index++) {
        $entryOffset = 6 + (16 * $index)
        $width = if ($bytes[$entryOffset] -eq 0) { 256 } else { [int]$bytes[$entryOffset] }
        $height = if ($bytes[$entryOffset + 1] -eq 0) { 256 } else { [int]$bytes[$entryOffset + 1] }
        $size = [BitConverter]::ToUInt32($bytes, $entryOffset + 8)
        $offset = [BitConverter]::ToUInt32($bytes, $entryOffset + 12)
        if ($width -ne $height -or $size -lt 8 -or $offset -lt (6 + (16 * $count)) -or $offset + $size -gt $bytes.Length) {
            throw "ICO directory entry is invalid: $Path"
        }
        if ($bytes[$offset] -ne 0x89 -or $bytes[$offset + 1] -ne 0x50 -or $bytes[$offset + 2] -ne 0x4E -or $bytes[$offset + 3] -ne 0x47) {
            throw "ICO entry is not PNG encoded: $Path"
        }
        $dimensions += $width
    }
    return $dimensions
}

foreach ($assetName in @('installer.ico', 'server.ico', 'tray.ico', 'tray.png')) {
    Assert-Exists (Join-Path $assets $assetName) "brand asset $assetName"
}
$expectedIconDimensions = @(16, 24, 32, 48, 64, 128, 256)
foreach ($icoName in @('installer.ico', 'server.ico', 'tray.ico')) {
    $dimensions = @(Get-IcoDimensions (Join-Path $assets $icoName)) | Sort-Object -Unique
    if (($dimensions -join ',') -ne ($expectedIconDimensions -join ',')) {
        throw "ICO does not contain the required Windows sizes: $icoName ($($dimensions -join ', '))"
    }
}
$pngBytes = [IO.File]::ReadAllBytes((Join-Path $assets 'tray.png'))
$pngSignature = [byte[]](0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
for ($index = 0; $index -lt $pngSignature.Length; $index++) {
    if ($pngBytes.Length -lt 33 -or $pngBytes[$index] -ne $pngSignature[$index]) { throw 'tray.png is not a valid PNG.' }
}
if ([Text.Encoding]::ASCII.GetString($pngBytes, 12, 4) -ne 'IHDR') { throw 'tray.png has no IHDR chunk.' }

$buildSource = Get-Content -LiteralPath $build -Raw -Encoding UTF8
$buildTokens = $null
$buildParseErrors = $null
$buildAst = [System.Management.Automation.Language.Parser]::ParseInput($buildSource, $build, [ref]$buildTokens, [ref]$buildParseErrors)
if ($buildParseErrors.Count -ne 0) { throw "Build script AST parse failed: $($buildParseErrors[0].Message)" }
$parameterNames = @($buildAst.ParamBlock.Parameters | ForEach-Object { $_.Name.VariablePath.UserPath })
if ('CleanOutput' -notin $parameterNames) { throw 'Build script does not expose -CleanOutput.' }
$buildFunctionAsts = @{}
foreach ($functionName in @('Resolve-AbsolutePath', 'Assert-NoReparsePointsInPath', 'Assert-PathContainedBy',
        'Assert-SafeOutputPath', 'Remove-SafeGeneratedPath', 'Clear-KnownOutput')) {
    $functionAst = $buildAst.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName }, $true)
    if ($null -eq $functionAst) { throw "Build script does not expose required function: $functionName" }
    $buildFunctionAsts[$functionName] = $functionAst
    Invoke-Expression $functionAst.Extent.Text
}
$safeOutputFunctionText = $buildFunctionAsts['Assert-SafeOutputPath'].Extent.Text
$reparseFunctionText = $buildFunctionAsts['Assert-NoReparsePointsInPath'].Extent.Text
$strictDistPolicyFallbackReady = $safeOutputFunctionText -match 'Join-Path\s+\$resolvedProject\s+''dist''' -and
    $safeOutputFunctionText -match 'StartsWith\(\$allowedOutputRoot\s*\+\s*''\\''' -and
    $safeOutputFunctionText -match 'Assert-NoReparsePointsInPath\s+\$resolvedOutput' -and
    $reparseFunctionText -match 'canonicalItem\.Name'
if (-not $strictDistPolicyFallbackReady) {
    throw 'Build script does not expose the strict dist-subdirectory and canonical-component policy required for capability fallbacks.'
}
$safeOutputAssignments = @($buildAst.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
            $node.Left.Extent.Text -eq '$outputPath' -and
            $node.Right.Extent.Text -match '^Assert-SafeOutputPath\s+\$outputPath\s+\$projectRoot$'
        }, $true))
if ($safeOutputAssignments.Count -ne 1) {
    throw 'Build script must unconditionally validate OutputDir before the build workflow starts.'
}
$outputResolveAssignments = @($buildAst.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
            $node.Left.Extent.Text -eq '$outputPath' -and
            $node.Right.Extent.Text -match '^Resolve-AbsolutePath\s+\$OutputDir\s+\$projectRoot$'
        }, $true))
if ($outputResolveAssignments.Count -ne 1 -or
    $outputResolveAssignments[0].Extent.EndOffset -gt $safeOutputAssignments[0].Extent.StartOffset) {
    throw 'Build script does not validate the raw OutputDir before output safety checks.'
}
$brandAssetsCommand = $buildAst.Find({
        param($node)
        $node -is [System.Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Test-WindowsBrandAssets'
    }, $true)
if ($null -eq $brandAssetsCommand -or
    $safeOutputAssignments[0].Extent.EndOffset -gt $brandAssetsCommand.Extent.StartOffset) {
    throw 'Build script validates OutputDir too late in the build workflow.'
}
$removeCommands = @($buildAst.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Remove-Item'
        }, $true))
foreach ($removeCommand in $removeCommands) {
    $owner = $removeCommand.Parent
    while ($null -ne $owner -and $owner -isnot [System.Management.Automation.Language.FunctionDefinitionAst]) {
        $owner = $owner.Parent
    }
    if ($null -eq $owner -or $owner.Name -ne 'Remove-SafeGeneratedPath') {
        throw "Build script bypasses Remove-SafeGeneratedPath: $($removeCommand.Extent.Text)"
    }
}
$safeGeneratedRemovals = @($buildAst.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Remove-SafeGeneratedPath'
        }, $true))
foreach ($requiredPathVariable in @('$inputPath', '$trayClasses', '$trayJar', '$imagePath', '$payloadPath')) {
    if (-not ($safeGeneratedRemovals | Where-Object {
                $_.CommandElements.Count -ge 2 -and $_.CommandElements[1].Extent.Text -eq $requiredPathVariable
            })) {
        throw "Build staging deletion does not use Remove-SafeGeneratedPath for $requiredPathVariable."
    }
}
$jpackageAssignment = $buildAst.Find({ param($node) $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$jpackageArgs' }, $true)
if ($null -eq $jpackageAssignment) { throw 'Build script jpackage arguments are not represented as an assignment.' }
foreach ($requiredLauncherToken in @('--icon', '--add-launcher', '--win-console')) {
    if ($jpackageAssignment.Extent.Text -notmatch [regex]::Escape($requiredLauncherToken)) { throw "jpackage arguments omit $requiredLauncherToken." }
}
$serverIconBinding = [regex]::Escape("'--icon'") + '\s*,\s*\$serverIconPath'
if ($jpackageAssignment.Extent.Text -notmatch $serverIconBinding) {
    throw 'jpackage --icon is not immediately bound to $serverIconPath.'
}
$trayIconAssignment = $buildAst.Find({ param($node) $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$trayIconPropertyPath' }, $true)
if ($null -eq $trayIconAssignment -or $trayIconAssignment.Right.Extent.Text -notmatch '^\$trayIconPath\s+-replace') {
    throw 'Tray launcher property icon path is not derived from $trayIconPath.'
}
if ($buildSource -notmatch '(?m)^icon=\$trayIconPropertyPath$' -or $buildSource -notmatch 'Copy-Item -LiteralPath \$trayPngPath') {
    throw 'Tray launcher icon/resource wiring is missing from the build script.'
}

# Exercise the cleanup function only against a disposable direct child of the repository dist directory.
$cleanTemp = Join-Path $contractDistRoot ('installer-contract-clean-' + [guid]::NewGuid().ToString('N'))
try {
    $fixtureOutput = $cleanTemp
    New-Item -ItemType Directory -Force -Path (Join-Path $fixtureOutput 'work'), (Join-Path $fixtureOutput 'precommit-check') | Out-Null
    foreach ($knownFile in @('FabMatrixServer-Setup.exe', 'Farm-Setup.exe', 'checksums.txt', 'release-info.txt', 'README-Windows.md', 'acceptance-install.log')) {
        New-Item -ItemType File -Force -Path (Join-Path $fixtureOutput $knownFile) | Out-Null
    }
    New-Item -ItemType File -Force -Path (Join-Path $fixtureOutput 'keep-me.txt') | Out-Null
    Clear-KnownOutput $fixtureOutput $projectRoot
    foreach ($knownPath in @('work', 'precommit-check', 'FabMatrixServer-Setup.exe', 'Farm-Setup.exe', 'checksums.txt', 'release-info.txt', 'README-Windows.md', 'acceptance-install.log')) {
        if (Test-Path -LiteralPath (Join-Path $fixtureOutput $knownPath)) { throw "CleanOutput retained known generated item: $knownPath" }
    }
    Assert-Exists (Join-Path $fixtureOutput 'keep-me.txt') 'unknown output fixture'
    Assert-Exists $fixtureOutput 'output directory itself'
    foreach ($dangerousPath in @($projectRoot, (Join-Path $projectRoot 'dist'), (Join-Path $projectRoot 'src'),
            (Join-Path $projectRoot 'scripts'), (Join-Path $projectRoot 'scripts\windows\assets'),
            (Split-Path $projectRoot -Parent), [IO.Path]::GetPathRoot($projectRoot))) {
        Assert-Throws { Clear-KnownOutput $dangerousPath $projectRoot } "dangerous output path $dangerousPath"
    }
    $protectedAliasTargets = @(
        [pscustomobject]@{ Label = 'repository root'; Path = $projectRoot; Sentinel = (Join-Path $projectRoot 'AGENTS.md') },
        [pscustomobject]@{ Label = 'scripts/windows'; Path = (Join-Path $projectRoot 'scripts\windows'); Sentinel = (Join-Path $projectRoot 'scripts\windows\README-Windows.md') },
        [pscustomobject]@{ Label = 'src'; Path = (Join-Path $projectRoot 'src'); Sentinel = (Join-Path $projectRoot 'src\main\java\com\example\farm\tray\FabMatrixTray.java') }
    )
    foreach ($target in $protectedAliasTargets) {
        $sentinelHash = (Get-FileHash -LiteralPath $target.Sentinel -Algorithm SHA256).Hash
        $driveLetter = $target.Path.Substring(0, 1)
        $driveTail = $target.Path.Substring(3)
        $aliases = @(
            "\\?\$($target.Path)",
            "\\.\$($target.Path)",
            "\??\$($target.Path)",
            "\\localhost\$driveLetter`$\$driveTail"
        )
        foreach ($alias in $aliases) {
            Assert-ThrowsMatching { Assert-SafeOutputPath $alias $projectRoot } 'ordinary local-drive' "$($target.Label) device/UNC alias"
            if ((Get-FileHash -LiteralPath $target.Sentinel -Algorithm SHA256).Hash -ne $sentinelHash) {
                throw "Protected source sentinel changed while rejecting $($target.Label) alias: $($target.Sentinel)"
            }
        }
    }
    $defaultOutput = Join-Path $projectRoot 'dist\windows-installer'
    if ((Assert-SafeOutputPath $defaultOutput $projectRoot) -ne [IO.Path]::GetFullPath($defaultOutput)) {
        throw 'Default dist\windows-installer output path was unexpectedly rejected.'
    }
    if ((Resolve-AbsolutePath 'dist\windows-installer' $projectRoot) -ne [IO.Path]::GetFullPath($defaultOutput) -or
        (Resolve-AbsolutePath $fixtureOutput) -ne [IO.Path]::GetFullPath($fixtureOutput)) {
        throw 'Ordinary project-relative or local-drive output path was unexpectedly rejected.'
    }
    $shortPath = $null
    if (Get-Command cmd.exe -ErrorAction SilentlyContinue) {
        $shortPathLines = @(& cmd.exe /d /c "for %I in (`"$fixtureOutput`") do @echo %~sI")
        if ($LASTEXITCODE -eq 0 -and $shortPathLines.Count -gt 0) {
            $shortPath = ([string]$shortPathLines[-1]).Trim()
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($shortPath) -and
        -not [string]::Equals($shortPath, $fixtureOutput, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $shortPath)) {
        Assert-ThrowsMatching { Assert-SafeOutputPath $shortPath $projectRoot } 'strict subdirectory|non-canonical|short-name' '8.3 short output alias'
        Assert-Exists (Join-Path $fixtureOutput 'keep-me.txt') 'short-path rejection sentinel'
    } else {
        Write-Host '8.3 short-path fixture unavailable; strict dist-root and canonical-component policy fallback passed.'
    }

    $substCreated = $false
    $substDrive = @('Z', 'Y', 'X', 'W', 'V') | Where-Object {
        -not (Get-PSDrive -Name $_ -ErrorAction SilentlyContinue) -and -not (Test-Path -LiteralPath "$_`:\")
    } | Select-Object -First 1
    if ($substDrive -and (Get-Command subst.exe -ErrorAction SilentlyContinue)) {
        & subst.exe "$substDrive`:" $projectRoot | Out-Null
        $substCreated = $LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath "$substDrive`:\")
    }
    if ($substCreated) {
        try {
            $mappedOutput = "$substDrive`:\dist\$([IO.Path]::GetFileName($fixtureOutput))"
            Assert-ThrowsMatching { Assert-SafeOutputPath $mappedOutput $projectRoot } 'strict subdirectory' 'SUBST output alias'
            Assert-Exists (Join-Path $fixtureOutput 'keep-me.txt') 'SUBST rejection sentinel'
            Write-Host 'SUBST output alias rejection passed.'
        } finally {
            & subst.exe "$substDrive`:" /D | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Unable to remove SUBST contract drive: $substDrive`:" }
        }
    } else {
        Write-Host 'SUBST fixture unavailable; strict dist-root policy fallback passed.'
    }
    Assert-Throws { Assert-PathContainedBy (Join-Path $fixtureOutput '..\escape') $fixtureOutput } 'output containment escape'
} finally {
    Remove-DistContractFixture $cleanTemp
}

$externalTemp = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixExternalOutputContract-' + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Force -Path $externalTemp | Out-Null
    $externalSentinel = Join-Path $externalTemp 'must-survive.txt'
    [IO.File]::WriteAllText($externalSentinel, 'external-output-sentinel')
    $externalHash = (Get-FileHash -LiteralPath $externalSentinel -Algorithm SHA256).Hash
    Assert-ThrowsMatching { Assert-SafeOutputPath $externalTemp $projectRoot } 'strict subdirectory' 'external temp output directory'
    if ((Get-FileHash -LiteralPath $externalSentinel -Algorithm SHA256).Hash -ne $externalHash) {
        throw 'External temp output sentinel changed while rejecting the path.'
    }
    $projectSentinelHash = (Get-FileHash -LiteralPath (Join-Path $projectRoot 'pom.xml') -Algorithm SHA256).Hash
    Assert-ThrowsMatching { Assert-SafeOutputPath (Join-Path $projectRoot 'target\installer-contract-outside') $projectRoot } 'strict subdirectory' 'local non-dist output directory'
    if ((Get-FileHash -LiteralPath (Join-Path $projectRoot 'pom.xml') -Algorithm SHA256).Hash -ne $projectSentinelHash) {
        throw 'Repository sentinel changed while rejecting a local non-dist path.'
    }
} finally {
    Remove-TempContractFixture $externalTemp
}

# Create real Windows junctions so cleanup and default-build staging defenses are executed, not inferred from text.
$junctionTemp = Join-Path $contractDistRoot ('installer-contract-junction-' + [guid]::NewGuid().ToString('N'))
$ancestorJunction = Join-Path $junctionTemp 'ancestor-link'
$candidateJunction = Join-Path $junctionTemp 'candidate-output\work'
$stagingWorkJunction = Join-Path $junctionTemp 'staging-ancestor-output\work'
$stagingCandidateOutput = Join-Path $junctionTemp 'staging-candidate-output'
$stagingCandidateNames = @('input', 'tray-classes', 'app-image', 'payload')
$stagingCandidateJunctions = @($stagingCandidateNames | ForEach-Object { Join-Path $stagingCandidateOutput "work\$_" })
try {
    $ancestorTarget = Join-Path $junctionTemp 'ancestor-target'
    $ancestorTargetOutput = Join-Path $ancestorTarget 'output'
    $candidateOutput = Join-Path $junctionTemp 'candidate-output'
    $candidateTarget = Join-Path $junctionTemp 'candidate-target'
    $stagingWorkTarget = Join-Path $junctionTemp 'staging-work-target'
    $stagingAncestorOutput = Join-Path $junctionTemp 'staging-ancestor-output'
    New-Item -ItemType Directory -Force -Path (Join-Path $ancestorTargetOutput 'work'), $candidateOutput, $candidateTarget,
        $stagingWorkTarget, $stagingAncestorOutput, (Join-Path $stagingCandidateOutput 'work') | Out-Null
    New-Item -ItemType File -Force -Path (Join-Path $ancestorTargetOutput 'work\must-survive.txt'),
        (Join-Path $candidateTarget 'must-survive.txt'), (Join-Path $stagingWorkTarget 'must-survive.txt') | Out-Null
    $stagingCandidateTargets = @{}
    foreach ($name in $stagingCandidateNames) {
        $target = Join-Path $junctionTemp "staging-$name-target"
        New-Item -ItemType Directory -Force -Path $target | Out-Null
        New-Item -ItemType File -Force -Path (Join-Path $target 'must-survive.txt') | Out-Null
        $stagingCandidateTargets[$name] = $target
    }
    try {
        New-Item -ItemType Junction -Path $ancestorJunction -Target $ancestorTarget -ErrorAction Stop | Out-Null
        New-Item -ItemType Junction -Path $candidateJunction -Target $candidateTarget -ErrorAction Stop | Out-Null
        New-Item -ItemType Junction -Path $stagingWorkJunction -Target $stagingWorkTarget -ErrorAction Stop | Out-Null
        foreach ($name in $stagingCandidateNames) {
            $stagingJunction = Join-Path $stagingCandidateOutput "work\$name"
            New-Item -ItemType Junction -Path $stagingJunction -Target $stagingCandidateTargets[$name] -ErrorAction Stop | Out-Null
        }
    } catch {
        throw "Unable to create required junction fixtures; reparse-point protection was not validated or skipped: $($_.Exception.Message)"
    }
    Assert-ThrowsMatching { Clear-KnownOutput (Join-Path $ancestorJunction 'output') $projectRoot } 'reparse point' 'ancestor junction output path'
    Assert-Exists (Join-Path $ancestorTargetOutput 'work\must-survive.txt') 'ancestor junction target sentinel'
    Assert-ThrowsMatching { Clear-KnownOutput $candidateOutput $projectRoot } 'reparse point' 'known output item junction'
    Assert-Exists (Join-Path $candidateTarget 'must-survive.txt') 'candidate junction target sentinel'
    Assert-ThrowsMatching {
        Remove-SafeGeneratedPath (Join-Path $stagingAncestorOutput 'work\input') $stagingAncestorOutput $projectRoot
    } 'reparse point' 'default-build work ancestor junction'
    Assert-Exists (Join-Path $stagingWorkTarget 'must-survive.txt') 'default-build work junction target sentinel'
    foreach ($name in $stagingCandidateNames) {
        Assert-ThrowsMatching {
            Remove-SafeGeneratedPath (Join-Path $stagingCandidateOutput "work\$name") $stagingCandidateOutput $projectRoot
        } 'reparse point' "default-build $name junction"
        Assert-Exists (Join-Path $stagingCandidateTargets[$name] 'must-survive.txt') "default-build $name junction target sentinel"
    }
} finally {
    foreach ($junction in @($stagingCandidateJunctions + @($stagingWorkJunction, $candidateJunction, $ancestorJunction))) {
        if (Test-Path -LiteralPath $junction) { [IO.Directory]::Delete($junction) }
    }
    Remove-DistContractFixture $junctionTemp
}

$installer = Join-Path $scriptRoot 'installer\Farm.iss'
$configure = Join-Path $scriptRoot 'service\Farm-Configure.ps1'
$install = Join-Path $scriptRoot 'service\install-service.ps1'
$uninstall = Join-Path $scriptRoot 'service\uninstall-service.ps1'
$capture = Join-Path $scriptRoot 'service\capture-upgrade-state.ps1'
$migrate = Join-Path $scriptRoot 'service\migrate-legacy-data.ps1'
$portableConfig = Join-Path $scriptRoot 'service\portable-config.ps1'
$dataAcl = Join-Path $scriptRoot 'service\data-acl.ps1'
$recovery = Join-Path $scriptRoot 'service\recover-upgrade-state.ps1'
$serviceXml = Join-Path $scriptRoot 'service\service-xml.ps1'
$control = Join-Path $scriptRoot 'service\service-control.ps1'
$tray = Join-Path $projectRoot 'src\main\java\com\example\farm\tray\FabMatrixTray.java'
$backup = Join-Path $projectRoot 'scripts\local-backup.ps1'
$restore = Join-Path $projectRoot 'scripts\local-restore.ps1'
$baseConfig = Join-Path $projectRoot 'src\main\resources\application.yaml'
$devConfig = Join-Path $projectRoot 'src\main\resources\application-dev.yaml'
$localConfig = Join-Path $projectRoot 'src\main\resources\application-local.yaml'
$dockerfile = Join-Path $projectRoot 'Dockerfile'
$compose = Join-Path $projectRoot 'docker-compose.server.yml'
$serverEnv = Join-Path $projectRoot '.env.server.example'

# Parse every Windows PowerShell script without executing service/firewall operations.
Get-ChildItem -LiteralPath $scriptRoot -Recurse -File -Filter '*.ps1' | ForEach-Object {
    $tokens = $null
    $errors = $null
    $source = Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8
    [void][System.Management.Automation.Language.Parser]::ParseInput($source, $_.FullName, [ref]$tokens, [ref]$errors)
    if ($errors.Count -ne 0) { throw "PowerShell AST parse failed: $($_.FullName): $($errors[0].Message)" }
}

Assert-Contains $installer 'AppName=FabMatrix Server' 'installer brand'
Assert-Contains $installer 'DefaultDirName=\{autopf\}\\FabMatrix' 'default install directory'
Assert-Contains $installer 'ExtractFileDrive\(ExpandConstant\(''\{sd\}''\)\)' 'valid system drive constant'
Assert-NotContains $installer '\{sysdrive\}' 'invalid Inno system drive constant'
Assert-Contains $installer 'commonappdata.*FabMatrix|GetDataRoot' 'system drive data root'
Assert-Contains $installer '\{app\}.*data|Result := AddBackslash.*data' 'non-system drive data root'
Assert-Contains $installer 'uninsneveruninstall' 'data retention declaration'
Assert-NotContains $installer 'UninstallDelete[\s\S]*(commonappdata|\\data|\\database|\\files|\\backups)' 'recursive data deletion'
Assert-Contains $installer 'capture-upgrade-state\.ps1|PreserveExistingState|StateFile' 'upgrade service state handoff'
$installerText = Get-Content -LiteralPath $installer -Raw
$runSection = [regex]::Match($installerText, '(?ms)^\[Run\]\s*(.*?)(?=^\[)')
if (-not $runSection.Success -or $runSection.Groups[1].Value -match 'install-service\.ps1') {
    throw 'Service installation must not run from the unchecked Inno [Run] section.'
}
Assert-Contains $installer 'CurStepChanged|InstallServiceChecked' 'checked post-copy service install hook'
Assert-Contains $installer 'Exec\(PowerShell, Parameters.*ResultCode|ResultCode <> 0' 'service install exit-code check'
Assert-Contains $installer 'RestoreExistingServiceState|recover-upgrade-state\.ps1' 'upgrade failure state recovery'
Assert-Contains $installer 'RaiseException' 'service install failure abort'
Assert-Contains $installer 'Name: "tray_autostart"[^\r\n]*Flags: checkedonce' 'default tray autostart choice'
foreach ($config in @($baseConfig, $devConfig, $localConfig)) {
    Assert-Contains $config '\$\{SERVER_PORT:28081\}' "default SERVER_PORT for $config"
}
Assert-Contains $dockerfile 'EXPOSE 28081' 'Dockerfile default port'
Assert-Contains $compose 'SERVER_PORT: \$\{SERVER_PORT:-28081\}' 'Compose container port override'
Assert-Contains $compose 'FARM_HTTP_PORT:-28081.*SERVER_PORT:-28081' 'Compose host/container port mapping'
Assert-Contains $compose '127\.0\.0\.1:\$\$\{SERVER_PORT:-28081\}/actuator/health' 'Compose healthcheck port'
Assert-Contains $serverEnv 'FARM_HTTP_PORT=28081' 'Server Edition host port default'
Assert-Contains $installer '28081' 'installer default port metadata'

Assert-Contains $configure 'address: 0\.0\.0\.0' 'server address'
Assert-Contains $configure '\[int\]\$Port = 28081' 'new installer default port'
Assert-Contains $configure 'shutdown: graceful' 'graceful shutdown'
Assert-Contains $configure 'enabled: false' 'scheduler and Swagger disabled'
Assert-Contains $configure "portableDataRoot = '\$\{FARM_DATA_DIR\}'" 'portable data root placeholder'
Assert-Contains $configure 'portableDataRoot/database/farm\.db|database/farm\.db' 'portable SQLite path'
Assert-Contains $configure 'portableDataRoot/files|portableFiles' 'portable files path'
Assert-Contains $install '<id>FabMatrixServer</id>|New-FabMatrixServiceXml' 'fixed service name'
Assert-Contains $install 'Private|New-NetFirewallRule' 'private firewall rule'
Assert-Contains $install 'Get-FabMatrixEffectivePort|ConvertTo-FabMatrixPort|SERVER_PORT' 'effective port resolution'
Assert-Contains $install 'Write-FabMatrixEffectivePortMetadata[^\r\n]*EffectivePort \$effectivePort' 'tray metadata uses resolved effective port'
Assert-Contains $install 'New-FabMatrixServiceXml[^\r\n]*Port \$effectivePort' 'WinSW service uses resolved effective port'
Assert-Contains $install 'Set-PrivateFirewallRule -EffectivePort \$effectivePort' 'firewall uses resolved effective port'
Assert-Contains $install 'SelfTestPortConfig|Test-FabMatrixPortConfig' 'installer port parser self-test'
Assert-Contains $uninstall '\[int\]\$Port = 28081' 'new uninstall default port'
Assert-NotContains $install 'Profile\s+Private[^\r\n]*Public|Profile.*Public' 'public firewall profile'
Assert-Contains $install 'Set-FabMatrixDataAcl' 'shared data ACL policy'
Assert-Contains $install 'Set-FabMatrixRuntimeMetadataAcl' 'runtime metadata ACL application'
Assert-Contains $dataAcl 'S-1-5-32-545|S-1-5-11' 'explicit broad ACL removal identities'
Assert-Contains $dataAcl '/remove:g|/remove:d' 'explicit broad ACL removal operations'
Assert-Contains $dataAcl 'service-state|UsersReadOnly' 'runtime metadata Users read-only ACL intent'
Assert-Contains $capture 'StartMode|WasRunning|Stop-ServiceReliably' 'upgrade state capture'
Assert-Contains $migrate 'data\\farm\.db|uploads|pre-fabmatrix-migration|New data root is already initialized' 'legacy migration mapping and guard'
Assert-Contains $migrate 'Set-FabMatrixPortableConfig' 'legacy config normalization'
Assert-Contains $portableConfig 'datasource|FARM_DATA_DIR|storage-path|logs/farm\.log' 'portable path insertion and normalization'
Assert-Contains $recovery 'StartMode|WasRunning|sc\.exe config' 'best-effort original service recovery'
Assert-Contains $serviceXml '&quot;|ConvertTo-XmlText|stoptimeout>30sec' 'quoted escaped WinSW arguments and stop timeout'
Assert-Contains $serviceXml '<env name="SERVER_PORT" value="\$portXml" />' 'WinSW service-level effective port environment'
Assert-Contains $control 'ValidateSet' 'tray service actions'
Assert-Contains $tray 'SystemTray|FabMatrixTray' 'pure JDK tray controller'
Assert-Contains $tray 'service-state.*effective-port\.txt|PORT_METADATA_DIRECTORY.*PORT_METADATA_FILE' 'tray installed effective port metadata path'
Assert-Contains $tray 'tray\.lock|tryLock' 'single instance'
Assert-Contains $tray 'EncodedCommand|self-test-uac-command' 'encoded UAC command construction'
Assert-NotContains $tray 'dataRoot\.resolve\("backups"\)' 'ordinary tray cannot browse private backups'
Assert-Contains $uninstall "Get-Process -Name 'FabMatrixTray', 'FarmTray'" 'uninstall closes only tray executables'
Assert-NotContains $build "'\-Xrs'" 'service launcher must receive console control events'
Assert-Contains $build "'--win-console'" 'console main launcher'
Assert-Contains $build 'Remove-SafeGeneratedPath \$inputPath|inputFiles.Count -ne 2' 'safe clean and exclusive jpackage input'
Assert-Contains $build 'maintenance.*local-backup\.ps1|local-backup\.ps1.*maintenance' 'maintenance payload scripts'
Assert-Contains $build 'System\.Diagnostics\.ProcessStartInfo|Invoke-CapturedProcess' 'safe native Java metadata capture'
Assert-Contains $build "releaseInfo -match 'NativeCommandError\|CategoryInfo'" 'release metadata error pollution guard'
Assert-NotContains $build '& \$javaExe -version 2>&1|& java -XshowSettings:properties -version 2>&1' 'PowerShell native error-stream Java capture'
Assert-Contains $build 'AssetsDir|installerIconPath|serverIconPath|trayIconPath|trayPngPath' 'fixed replaceable brand asset directory'
Assert-Contains $build 'if \(\$CleanOutput\)[\s\S]*Clear-KnownOutput' 'explicit safe output cleanup switch'
Assert-Contains $build 'if \(-not \$NoClean\)[\s\S]*maven @\(''clean''\)' 'preserved NoClean Maven semantics'
Assert-Contains $installer 'SetupIconFile=\{#AssetsDir\}\\installer\.ico' 'installer icon resource'
Assert-Contains $tray 'getResourceAsStream\("/tray\.png"\)' 'classpath tray icon loading'
Assert-Contains $tray 'selectBrandIcon\(loadPackagedBrandIcon\(\)\)' 'packaged tray icon selection'
Assert-Contains $tray 'packagedIcon != null \? packagedIcon : createFallbackBrandIcon\(\)' 'Java2D tray icon fallback selection'
Assert-Contains $tray 'private static Image createFallbackBrandIcon\(\)' 'Java2D tray icon fallback implementation'
Assert-Contains $tray '--self-test-brand-icon|runBrandIconSelfTest' 'headless brand icon self-test'
Assert-Contains $tray 'Microsoft YaHei UI|Microsoft YaHei|SimSun|Noto Sans CJK' 'system Chinese font candidates'
Assert-Contains $tray 'canDisplayUpTo|CHINESE_TRAY_TEXTS' 'complete Chinese tray text display check'
Assert-NotContains $tray 'import java\.awt\.(PopupMenu|MenuItem);|new (PopupMenu|MenuItem)\(' 'native AWT tray menu components'
Assert-Contains $tray 'JPopupMenu|JMenuItem' 'Swing tray menu components'
Assert-Contains $tray 'addMouseListener|MouseAdapter|isPopupTrigger' 'tray popup mouse listener'
Assert-Contains $tray 'constrainPopupLocation|getScreenDevices|getScreenInsets' 'multi-monitor usable screen bounds positioning'
Assert-Contains $tray 'JWindow|Window\.Type\.POPUP|disposeMenuAnchor|anchor\.dispose\(\)' 'disposable lightweight menu anchor'
Assert-NotContains $tray 'addWindowFocusListener|windowLostFocus' 'focus-racing anchor window listener'
Assert-Contains $tray 'addAWTEventListener|removeAWTEventListener|KeyboardFocusManager' 'focus-safe popup dismissal lifecycle'
Assert-Contains $tray 'isPopupInteraction|hidePopupMenuIfFocusOutside|isMenuWindow' 'inside-click and focus transition guards'
Assert-Contains $tray 'validateSwingMenuModel|menuItemTexts|invokeAndWait|outsideClick' 'headless Swing menu model and click validation'
Assert-Contains $tray '--self-test-menu-font|runMenuFontSelfTest' 'headless Swing menu font self-test'
Assert-Contains $build "'--add-modules'.*'ALL-MODULE-PATH'" 'jpackage complete JDK runtime module path'
Assert-Contains $backup 'database.*farm\.db|config.*application\.yml|files' 'backup coverage'
Assert-Contains $backup '\$\{FARM_DATA_DIR\}' 'portable backup config'
Assert-Contains $restore 'Move-Item|restore-staging|installedNames|rollback was attempted' 'replacement restore and rollback'
Assert-Contains $restore 'Set-FabMatrixDataAcl' 'restore reapplies data ACL policy'
Assert-Contains $restore "Import-MaintenanceHelper 'data-acl\.ps1'" 'packaged restore ACL helper'

# Generate and parse WinSW XML for paths with spaces; this does not install a service.
$portSelfTestOutput = (& $install -SelfTestPortConfig | Out-String)
if ($portSelfTestOutput -notmatch 'parser and metadata self-test passed') {
    throw 'Installer effective port parser/metadata self-test did not complete.'
}
. $serviceXml
$autoStopped = Resolve-FabMatrixServicePlan -PreservedState ([pscustomobject]@{
        ExistingServiceName = 'Farm'; StartMode = 'Auto'; WasRunning = $false
    }) -AutoStartRequested $false
$manualRunning = Resolve-FabMatrixServicePlan -PreservedState ([pscustomobject]@{
        ExistingServiceName = 'FabMatrixServer'; StartMode = 'Manual'; WasRunning = $true
    }) -AutoStartRequested $false
$disabled = Resolve-FabMatrixServicePlan -PreservedState ([pscustomobject]@{
        ExistingServiceName = 'FabMatrixServer'; StartMode = 'Disabled'; WasRunning = $false
    }) -AutoStartRequested $true
if ($autoStopped.StartMode -ne 'Automatic' -or $autoStopped.ShouldStart -or
    $manualRunning.StartMode -ne 'Manual' -or -not $manualRunning.ShouldStart -or
    -not $disabled.DisableAfterInstall -or $disabled.ShouldStart) {
    throw 'Upgrade service StartType/running-state plan was not preserved.'
}
$contractEffectivePort = 8080
$xmlText = New-FabMatrixServiceXml -InstallDir 'D:\Program Files\FabMatrix' -DataRoot 'D:\Program Files\FabMatrix\data' -Port $contractEffectivePort -StartMode Automatic
$xmlDocument = [xml]$xmlText
$expectedArguments = '--spring.config.additional-location="file:D:/Program Files/FabMatrix/data/config/"'
if ([string]$xmlDocument.service.arguments -ne $expectedArguments -or $xmlText -notmatch '&quot;') {
    throw 'WinSW XML dry-run did not preserve quoted Program Files config URL.'
}
$servicePortEnvironment = @($xmlDocument.service.env | Where-Object { $_.name -eq 'SERVER_PORT' })
if ($servicePortEnvironment.Count -ne 1 -or [int]$servicePortEnvironment[0].value -ne $contractEffectivePort) {
    throw 'WinSW XML did not freeze the resolved legacy effective port.'
}
if ([int](([string]$xmlDocument.service.stoptimeout) -replace '\D', '') -lt 20) {
    throw 'WinSW stop timeout is shorter than graceful shutdown timeout.'
}
$specialXml = New-FabMatrixServiceXml -InstallDir 'D:\Program Files\Fab&Matrix' -DataRoot 'D:\Program Files\Fab&Matrix\data' -Port 28081
if ($specialXml -notmatch 'Fab&amp;Matrix' -or ([xml]$specialXml).service.arguments -notmatch 'Fab&Matrix') {
    throw 'WinSW XML dry-run did not XML-escape special path characters.'
}

# Generate a disposable Local config and verify it contains no source-machine data path.
$configTemp = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixConfigContract-' + [guid]::NewGuid().ToString('N'))
try {
    $configData = Join-Path $configTemp 'D Program Files\FabMatrix\data'
    $configDir = Join-Path $configData 'config'
    & $configure -Edition Local -ConfigDir $configDir -DataRoot $configData -Force | Out-Null
    $generatedConfig = Join-Path $configDir 'application.yml'
    Assert-Contains $generatedConfig '\$\{FARM_DATA_DIR\}/database/farm\.db' 'generated portable SQLite path'
    Assert-Contains $generatedConfig '\$\{FARM_DATA_DIR\}/files' 'generated portable files path'
    Assert-Contains $generatedConfig 'port: 28081' 'generated default server port'
    if ((Get-Content -LiteralPath $generatedConfig -Raw).Contains(($configData -replace '\\', '/'))) {
        throw 'Generated Local config retained its source-machine absolute path.'
    }
} finally {
    if (Test-Path -LiteralPath $configTemp) { Remove-Item -LiteralPath $configTemp -Recurse -Force }
}

# Exercise legacy migration only against disposable temp fixtures.
$migrationTemp = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixMigrationContract-' + [guid]::NewGuid().ToString('N'))
try {
    $legacy = Join-Path $migrationTemp 'legacy'
    $newData = Join-Path $migrationTemp 'new-data'
    New-Item -ItemType Directory -Force -Path (Join-Path $legacy 'data'), (Join-Path $legacy 'uploads'), (Join-Path $legacy 'config'),
        (Join-Path $newData 'database'), (Join-Path $newData 'files'), (Join-Path $newData 'config') | Out-Null
    [IO.File]::WriteAllText((Join-Path $legacy 'data\farm.db'), 'fixture')
    [IO.File]::WriteAllText((Join-Path $legacy 'uploads\sample.gcode'), 'G1 X1')
    $legacyConfigText = @"
# Farm Windows configuration. Generated by Farm-Configure.ps1.
spring:
  profiles:
    active: local
server:
  port: 8181
logging:
  file:
    path: 'C:/ProgramData/Farm/logs'
    name: farm
farm:
  local:
    storage-path: 'C:/ProgramData/Farm/uploads'
  security:
    cors-allowed-origins: 'http://legacy.example:5173'
    first-admin-setup-enabled: true
jwt:
  secret-key: 'legacy-jwt-secret'
admin:
  secret-key: 'legacy-admin-secret'
"@
    [IO.File]::WriteAllText((Join-Path $legacy 'config\application.yml'), $legacyConfigText)
    & $migrate -DataRoot $newData -LegacyDataRoot $legacy | Out-Null
    Assert-Exists (Join-Path $newData 'database\farm.db') 'migrated database'
    Assert-Exists (Join-Path $newData 'files\sample.gcode') 'migrated uploads'
    $migratedConfig = Join-Path $newData 'config\application.yml'
    Assert-Contains $migratedConfig 'jdbc:sqlite:\$\{FARM_DATA_DIR\}/database/farm\.db' 'inserted portable migrated database URL'
    Assert-Contains $migratedConfig '\$\{FARM_DATA_DIR\}/files' 'portable migrated files path'
    Assert-Contains $migratedConfig '\$\{FARM_DATA_DIR\}/logs/farm\.log' 'portable migrated logs path'
    Assert-Contains $migratedConfig 'port: 8181' 'preserved legacy port'
    Assert-Contains $migratedConfig 'http://legacy\.example:5173' 'preserved legacy CORS'
    Assert-Contains $migratedConfig 'legacy-jwt-secret' 'preserved legacy JWT secret'
    Assert-Contains $migratedConfig 'legacy-admin-secret' 'preserved legacy admin secret'
    $migrationBackup = Get-ChildItem -LiteralPath (Join-Path $legacy 'backups') -Directory -Filter 'pre-fabmatrix-migration-*' | Select-Object -First 1
    if ($null -eq $migrationBackup) { throw 'Legacy migration backup was not created.' }

    [IO.File]::WriteAllText((Join-Path $newData 'database\farm.db'), 'new-data-wins')
    & $migrate -DataRoot $newData -LegacyDataRoot $legacy | Out-Null
    if ([IO.File]::ReadAllText((Join-Path $newData 'database\farm.db')) -ne 'new-data-wins') {
        throw 'Initialized new data root was overwritten by legacy migration.'
    }
} finally {
    if (Test-Path -LiteralPath $migrationTemp) { Remove-Item -LiteralPath $migrationTemp -Recurse -Force }
}

# Exercise the real Windows ACL policy only against disposable temp directories.
$aclTemp = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixAclContract-' + [guid]::NewGuid().ToString('N'))
$currentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
try {
    foreach ($relative in @('config', 'database', 'backups', 'files', 'logs')) {
        $path = Join-Path $aclTemp $relative
        New-Item -ItemType Directory -Force -Path $path | Out-Null
        # Keep the non-elevated test owner able to inspect/clean the fixture; production calls run elevated.
        & icacls.exe $path '/grant' "*$currentSid`:(OI)(CI)F" '*S-1-5-32-545:(OI)(CI)F' '*S-1-5-11:(OI)(CI)F' '/T' '/C' '/Q' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Unable to seed disposable ACL fixture: $path" }
    }
    . $dataAcl
    Set-FabMatrixDataAcl -DataRoot $aclTemp

    $aclInstallDir = Join-Path $aclTemp 'install'
    $runtimeMetadataDir = Join-Path $aclInstallDir 'service-state'
    $runtimeMetadataPath = Join-Path $runtimeMetadataDir 'effective-port.txt'
    New-Item -ItemType Directory -Force -Path $runtimeMetadataDir | Out-Null
    [IO.File]::WriteAllText($runtimeMetadataPath, "8080`r`n")
    & icacls.exe $runtimeMetadataDir '/grant' "*$currentSid`:(OI)(CI)F" '*S-1-5-32-545:(OI)(CI)F' '/T' '/C' '/Q' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to seed runtime metadata ACL fixture: $runtimeMetadataDir" }
    Set-FabMatrixRuntimeMetadataAcl -InstallDir $aclInstallDir

    foreach ($relative in @('config', 'database', 'backups')) {
        $rules = (Get-Acl -LiteralPath (Join-Path $aclTemp $relative)).Access
        $sids = @($rules | ForEach-Object {
                try { $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value }
                catch { $_.IdentityReference.Value }
            })
        foreach ($forbiddenSid in @('S-1-5-32-545', 'S-1-5-11', 'S-1-1-0')) {
            if ($sids -contains $forbiddenSid) { throw "Private ACL retained broad principal $forbiddenSid on $relative" }
        }
        foreach ($requiredSid in @('S-1-5-18', 'S-1-5-32-544')) {
            if ($sids -notcontains $requiredSid) { throw "Private ACL is missing $requiredSid on $relative" }
        }
    }
    foreach ($relative in @('files', 'logs')) {
        $usersRule = (Get-Acl -LiteralPath (Join-Path $aclTemp $relative)).Access | Where-Object {
            try { $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value -eq 'S-1-5-32-545' }
            catch { $false }
        } | Select-Object -First 1
        if ($null -eq $usersRule -or ($usersRule.FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::Write) -ne 0) {
            throw "Shared ACL must grant Users read-only access on $relative"
        }
    }
    $runtimeUsersRule = (Get-Acl -LiteralPath $runtimeMetadataPath).Access | Where-Object {
        try { $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value -eq 'S-1-5-32-545' }
        catch { $false }
    } | Select-Object -First 1
    if ($null -eq $runtimeUsersRule -or
        ($runtimeUsersRule.FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::ReadAndExecute) -eq 0 -or
        ($runtimeUsersRule.FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::Write) -ne 0) {
        throw "Runtime effective port metadata must grant Users read/execute without write; actual rights: $($runtimeUsersRule.FileSystemRights)"
    }
    if (([IO.File]::ReadAllText($runtimeMetadataPath)).Trim() -ne '8080') {
        throw 'Runtime metadata ACL application changed the preserved legacy effective port.'
    }
} finally {
    if (Test-Path -LiteralPath $aclTemp) {
        & icacls.exe $aclTemp '/grant' "*$currentSid`:(OI)(CI)F" '/T' '/C' '/Q' | Out-Null
        Remove-Item -LiteralPath $aclTemp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if (-not [string]::IsNullOrWhiteSpace($PayloadPath)) {
    $payload = [IO.Path]::GetFullPath($PayloadPath)
    foreach ($relative in @('FabMatrixServer.exe', 'FabMatrixTray.exe', 'maintenance\local-backup.ps1',
            'maintenance\local-restore.ps1', 'maintenance\data-acl.ps1', 'maintenance\portable-config.ps1',
            'service\capture-upgrade-state.ps1', 'service\data-acl.ps1', 'service\migrate-legacy-data.ps1',
            'service\portable-config.ps1', 'service\recover-upgrade-state.ps1', 'service\service-xml.ps1')) {
        Assert-Exists (Join-Path $payload $relative) "payload $relative"
    }
    $trayJarPath = Join-Path $payload 'app\FabMatrixTray.jar'
    Assert-Exists $trayJarPath 'payload tray JAR'
    $runtimeModulesPath = Join-Path $payload 'runtime\lib\modules'
    Assert-Exists $runtimeModulesPath 'payload runtime module image'
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $settings = (& java -XshowSettings:properties -version 2>&1 | Out-String)
    $ErrorActionPreference = $previousErrorAction
    $match = [regex]::Match($settings, '(?m)^\s*java\.home\s*=\s*(.+?)\s*$')
    if (-not $match.Success) { throw 'Unable to locate the current JDK for payload runtime inspection.' }
    $jimage = Join-Path ($match.Groups[1].Value.Trim()) 'bin\jimage.exe'
    Assert-Exists $jimage 'current JDK jimage tool'
    $runtimeImageListing = (& $jimage list $runtimeModulesPath | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect payload runtime module image: $LASTEXITCODE" }
    $runtimeModules = @([regex]::Matches($runtimeImageListing, '(?m)^Module:\s+(\S+)\s*$') |
            ForEach-Object { $_.Groups[1].Value })
    foreach ($requiredModule in @('java.desktop', 'java.net.http', 'java.sql', 'java.naming',
            'java.management', 'java.instrument', 'jdk.localedata', 'jdk.unsupported')) {
        if ($requiredModule -notin $runtimeModules) {
            throw "Payload runtime is missing required JDK module: $requiredModule"
        }
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $trayArchive = [IO.Compression.ZipFile]::OpenRead($trayJarPath)
    try {
        if ($null -eq $trayArchive.GetEntry('tray.png')) { throw 'Packaged tray JAR does not contain tray.png.' }
    } finally {
        $trayArchive.Dispose()
    }
    $trayLauncherPath = Join-Path $payload 'FabMatrixTray.exe'
    $trayLauncherProcess = Start-Process -FilePath $trayLauncherPath -ArgumentList '--self-test-brand-icon' -Wait -PassThru
    if ($trayLauncherProcess.ExitCode -ne 0) {
        throw "Packaged tray launcher brand icon self-test failed: $($trayLauncherProcess.ExitCode)"
    }
    $trayPortProcess = Start-Process -FilePath $trayLauncherPath -ArgumentList '--self-test-port-config' -Wait -PassThru
    if ($trayPortProcess.ExitCode -ne 0) {
        throw "Packaged tray launcher port config self-test failed: $($trayPortProcess.ExitCode)"
    }
    Write-Host 'FabMatrix packaged tray launcher brand icon self-test passed.'
    $trayMenuFontProcess = Start-Process -FilePath $trayLauncherPath -ArgumentList '--self-test-menu-font' -Wait -PassThru
    if ($trayMenuFontProcess.ExitCode -ne 0) {
        throw "Packaged tray launcher menu font self-test failed: $($trayMenuFontProcess.ExitCode)"
    }
    Write-Host 'FabMatrix packaged tray launcher menu font self-test passed.'
    $payloadJava = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -eq $payloadJava) { throw 'Java is required to execute the packaged tray brand icon self-test.' }
    & $payloadJava.Source '-Djava.awt.headless=true' '-cp' $trayJarPath 'com.example.farm.tray.FabMatrixTray' '--self-test-brand-icon'
    if ($LASTEXITCODE -ne 0) { throw "Packaged tray brand icon self-test failed: $LASTEXITCODE" }
    & $payloadJava.Source '-Djava.awt.headless=true' '-cp' $trayJarPath 'com.example.farm.tray.FabMatrixTray' '--self-test-port-config'
    if ($LASTEXITCODE -ne 0) { throw "Packaged tray port config self-test failed: $LASTEXITCODE" }
    & $payloadJava.Source '-Djava.awt.headless=true' '-cp' $trayJarPath 'com.example.farm.tray.FabMatrixTray' '--self-test-menu-font'
    if ($LASTEXITCODE -ne 0) { throw "Packaged tray menu font self-test failed: $LASTEXITCODE" }
}

if (-not [string]::IsNullOrWhiteSpace($ReleaseInfoPath)) {
    Assert-Exists $ReleaseInfoPath 'release metadata'
    Assert-NotContains $ReleaseInfoPath 'NativeCommandError|CategoryInfo' 'PowerShell native error metadata'
}

if (-not $SkipTrayCompile) {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $settings = (& java -XshowSettings:properties -version 2>&1 | Out-String)
    $ErrorActionPreference = $previousErrorAction
    $match = [regex]::Match($settings, '(?m)^\s*java\.home\s*=\s*(.+?)\s*$')
    if (-not $match.Success) { throw 'Unable to locate Java for tray compilation.' }
    $javaHome = $match.Groups[1].Value.Trim()
    $javac = Join-Path $javaHome 'bin\javac.exe'
    $java = Join-Path $javaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) { throw "Java tools not found: $javaHome" }
    $temp = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixTrayContract-' + [guid]::NewGuid().ToString('N'))
    try {
        New-Item -ItemType Directory -Force -Path $temp | Out-Null
        & $javac '--release' '25' '-encoding' 'UTF-8' '-d' $temp $tray
        if ($LASTEXITCODE -ne 0) { throw "Tray source compilation failed: $LASTEXITCODE" }
        Copy-Item -LiteralPath (Join-Path $assets 'tray.png') -Destination (Join-Path $temp 'tray.png') -Force
        & $java '-Djava.awt.headless=true' '-cp' $temp 'com.example.farm.tray.FabMatrixTray' '--self-test-brand-icon'
        if ($LASTEXITCODE -ne 0) { throw "Tray brand icon self-test failed: $LASTEXITCODE" }
        & $java '-Djava.awt.headless=true' '-cp' $temp 'com.example.farm.tray.FabMatrixTray' '--self-test-port-config'
        if ($LASTEXITCODE -ne 0) { throw "Tray port config self-test failed: $LASTEXITCODE" }
        & $java '-cp' $temp 'com.example.farm.tray.FabMatrixTray' '--self-test-uac-command'
        if ($LASTEXITCODE -ne 0) { throw "Tray UAC command self-test failed: $LASTEXITCODE" }
        & $java '-Djava.awt.headless=true' '-cp' $temp 'com.example.farm.tray.FabMatrixTray' '--self-test-menu-font'
        if ($LASTEXITCODE -ne 0) { throw "Tray menu font self-test failed: $LASTEXITCODE" }
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
    }
}

Write-Host 'FabMatrix Windows installer contract checks passed.'
