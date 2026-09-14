param(
    [switch]$SkipTrayCompile,
    [string]$PayloadPath = '',
    [string]$ReleaseInfoPath = '',
    [string]$ClientRoot = ''
)

$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptRoot '..\..')).Path
$assets = Join-Path $scriptRoot 'assets'
$build = Join-Path $scriptRoot 'build-installer.ps1'
$contractDistRoot = Join-Path $projectRoot 'dist'
$releaseVersionCheck = Join-Path $scriptRoot 'test-release-version.ps1'

& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $releaseVersionCheck -ServerRoot $projectRoot -ClientRoot $ClientRoot
if ($LASTEXITCODE -ne 0) { throw "Release version contract failed: $LASTEXITCODE" }

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

function Find-ContractInnoCompiler {
    $command = Get-Command ISCC.exe -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    foreach ($candidate in @(
            'C:\Program Files (x86)\Inno Setup 6\ISCC.exe',
            'C:\Program Files\Inno Setup 6\ISCC.exe',
            (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'))) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw 'Inno Setup 6 ISCC.exe is required for the uninstall-stage contract compile.'
}

function Remove-InnoCompileContractFixture([string]$Path) {
    $resolvedPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if (-not [string]::Equals([IO.Path]::GetDirectoryName($resolvedPath), $resolvedTemp, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolvedPath) -notmatch '^FabMatrixInnoCompileContract-[0-9a-f]+$') {
        throw "Refusing to remove an unexpected Inno compile fixture: $resolvedPath"
    }
    if (Test-Path -LiteralPath $resolvedPath) {
        Remove-Item -LiteralPath $resolvedPath -Recurse -Force -ErrorAction Stop
    }
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

function Invoke-PackagedLocalStartupProbe([string]$Payload, [string]$ConfigureScript) {
    $probeRoot = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixLocalStartupContract-' + [guid]::NewGuid().ToString('N'))
    $configDir = Join-Path $probeRoot 'config'
    $serverLauncher = Join-Path $Payload 'FabMatrixServer.exe'
    $stdoutPath = Join-Path $probeRoot 'launcher.stdout.log'
    $stderrPath = Join-Path $probeRoot 'launcher.stderr.log'
    $process = $null
    $listener = New-Object System.Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    try {
        New-Item -ItemType Directory -Force -Path $probeRoot, $configDir | Out-Null
        $listener.Start()
        $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
        $listener.Stop()
        $configureArguments = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $ConfigureScript,
            '-Edition', 'Local', '-ConfigDir', $configDir, '-DataRoot', $probeRoot, '-Port', $port, '-Force')
        & powershell.exe @configureArguments | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Unable to generate disposable Local configuration: $LASTEXITCODE" }

        $configLocation = 'file:' + (($configDir -replace '\\', '/').TrimEnd('/') + '/')
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = $serverLauncher
        $startInfo.WorkingDirectory = $Payload
        $startInfo.Arguments = '--spring.config.additional-location="' + $configLocation + '"'
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $startInfo.EnvironmentVariables['FARM_DATA_DIR'] = $probeRoot
        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = $startInfo
        if (-not $process.Start()) { throw "Unable to start packaged server launcher: $serverLauncher" }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()

        $health = $null
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            Start-Sleep -Milliseconds 500
            if ($process.HasExited) { break }
            try {
                $health = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2
                if ($health.StatusCode -eq 200) { break }
            } catch { }
        }
        if ($null -eq $health -or $health.StatusCode -ne 200) {
            $exitCode = if ($process.HasExited) { $process.ExitCode } else { 'running' }
            $stderr = if ($process.HasExited) { $stderrTask.GetAwaiter().GetResult() } else { '' }
            throw "Packaged Local server did not become healthy (exit=$exitCode). $stderr"
        }

        $logPath = Join-Path $probeRoot 'logs\farm.log'
        for ($attempt = 0; $attempt -lt 20 -and -not (Test-Path -LiteralPath $logPath -PathType Leaf); $attempt++) {
            Start-Sleep -Milliseconds 250
        }
        if (-not (Test-Path -LiteralPath $logPath -PathType Leaf)) {
            throw "Packaged Local server was healthy but did not write the expected log: $logPath"
        }
        if (Test-Path -LiteralPath (Join-Path $probeRoot 'logs\logs')) {
            throw 'Packaged Local server created a duplicated logs directory.'
        }
        if (@(Get-ChildItem -LiteralPath $probeRoot -Recurse -File -Filter '*.log.log' -ErrorAction Stop).Count -gt 0) {
            throw 'Packaged Local server created a duplicated .log.log file.'
        }
        Write-Host "Packaged Local server startup and health probe passed; log: $logPath"
    } finally {
        if ($null -ne $listener) { $listener.Stop() }
        if ($null -ne $process) {
            if (-not $process.HasExited) {
                $process.Kill()
                $process.WaitForExit()
            }
            $process.Dispose()
        }
        if (Test-Path -LiteralPath $probeRoot) { Remove-Item -LiteralPath $probeRoot -Recurse -Force -ErrorAction SilentlyContinue }
        foreach ($path in @($stdoutPath, $stderrPath)) {
            if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue }
        }
    }
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
    foreach ($knownFile in @('FabMatrix-Server-Setup-1.0.0.exe', 'FabMatrixServer-Setup.exe', 'Farm-Setup.exe', 'checksums.txt', 'release-info.txt', 'README-Windows.md', 'acceptance-install.log')) {
        New-Item -ItemType File -Force -Path (Join-Path $fixtureOutput $knownFile) | Out-Null
    }
    New-Item -ItemType File -Force -Path (Join-Path $fixtureOutput 'keep-me.txt') | Out-Null
    Clear-KnownOutput $fixtureOutput $projectRoot
    foreach ($knownPath in @('work', 'precommit-check', 'FabMatrix-Server-Setup-1.0.0.exe', 'FabMatrixServer-Setup.exe', 'Farm-Setup.exe', 'checksums.txt', 'release-info.txt', 'README-Windows.md', 'acceptance-install.log')) {
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
$purge = Join-Path $scriptRoot 'service\purge-local-data.ps1'
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
$prodConfig = Join-Path $projectRoot 'src\main\resources\application-prod.yaml'
$logback = Join-Path $projectRoot 'src\main\resources\logback-spring.xml'
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
Assert-Contains $installer 'InitializeUninstall' 'uninstall mode hook'
Assert-Contains $installer 'MB_YESNOCANCEL|IDCANCEL' 'interactive uninstall cancellation'
Assert-Contains $installer 'ConfirmCompleteUninstall|PurgeDataRequested' 'complete uninstall second confirmation'
Assert-Contains $installer 'MB_YESNO or MB_DEFBUTTON2' 'irreversible confirmation defaults to No'
Assert-Contains $installer '/PURGEDATA=CONFIRM' 'explicit purge command-line confirmation'
Assert-Contains $installer 'LegacyDataRoot := ExpandConstant|DiagnosticsRoot := ExpandConstant' 'complete uninstall legacy and diagnostics warning paths'
Assert-Contains $installer 'LegacyDataRoot := ExpandConstant\(''\{commonappdata\}\\Farm''\)' 'controlled legacy root argument'
Assert-Contains $installer 'DiagnosticsRoot := ExpandConstant\(''\{commonappdata\}\\FabMatrix''\)' 'controlled diagnostics root argument'
Assert-Contains $installer '-LegacyDataRoot.*-DiagnosticsRoot' 'uninstall receives legacy and diagnostics arguments'
Assert-Contains $installer 'UninstallSilent[\s\S]*PurgeDataRequested|/SILENT never implies data removal' 'silent uninstall retains data'
Assert-NotContains $installer 'WizardSilent' 'installer-only silent API in uninstall code'
Assert-NotContains $installer '(?m)^\[UninstallRun\]' 'unchecked UninstallRun cleanup hook'
Assert-Contains $installer 'CurUninstallStepChanged[\s\S]*usUninstall[\s\S]*RunUninstallCleanupChecked' 'checked uninstall-stage callback'
Assert-Contains $installer 'UninstallCleanupExecuted[\s\S]*duplicate callback ignored' 'single-execution uninstall guard'
Assert-Contains $installer 'Exec\(PowerShell, Parameters.*ewWaitUntilTerminated, ResultCode\)[\s\S]*ResultCode <> 0' 'synchronous uninstall exit-code check'
Assert-Contains $installer 'if not UninstallSilent then[\s\S]*MsgBox\(FailureMessage[\s\S]*RaiseException' 'interactive-only failure message and abort'
Assert-Contains $installer "Parameters := Parameters \+ ' -Silent'" 'silent state passed to uninstall script'
Assert-Contains $installer 'capture-upgrade-state\.ps1|PreserveExistingState|StateFile' 'upgrade service state handoff'
Assert-Contains $installer 'capture-upgrade-state\.ps1[\s\S]*InstallDir[\s\S]*\{app\}' 'upgrade preflight receives the target install directory'
Assert-Contains $installer 'PrepareToInstall[\s\S]*Result := ' 'upgrade preflight aborts before file copy'
Assert-Contains $installer '升级安装预检' 'upgrade preflight has a clear Chinese failure message'
$installerText = Get-Content -LiteralPath $installer -Raw
$runSection = [regex]::Match($installerText, '(?ms)^\[Run\]\s*(.*?)(?=^\[)')
if (-not $runSection.Success -or $runSection.Groups[1].Value -match 'install-service\.ps1') {
    throw 'Service installation must not run from the unchecked Inno [Run] section.'
}
$innoCompileRoot = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixInnoCompileContract-' + [guid]::NewGuid().ToString('N'))
try {
    $innoPayload = Join-Path $innoCompileRoot 'payload'
    $innoService = Join-Path $innoPayload 'service'
    $innoOutput = Join-Path $innoCompileRoot 'output'
    [IO.Directory]::CreateDirectory($innoService) | Out-Null
    [IO.Directory]::CreateDirectory($innoOutput) | Out-Null
    [IO.File]::WriteAllText((Join-Path $innoPayload 'contract-placeholder.txt'), 'isolated Inno compile fixture')
    [IO.File]::WriteAllText((Join-Path $innoService 'capture-upgrade-state.ps1'), '# isolated compile fixture')
    $innoCompiler = Find-ContractInnoCompiler
    & $innoCompiler '/Qp' '/DAppVersion=1.0.0' "/DPayloadDir=$innoPayload" "/DOutputDir=$innoOutput" "/DAssetsDir=$assets" $installer
    if ($LASTEXITCODE -ne 0) { throw "Inno uninstall-stage contract compile failed: $LASTEXITCODE" }
    Assert-Exists (Join-Path $innoOutput 'FabMatrix-Server-Setup-1.0.0.exe') 'compiled isolated Inno installer'
    Write-Host 'FabMatrix isolated Inno uninstall-stage compile passed.'
} finally {
    Remove-InnoCompileContractFixture $innoCompileRoot
}
Assert-Contains $installer 'CurStepChanged|InstallServiceChecked' 'checked post-copy service install hook'
Assert-Contains $installer 'Exec\(PowerShell, Parameters.*ResultCode|ResultCode <> 0' 'service install exit-code check'
Assert-Contains $installer 'RestoreExistingServiceState|recover-upgrade-state\.ps1' 'upgrade failure state recovery'
Assert-Contains $installer 'RaiseException' 'service install failure abort'
Assert-Contains $installer 'Name: "tray_autostart"[^\r\n]*Flags: checkedonce' 'default tray autostart choice'
Assert-Contains $installer 'Check: ShouldLaunchTray|ServiceInstallSucceeded' 'tray launch guarded by successful service installation'
Assert-Contains $installer 'service-install\.log|LogPath' 'persistent service installation diagnostics'
Assert-Contains $installer "commonappdata.*FabMatrix.*installer.*service-install\.log" 'ACL-independent service installation diagnostics'
foreach ($config in @($baseConfig, $devConfig, $localConfig)) {
    Assert-Contains $config '\$\{SERVER_PORT:28081\}' "default SERVER_PORT for $config"
}
Assert-Contains $prodConfig 'name: \$\{LOG_FILE_PATH:\.\/logs\/farm\.log\}' 'prod complete LOG_FILE_PATH logging name'
Assert-Contains $logback 'property name="FARM_LOG_FILE" value="\$\{LOG_FILE:-\$\{LOG_PATH:-\.\/logs\}\/farm\.log\}"' 'complete logging.file.name compatible log path'
Assert-Contains $logback '<file>\$\{FARM_LOG_FILE\}</file>' 'main log uses complete resolved file path'
Assert-Contains $logback '<file>\$\{FARM_LOG_FILE\}\.error</file>|<file>\$\{FARM_LOG_FILE\}\.access</file>' 'derived error/access log paths'
Assert-NotContains $logback '\$\{LOG_PATH\}\/\$\{LOG_FILE\}|\$\{LOG_FILE\}\.log' 'log path must not duplicate directory or extension'
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
Assert-Contains $install 'Set-FabMatrixLegacyDefaultPortForUpgrade|Write-FabMatrixAtomicUtf8File' 'legacy default port migration'
Assert-Contains $install 'Set-FabMatrixLegacyDefaultPortForInstallContext[\s\S]*LegacyDataMigrated' 'fresh legacy migration port gate'
Assert-Contains $install 'migrationResultPath[\s\S]*ResultPath[\s\S]*migrationResult\.Migrated' 'structured legacy migration result consumption'
Assert-Contains $install 'after confirmed legacy Farm data migration|during service upgrade' 'legacy port migration context log'
Assert-Contains $install 'server\.port from 8080 to 28081|legacy.*8080.*28081' 'legacy default port migration log'
Assert-Contains $install 'Write-FabMatrixEffectivePortMetadata[^\r\n]*EffectivePort \$effectivePort' 'tray metadata uses resolved effective port'
Assert-Contains $install 'New-FabMatrixServiceXml[^\r\n]*Port \$effectivePort' 'WinSW service uses resolved effective port'
Assert-Contains $install 'Set-PrivateFirewallRule -EffectivePort \$effectivePort' 'firewall uses resolved effective port'
Assert-Contains $install 'SelfTestPortConfig|Test-FabMatrixPortConfig' 'installer port parser self-test'
Assert-Contains $install 'Wait-ServiceAbsent|pending deletion' 'service deletion completion wait'
Assert-Contains $install 'Install-WinSwServiceWithRetry|registration failed after' 'bounded WinSW registration retry'
Assert-Contains $install 'FAILED stage=\$installStage|Detailed log' 'stage-specific service installation failure log'
Assert-Contains $install 'WARNING stage=\$installStage|private firewall rule was not created' 'non-fatal firewall automation warning'
Assert-NotContains $install "configure private firewall rule'[\s\S]*?Wait-ServiceAbsent" 'firewall automation failure must not remove the service'
Assert-Contains $install 'repair legacy data permissions|Repair-FabMatrixInstallerDataAccess' 'legacy ACL repair before configuration read'
Assert-Contains $uninstall '\[int\]\$Port = 28081' 'new uninstall default port'
Assert-NotContains $install 'Profile\s+Private[^\r\n]*Public|Profile.*Public' 'public firewall profile'
Assert-Contains $install 'Set-FabMatrixDataAcl' 'shared data ACL policy'
Assert-Contains $install 'Set-FabMatrixRuntimeMetadataAcl' 'runtime metadata ACL application'
Assert-Contains $dataAcl 'S-1-5-32-545|S-1-5-11' 'explicit broad ACL removal identities'
Assert-Contains $dataAcl '/remove:g|/remove:d' 'explicit broad ACL removal operations'
Assert-Contains $dataAcl 'service-state|UsersReadOnly' 'runtime metadata Users read-only ACL intent'
Assert-Contains $dataAcl 'Repair-FabMatrixInstallerDataAccess|takeown\.exe' 'legacy data ownership repair'
Assert-Contains $dataAcl "@\('config', 'database', 'backups', 'files', 'logs'\)" 'legacy ACL repair fixed directory scope'
Assert-Contains $dataAcl 'Assert-FabMatrixAclTreeSafe|ReparsePoint' 'ACL recursion reparse-point guard'
Assert-Contains $dataAcl "'/SKIPSL'" 'takeown skips symbolic links'
Assert-Contains $dataAcl "'/reset', '/T', '/Q'" 'legacy explicit deny ACL reset'
Assert-NotContains $dataAcl "Repair-FabMatrixInstallerDataAccess[\s\S]*?'/C'" 'legacy ACL repair must not continue past denied children'
Assert-Contains $capture 'StartMode|WasRunning|Stop-ServiceReliably' 'upgrade state capture'
Assert-Contains $capture '\$InstallDir|Get-NormalizedFullPath|Test-PathWithinDirectory' 'upgrade process ownership is bounded to the install directory'
Assert-Contains $capture 'Get-CimInstance Win32_Process|ParentProcessId' 'upgrade preflight inspects the process tree'
Assert-Contains $capture 'CloseMainWindow|Request-FabMatrixTrayExit' 'upgrade preflight requests tray exit before fallback termination'
Assert-Contains $capture 'Wait-FabMatrixProcessesExited|Start-Sleep.*Milliseconds' 'upgrade preflight waits for service and child process exit'
Assert-Contains $capture 'Stop-FabMatrixResidualProcesses|Stop-Process -Id' 'upgrade fallback termination uses verified process IDs'
Assert-Contains $capture 'sc\.exe config.*disabled|Disable-ServiceStartDuringUpgrade|onfailure=restart' 'upgrade preflight blocks WinSW restart races'
Assert-Contains $capture 'Restore-ServiceSnapshotBestEffort|Upgrade preflight failed before file copy' 'upgrade preflight attempts state recovery on failure'
Assert-NotContains $capture "Get-Process -Name 'FabMatrixTray', 'FarmTray'" 'upgrade preflight must not broadly kill same-name trays'
Assert-NotContains $capture 'Get-Process -Name[^\r\n]*java|Stop-Process -Name[^\r\n]*java' 'upgrade preflight must not kill java.exe by name'
Assert-Contains $migrate 'data\\farm\.db|uploads|pre-fabmatrix-migration|New data root is already initialized' 'legacy migration mapping and guard'
Assert-Contains $migrate 'Set-FabMatrixPortableConfig' 'legacy config normalization'
Assert-Contains $migrate 'ResultPath|SchemaVersion|LegacyDataMigrated|NoLegacyDataDetected|NewDataRootInitialized' 'structured legacy migration result publication'
Assert-Contains $portableConfig 'datasource|FARM_DATA_DIR|storage-path|logs/farm\.log' 'portable path insertion and normalization'
Assert-Contains $recovery 'StartMode|WasRunning|sc\.exe config' 'best-effort original service recovery'
Assert-Contains $serviceXml '&quot;|ConvertTo-XmlText|stoptimeout>30sec' 'quoted escaped WinSW arguments and stop timeout'
Assert-Contains $serviceXml '<env name="SERVER_PORT" value="\$portXml" />' 'WinSW service-level effective port environment'
Assert-NotContains $serviceXml '<env name="LOG_PATH"' 'WinSW must not override Spring Boot logging path'
Assert-Contains $control 'ValidateSet' 'tray service actions'
Assert-Contains $tray 'SystemTray|FabMatrixTray' 'pure JDK tray controller'
Assert-Contains $tray 'service-state.*effective-port\.txt|PORT_METADATA_DIRECTORY.*PORT_METADATA_FILE' 'tray installed effective port metadata path'
Assert-Contains $tray 'tray\.lock|tryLock' 'single instance'
Assert-Contains $tray 'EncodedCommand|self-test-uac-command' 'encoded UAC command construction'
Assert-Contains $tray 'runElevatedPowerShell\("service-control\.ps1", action\)' 'tray service action uses positional binding'
Assert-Contains $tray 'ProgressPreference.*SilentlyContinue|ErrorActionPreference.*Stop' 'UAC helper suppresses progress and stops on errors'
Assert-Contains $tray 'createUacResultPath|readUacResult|deleteUacResultPath' 'structured UAC result lifecycle'
Assert-Contains $tray 'sanitizePowerShellError|CLIXML output was suppressed' 'UAC CLIXML sanitization'
Assert-NotContains $tray 'dataRoot\.resolve\("backups"\)' 'ordinary tray cannot browse private backups'
Assert-Contains $uninstall "Get-Process -Name 'FabMatrixTray', 'FarmTray'" 'uninstall closes only tray executables'
Assert-Contains $uninstall '\[switch\]\$PurgeData|if \(\$PurgeData\)' 'purge is opt-in'
Assert-Contains $uninstall 'purge-local-data\.ps1|Business data retained at' 'purge payload and default retention output'
Assert-Contains $uninstall '-LegacyDataRoot \$LegacyDataRoot[\s\S]*-DiagnosticsRoot \$DiagnosticsRoot' 'uninstall forwards all controlled purge roots'
Assert-Contains $uninstall '\[switch\]\$Silent|\[Console\]::Error\.WriteLine' 'silent uninstall logs failure without UI'
Assert-Contains $uninstall 'Write-Error[\s\S]*exit 1|\[Console\]::Error\.WriteLine[\s\S]*exit 1' 'uninstall failure is propagated'
Assert-NotContains $uninstall 'System\.Windows\.Forms|MessageBox|FailureMarker' 'PowerShell uninstall UI and orphan failure marker'
Assert-NotContains $uninstall '& \$purgeScript[\s\S]{0,200}\$LASTEXITCODE' 'invalid in-process purge LASTEXITCODE check'
Assert-Contains $purge 'Assert-FabMatrixPurgePaths|Assert-FabMatrixNoReparsePointsInPath|Remove-FabMatrixLocalData' 'standalone purge safety functions'
Assert-Contains $purge 'Get-FabMatrixExpectedDataRoot|CommonApplicationData|ProgramData' 'controlled Local data root policy'
Assert-Contains $purge 'Expected legacy root[\s\S]*Farm|Expected diagnostics root[\s\S]*FabMatrix' 'strict legacy and diagnostics root policy'
Assert-Contains $purge 'Test-FabMatrixLegacyFarmLayout[\s\S]*data\\farm\.db[\s\S]*uploads[\s\S]*config' 'legacy Farm structure recognition'
Assert-Contains $purge 'Targets = @\(\$targets\)|StringComparison\]::OrdinalIgnoreCase' 'duplicate purge target suppression'
Assert-Contains $purge 'InstallDir.*must not equal|DataRoot.*outside|drive root' 'purge path boundary guards'
Assert-Contains $purge 'Remove-FabMatrixTreeNodeSafely|Get-ChildItem -LiteralPath' 'post-order no-follow purge deletion'
Assert-NotContains $purge 'Remove-Item[^\r\n]*-Recurse' 'actual DataRoot never uses recursive Remove-Item'
Assert-NotContains $purge 'Remove-Item -Path|Remove-Item [^\r\n]*\*' 'purge does not use wildcard deletion'
Assert-Contains $purge 'ReparsePoint|Get-Item -LiteralPath \$root|Get-Item -LiteralPath \$current' 'root and ancestor reparse-point protection'
Assert-Contains $purge 'SelfTest|dedicated temporary test root' 'purge disposable self-test'
$purgeSource = Get-Content -LiteralPath $purge -Raw -Encoding UTF8
$purgeTokens = $null
$purgeParseErrors = $null
$purgeAst = [System.Management.Automation.Language.Parser]::ParseInput($purgeSource, $purge, [ref]$purgeTokens, [ref]$purgeParseErrors)
if ($purgeParseErrors.Count -ne 0) { throw "Purge AST parse failed: $($purgeParseErrors[0].Message)" }
$purgeFunctionAsts = @{}
foreach ($functionName in @('Assert-FabMatrixNoReparsePointsInPath', 'Remove-FabMatrixTreeNodeSafely', 'Remove-FabMatrixLocalData')) {
    $functionAst = $purgeAst.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName }, $true)
    if ($null -eq $functionAst) { throw "Purge script does not expose required function: $functionName" }
    $purgeFunctionAsts[$functionName] = $functionAst
}
$purgeRemoveCommands = @($purgeAst.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Remove-Item'
        }, $true))
if ($purgeRemoveCommands.Count -eq 0) { throw 'Purge script has no explicit node deletion commands.' }
foreach ($removeCommand in $purgeRemoveCommands) {
    if ($removeCommand.Extent.Text -notmatch '-LiteralPath' -or $removeCommand.Extent.Text -match '-Recurse') {
        throw "Purge deletion must be non-recursive and LiteralPath-only: $($removeCommand.Extent.Text)"
    }
}
$treeDeleteAst = $purgeFunctionAsts['Remove-FabMatrixTreeNodeSafely']
$treeDeleteText = $treeDeleteAst.Extent.Text
if ($treeDeleteText -notmatch 'Remove-FabMatrixTreeNodeSafely\s+\$child\.FullName' -or
    $treeDeleteText -notmatch 'Get-Item -LiteralPath \$resolved[\s\S]*Get-Item -LiteralPath \$resolved' -or
    $treeDeleteText -match 'Get-ChildItem[^\r\n]*-Recurse') {
    throw 'Purge tree deletion is not a revalidated, non-recursive post-order traversal.'
}
$ancestorGuardText = $purgeFunctionAsts['Assert-FabMatrixNoReparsePointsInPath'].Extent.Text
if ($ancestorGuardText -notmatch 'Get-Item -LiteralPath \$root' -or
    $ancestorGuardText -notmatch '\$current = \$root' -or
    $ancestorGuardText -match '\$root\.TrimEnd') {
    throw 'Purge ancestor guard must preserve and inspect the complete drive root.'
}
$purgeSelfTestOutput = (& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $purge -SelfTest | Out-String)
if ($LASTEXITCODE -ne 0 -or $purgeSelfTestOutput -notmatch 'purge self-test passed' -or
    $purgeSelfTestOutput -notmatch 'multi-target purge' -or
    $purgeSelfTestOutput -notmatch 'idempotency' -or
    $purgeSelfTestOutput -notmatch 'Descendant junction' -or
    $purgeSelfTestOutput -notmatch 'DataRoot root junction' -or
    $purgeSelfTestOutput -notmatch 'InstallDir root junction' -or
    $purgeSelfTestOutput -notmatch 'InstallDir ancestor junction' -or
    $purgeSelfTestOutput -notmatch 'Duplicate current and diagnostics' -or
    $purgeSelfTestOutput -notmatch 'Legacy Farm layout rejection and acceptance' -or
    $purgeSelfTestOutput -notmatch 'Legacy root junction' -or
    $purgeSelfTestOutput -notmatch 'Legacy descendant junction' -or
    $purgeSelfTestOutput -notmatch 'Diagnostics root junction' -or
    $purgeSelfTestOutput -notmatch 'Diagnostics descendant junction' -or
    $purgeSelfTestOutput -notmatch 'Legacy and diagnostics ancestor junction') {
    throw "Purge script disposable self-test did not complete: $purgeSelfTestOutput"
}
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
if ($portSelfTestOutput -notmatch 'parser, fresh legacy migration and metadata self-test passed') {
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
    Assert-Contains $generatedConfig 'bootstrap-token-file: .+bootstrap\.token' 'generated bootstrap token path'
    Assert-Exists (Join-Path $configDir 'bootstrap.token') 'generated bootstrap credential'
    Assert-Contains (Join-Path $configDir 'bootstrap.token') '^expiresAt=\d+\r?\ntoken=[A-Za-z0-9_-]+\r?\n$' 'bootstrap credential format'
    $bootstrapPath = Join-Path $configDir 'bootstrap.token'
    $bootstrapBeforeForce = Get-Content -LiteralPath $bootstrapPath -Raw -Encoding UTF8
    & $configure -Edition Local -ConfigDir $configDir -DataRoot $configData -Force | Out-Null
    if ((Get-Content -LiteralPath $bootstrapPath -Raw -Encoding UTF8) -ne $bootstrapBeforeForce) {
        throw 'Forced Local configuration rewrote the existing bootstrap credential.'
    }
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
    $migrationResultPath = Join-Path $migrationTemp 'migration-result.json'
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
    & $migrate -DataRoot $newData -LegacyDataRoot $legacy -ResultPath $migrationResultPath | Out-Null
    $migrationResult = Get-Content -LiteralPath $migrationResultPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([int]$migrationResult.SchemaVersion -ne 1 -or -not [bool]$migrationResult.Migrated -or
        $migrationResult.Reason -ne 'LegacyDataMigrated') {
        throw 'Legacy migration did not publish a positive structured result.'
    }
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
    & $migrate -DataRoot $newData -LegacyDataRoot $legacy -ResultPath $migrationResultPath | Out-Null
    $secondMigrationResult = Get-Content -LiteralPath $migrationResultPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([bool]$secondMigrationResult.Migrated -or $secondMigrationResult.Reason -ne 'NewDataRootInitialized') {
        throw 'Initialized target did not publish a negative structured migration result.'
    }
    if ([IO.File]::ReadAllText((Join-Path $newData 'database\farm.db')) -ne 'new-data-wins') {
        throw 'Initialized new data root was overwritten by legacy migration.'
    }
} finally {
    if (Test-Path -LiteralPath $migrationTemp) { Remove-Item -LiteralPath $migrationTemp -Recurse -Force }
}

# Exercise the real Windows ACL policy only against disposable temp directories.
$aclTemp = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixAclContract-' + [guid]::NewGuid().ToString('N'))
$currentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$aclFixtureFiles = @{}
$aclJunctionPath = Join-Path $aclTemp 'config\external-link'
$aclExternalTarget = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixAclExternal-' + [guid]::NewGuid().ToString('N'))
try {
    foreach ($relative in @('config', 'database', 'backups', 'files', 'logs')) {
        $path = Join-Path $aclTemp $relative
        New-Item -ItemType Directory -Force -Path $path | Out-Null
        $childDirectory = Join-Path $path 'existing-child'
        New-Item -ItemType Directory -Force -Path $childDirectory | Out-Null
        $childFile = Join-Path $childDirectory 'existing-file.txt'
        [IO.File]::WriteAllText($childFile, "fixture:$relative")
        $aclFixtureFiles[$relative] = $childFile
        # Keep the non-elevated test owner able to inspect/clean the fixture; production calls run elevated.
        & icacls.exe $path '/grant' "*$currentSid`:(OI)(CI)F" '*S-1-5-32-545:(OI)(CI)F' '*S-1-5-11:(OI)(CI)F' '/T' '/C' '/Q' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Unable to seed disposable ACL fixture: $path" }
    }
    $serviceLogDirectory = Join-Path $aclTemp 'logs\service'
    $stdoutLog = Join-Path $serviceLogDirectory 'FabMatrixServerService.out.log'
    $stderrLog = Join-Path $serviceLogDirectory 'FabMatrixServerService.err.log'
    New-Item -ItemType Directory -Force -Path $serviceLogDirectory | Out-Null
    [IO.File]::WriteAllText($stdoutLog, 'existing stdout log')
    [IO.File]::WriteAllText($stderrLog, 'existing stderr log')
    $aclFixtureFiles['logs-service-out'] = $stdoutLog
    $aclFixtureFiles['logs-service-err'] = $stderrLog
    & icacls.exe (Join-Path $aclTemp 'logs') '/grant' "*$currentSid`:(OI)(CI)F" '*S-1-5-32-545:(OI)(CI)F' '*S-1-5-11:(OI)(CI)F' '/T' '/C' '/Q' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to seed disposable service log ACL fixture.' }
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

    function Get-ContractAclSid($Rule) {
        try { return $Rule.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value }
        catch { return $Rule.IdentityReference.Value }
    }

    function Assert-ContractObjectAcl([string]$Path, [bool]$UsersReadOnly) {
        $rules = @((Get-Acl -LiteralPath $Path -ErrorAction Stop).Access)
        foreach ($requiredSid in @('S-1-5-18', 'S-1-5-32-544')) {
            $privilegedRule = $rules | Where-Object {
                (Get-ContractAclSid $_) -eq $requiredSid -and
                $_.AccessControlType -eq 'Allow' -and -not $_.IsInherited
            } | Select-Object -First 1
            if ($null -eq $privilegedRule -or
                ($privilegedRule.FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::FullControl) -ne [System.Security.AccessControl.FileSystemRights]::FullControl) {
                throw "ACL is missing direct FullControl for $requiredSid on $Path"
            }
        }

        foreach ($forbiddenSid in @('S-1-5-11', 'S-1-1-0')) {
            if (@($rules | Where-Object { (Get-ContractAclSid $_) -eq $forbiddenSid }).Count -gt 0) {
                throw "ACL retained broad principal $forbiddenSid on $Path"
            }
        }

        $usersRule = $rules | Where-Object {
            (Get-ContractAclSid $_) -eq 'S-1-5-32-545' -and
            $_.AccessControlType -eq 'Allow' -and -not $_.IsInherited
        } | Select-Object -First 1
        if ($UsersReadOnly) {
            if ($null -eq $usersRule -or
                ($usersRule.FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::ReadAndExecute) -eq 0 -or
                ($usersRule.FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::Write) -ne 0) {
                throw "Shared ACL must grant Users direct read/execute without write on $Path"
            }
        } elseif ($null -ne $usersRule) {
            throw "Private ACL retained Users on $Path"
        }
    }

    function Assert-ContractDirectoryInheritance([string]$Path, [bool]$UsersReadOnly) {
        $rules = @((Get-Acl -LiteralPath $Path -ErrorAction Stop).Access)
        foreach ($requiredSid in @('S-1-5-18', 'S-1-5-32-544')) {
            $inheritableRule = $rules | Where-Object {
                (Get-ContractAclSid $_) -eq $requiredSid -and
                $_.AccessControlType -eq 'Allow' -and -not $_.IsInherited -and
                (($_.InheritanceFlags -band [System.Security.AccessControl.InheritanceFlags]::ContainerInherit) -ne 0) -and
                (($_.InheritanceFlags -band [System.Security.AccessControl.InheritanceFlags]::ObjectInherit) -ne 0)
            } | Select-Object -First 1
            if ($null -eq $inheritableRule) {
                throw "ACL is missing future-child inheritance for $requiredSid on $Path"
            }
        }
        if ($UsersReadOnly) {
            $usersRule = $rules | Where-Object {
                (Get-ContractAclSid $_) -eq 'S-1-5-32-545' -and
                $_.AccessControlType -eq 'Allow' -and -not $_.IsInherited -and
                (($_.InheritanceFlags -band [System.Security.AccessControl.InheritanceFlags]::ContainerInherit) -ne 0) -and
                (($_.InheritanceFlags -band [System.Security.AccessControl.InheritanceFlags]::ObjectInherit) -ne 0)
            } | Select-Object -First 1
            if ($null -eq $usersRule) {
                throw "Shared ACL is missing future-child Users inheritance on $Path"
            }
        }
    }

    foreach ($relative in @('config', 'database', 'backups', 'files', 'logs')) {
        $rootPath = Join-Path $aclTemp $relative
        $objects = @((Get-Item -LiteralPath $rootPath -Force)) + @(Get-ChildItem -LiteralPath $rootPath -Force -Recurse)
        foreach ($object in $objects) {
            Assert-ContractObjectAcl $object.FullName ($relative -in @('files', 'logs'))
            if ($object -is [IO.DirectoryInfo]) {
                Assert-ContractDirectoryInheritance $object.FullName ($relative -in @('files', 'logs'))
            }
        }
    }
    foreach ($logPath in @($aclFixtureFiles['logs-service-out'], $aclFixtureFiles['logs-service-err'])) {
        Assert-ContractObjectAcl $logPath $true
    }
    $runtimeUsersRule = (Get-Acl -LiteralPath $runtimeMetadataPath).Access | Where-Object {
        (Get-ContractAclSid $_) -eq 'S-1-5-32-545'
    } | Select-Object -First 1
    if ($null -eq $runtimeUsersRule -or
        ($runtimeUsersRule.FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::ReadAndExecute) -eq 0 -or
        ($runtimeUsersRule.FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::Write) -ne 0) {
        throw "Runtime effective port metadata must grant Users read/execute without write; actual rights: $($runtimeUsersRule.FileSystemRights)"
    }
    if (([IO.File]::ReadAllText($runtimeMetadataPath)).Trim() -ne '8080') {
        throw 'Runtime metadata ACL application changed the preserved legacy effective port.'
    }

    # A junction below a fixed data root must fail closed before any recursive ACL
    # command can reach its external target. Permission-restricted environments may
    # skip only the real junction exercise, while static guards remain mandatory.
    New-Item -ItemType Directory -Force -Path $aclExternalTarget | Out-Null
    $externalSentinel = Join-Path $aclExternalTarget 'sentinel.txt'
    [IO.File]::WriteAllText($externalSentinel, 'external-acl-sentinel')
    $externalContentHash = (Get-FileHash -LiteralPath $externalSentinel -Algorithm SHA256).Hash
    $externalAclSddl = (Get-Acl -LiteralPath $externalSentinel -ErrorAction Stop).Sddl
    $junctionCreated = $false
    try {
        New-Item -ItemType Junction -Path $aclJunctionPath -Target $aclExternalTarget -ErrorAction Stop | Out-Null
        $junctionCreated = $true
    } catch {
        if ($_.Exception.Message -match 'access|denied|privilege|not permitted|symbolic link') {
            Write-Host "ACL junction external sentinel test skipped due to permission limits: $($_.Exception.Message)"
        } else {
            throw "Unable to create ACL junction fixture: $($_.Exception.Message)"
        }
    }
    if ($junctionCreated) {
        try {
            Assert-ThrowsMatching { Set-FabMatrixDataAcl -DataRoot $aclTemp } 'reparse point' 'ACL junction external target'
        } finally {
            if (Test-Path -LiteralPath $aclJunctionPath) { [IO.Directory]::Delete($aclJunctionPath) }
        }
        if ((Get-FileHash -LiteralPath $externalSentinel -Algorithm SHA256).Hash -ne $externalContentHash -or
            (Get-Acl -LiteralPath $externalSentinel -ErrorAction Stop).Sddl -ne $externalAclSddl) {
            throw 'ACL junction rejection changed the external sentinel content or ACL.'
        }
    }
} finally {
    if (Test-Path -LiteralPath $aclJunctionPath) { [IO.Directory]::Delete($aclJunctionPath) }
    if (Test-Path -LiteralPath $aclTemp) {
        # The policy deliberately removes the test user's inherited access.
        # Restore a direct cleanup grant on every existing object first so this
        # contract remains runnable without elevation.
        & icacls.exe $aclTemp '/grant:r' "*$currentSid`:F" '/T' '/C' '/Q' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Unable to restore disposable ACL fixture cleanup access: $aclTemp" }
        Remove-Item -LiteralPath $aclTemp -Recurse -Force -ErrorAction Stop
        if (Test-Path -LiteralPath $aclTemp) { throw "Disposable ACL fixture was not removed: $aclTemp" }
    }
    if (Test-Path -LiteralPath $aclExternalTarget) { Remove-Item -LiteralPath $aclExternalTarget -Recurse -Force -ErrorAction SilentlyContinue }
}

if (-not [string]::IsNullOrWhiteSpace($PayloadPath)) {
    $payload = [IO.Path]::GetFullPath($PayloadPath)
    foreach ($relative in @('FabMatrixServer.exe', 'FabMatrixTray.exe', 'service\purge-local-data.ps1', 'maintenance\local-backup.ps1',
            'maintenance\local-restore.ps1', 'maintenance\data-acl.ps1', 'maintenance\portable-config.ps1',
            'service\capture-upgrade-state.ps1', 'service\data-acl.ps1', 'service\migrate-legacy-data.ps1',
            'service\portable-config.ps1', 'service\recover-upgrade-state.ps1', 'service\service-xml.ps1')) {
        Assert-Exists (Join-Path $payload $relative) "payload $relative"
    }
    Invoke-PackagedLocalStartupProbe $payload $configure
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
    $trayUacProcess = Start-Process -FilePath $trayLauncherPath -ArgumentList '--self-test-uac-command' -Wait -PassThru
    if ($trayUacProcess.ExitCode -ne 0) {
        throw "Packaged tray launcher UAC command self-test failed: $($trayUacProcess.ExitCode)"
    }
    Write-Host 'FabMatrix packaged tray launcher UAC command self-test passed.'
    $payloadJava = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -eq $payloadJava) { throw 'Java is required to execute the packaged tray brand icon self-test.' }
    & $payloadJava.Source '-Djava.awt.headless=true' '-cp' $trayJarPath 'com.example.farm.tray.FabMatrixTray' '--self-test-brand-icon'
    if ($LASTEXITCODE -ne 0) { throw "Packaged tray brand icon self-test failed: $LASTEXITCODE" }
    & $payloadJava.Source '-Djava.awt.headless=true' '-cp' $trayJarPath 'com.example.farm.tray.FabMatrixTray' '--self-test-port-config'
    if ($LASTEXITCODE -ne 0) { throw "Packaged tray port config self-test failed: $LASTEXITCODE" }
    & $payloadJava.Source '-Djava.awt.headless=true' '-cp' $trayJarPath 'com.example.farm.tray.FabMatrixTray' '--self-test-menu-font'
    if ($LASTEXITCODE -ne 0) { throw "Packaged tray menu font self-test failed: $LASTEXITCODE" }
    & $payloadJava.Source '-Djava.awt.headless=true' '-cp' $trayJarPath 'com.example.farm.tray.FabMatrixTray' '--self-test-uac-command'
    if ($LASTEXITCODE -ne 0) { throw "Packaged tray UAC command self-test failed: $LASTEXITCODE" }
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
