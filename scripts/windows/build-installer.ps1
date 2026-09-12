param(
    [string]$OutputDir = 'dist\windows-installer',
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version = '2.0.0',
    [ValidateSet('Full', 'AppImage', 'Payload')]
    [string]$Mode = 'Full',
    [switch]$SkipTests,
    [switch]$NoClean,
    [switch]$CleanOutput
)

$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptRoot '..\..')).Path
$assetsPath = Join-Path $scriptRoot 'assets'
$serverIconPath = Join-Path $assetsPath 'server.ico'
$trayIconPath = Join-Path $assetsPath 'tray.ico'
$trayPngPath = Join-Path $assetsPath 'tray.png'
$trayIconPropertyPath = $trayIconPath -replace '\\', '/'

function Resolve-AbsolutePath([string]$Path, [string]$BasePath = '') {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'A non-empty path is required.' }
    $normalizedInput = $Path.Replace('/', '\')
    $isLocalAbsolute = [IO.Path]::IsPathRooted($Path) -and $normalizedInput -match '^[A-Za-z]:\\'
    $isProjectRelative = -not [IO.Path]::IsPathRooted($Path) -and $normalizedInput -notmatch '^[A-Za-z]:' -and
        -not [string]::IsNullOrWhiteSpace($BasePath)
    if (-not $isLocalAbsolute -and -not $isProjectRelative) {
        throw "Only an ordinary local-drive absolute path or a project-relative path is supported: $Path"
    }
    $candidatePath = if ($isProjectRelative) {
        Join-Path $BasePath $Path
    } else {
        $Path
    }
    try {
        $resolvedPath = [IO.Path]::GetFullPath($candidatePath)
    } catch {
        throw "Unable to resolve path: $Path"
    }
    $normalizedResolved = $resolvedPath.Replace('/', '\')
    $resolvedRoot = [IO.Path]::GetPathRoot($normalizedResolved)
    if ($normalizedResolved -notmatch '^[A-Za-z]:\\' -or
        $resolvedRoot -notmatch '^[A-Za-z]:\\$') {
        throw "Resolved path is not an ordinary local drive path: $resolvedPath"
    }
    return $resolvedPath
}

function Assert-NoReparsePointsInPath([string]$Path) {
    $resolvedPath = Resolve-AbsolutePath $Path
    $pathRoot = [IO.Path]::GetPathRoot($resolvedPath)
    if ([string]::IsNullOrWhiteSpace($pathRoot)) { throw "Path has no filesystem root: $resolvedPath" }
    $currentPath = $pathRoot
    $rootAttributes = [IO.File]::GetAttributes($currentPath)
    if ($rootAttributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "Refusing a path containing a reparse point: $currentPath"
    }
    $components = @()
    $relativePath = $resolvedPath.Substring($pathRoot.Length)
    if (-not [string]::IsNullOrWhiteSpace($relativePath)) {
        $components = @($relativePath -split '[\\/]' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    }
    foreach ($component in $components) {
        $currentPath = [IO.Path]::Combine($currentPath, $component)
        try {
            $attributes = [IO.File]::GetAttributes($currentPath)
        } catch [IO.FileNotFoundException], [IO.DirectoryNotFoundException] {
            break
        }
        $canonicalItem = Get-Item -LiteralPath $currentPath -Force
        if (-not [string]::Equals($component, $canonicalItem.Name, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing a non-canonical or short-name path component: $currentPath"
        }
        if ($attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Refusing a path containing a reparse point: $currentPath"
        }
    }
    return $resolvedPath
}

function Assert-PathContainedBy([string]$Path, [string]$ParentPath) {
    $resolvedPath = (Resolve-AbsolutePath $Path).TrimEnd('\')
    $resolvedParent = (Resolve-AbsolutePath $ParentPath).TrimEnd('\')
    if (-not $resolvedPath.StartsWith($resolvedParent + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path is not contained by the expected output directory: $resolvedPath"
    }
    return $resolvedPath
}

function Assert-SafeOutputPath([string]$Path, [string]$ProjectRoot) {
    $resolvedOutput = Resolve-AbsolutePath $Path
    $resolvedProject = Resolve-AbsolutePath $ProjectRoot
    $resolvedOutputTrimmed = $resolvedOutput.TrimEnd('\')
    $allowedOutputRoot = (Resolve-AbsolutePath (Join-Path $resolvedProject 'dist')).TrimEnd('\')
    if (-not $resolvedOutputTrimmed.StartsWith($allowedOutputRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "OutputDir must be a strict subdirectory of the repository dist directory: $allowedOutputRoot"
    }
    $resolvedOutput = Assert-NoReparsePointsInPath $resolvedOutput
    if (Test-Path -LiteralPath $resolvedOutput -PathType Leaf) {
        throw "Output path is not a directory: $resolvedOutput"
    }
    return $resolvedOutput
}

function Remove-SafeGeneratedPath([string]$Path, [string]$OutputPath, [string]$ProjectRoot) {
    $safeOutput = Assert-SafeOutputPath $OutputPath $ProjectRoot
    $candidatePath = Assert-PathContainedBy $Path $safeOutput
    [void](Assert-NoReparsePointsInPath $candidatePath)
    if (-not (Test-Path -LiteralPath $candidatePath)) { return }
    $candidate = Get-Item -LiteralPath $candidatePath -Force
    if ($candidate.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "Refusing to remove a reparse-point generated item: $candidatePath"
    }
    if ($candidate.PSIsContainer) {
        Remove-Item -LiteralPath $candidatePath -Recurse -Force
    } else {
        Remove-Item -LiteralPath $candidatePath -Force
    }
}

function Clear-KnownOutput([string]$OutputPath, [string]$ProjectRoot) {
    $safeOutput = Assert-SafeOutputPath $OutputPath $ProjectRoot
    if (-not (Test-Path -LiteralPath $safeOutput)) { return }
    $knownInstallerPattern = '^(FabMatrixServer-Setup|Farm-Setup)(-[^\\/]+)?\.exe$'
    $knownNames = @('work', 'checksums.txt', 'release-info.txt', 'README-Windows.md', 'precommit-check')
    Get-ChildItem -LiteralPath $safeOutput -Force | ForEach-Object {
        $isKnown = $_.Name -in $knownNames -or $_.Name -match $knownInstallerPattern -or
            (-not $_.PSIsContainer -and $_.Name -match '^acceptance-[^\\/]+\.log$')
        if (-not $isKnown) { return }
        Remove-SafeGeneratedPath $_.FullName $safeOutput $ProjectRoot
    }
}

function Assert-ValidBrandAsset([string]$Path, [ValidateSet('ico', 'png')][string]$Kind) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Brand asset is missing: $Path" }
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($Kind -eq 'ico') {
        if ($bytes.Length -lt 6 -or $bytes[0] -ne 0 -or $bytes[1] -ne 0 -or
            $bytes[2] -ne 1 -or $bytes[3] -ne 0) {
            throw "Brand asset is not a valid ICO header: $Path"
        }
        $count = [BitConverter]::ToUInt16($bytes, 4)
        if ($count -lt 1 -or $bytes.Length -lt 6 + (16 * $count)) { throw "ICO directory is invalid: $Path" }
        for ($index = 0; $index -lt $count; $index++) {
            $entryOffset = 6 + (16 * $index)
            $size = [BitConverter]::ToUInt32($bytes, $entryOffset + 8)
            $offset = [BitConverter]::ToUInt32($bytes, $entryOffset + 12)
            if ($size -lt 8 -or $offset -lt (6 + (16 * $count)) -or $offset + $size -gt $bytes.Length) {
                throw "ICO image directory entry is outside the file: $Path"
            }
            if ($bytes[$offset] -ne 0x89 -or $bytes[$offset + 1] -ne 0x50 -or
                $bytes[$offset + 2] -ne 0x4E -or $bytes[$offset + 3] -ne 0x47) {
                throw "ICO image entry is not PNG encoded: $Path"
            }
        }
    } else {
        $signature = [byte[]](0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        $signatureMatches = $bytes.Length -ge 8
        for ($index = 0; $signatureMatches -and $index -lt $signature.Length; $index++) {
            $signatureMatches = $bytes[$index] -eq $signature[$index]
        }
        if ($bytes.Length -lt 33 -or -not $signatureMatches) {
            throw "Brand asset is not a valid PNG signature: $Path"
        }
        $width = [Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($bytes, 16))
        $height = [Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($bytes, 20))
        if ([Text.Encoding]::ASCII.GetString($bytes, 12, 4) -ne 'IHDR' -or
            $width -le 0 -or $height -le 0) {
            throw "PNG IHDR is invalid: $Path"
        }
    }
}

function Test-WindowsBrandAssets([string]$AssetsDir) {
    Assert-ValidBrandAsset (Join-Path $AssetsDir 'installer.ico') 'ico'
    Assert-ValidBrandAsset (Join-Path $AssetsDir 'server.ico') 'ico'
    Assert-ValidBrandAsset (Join-Path $AssetsDir 'tray.ico') 'ico'
    Assert-ValidBrandAsset (Join-Path $AssetsDir 'tray.png') 'png'
}

function Invoke-Checked([string]$FilePath, [string[]]$Arguments) {
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed ($LASTEXITCODE): $FilePath $($Arguments -join ' ')"
    }
}

function Invoke-CapturedProcess([string]$FilePath, [string]$Arguments) {
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $FilePath
    $startInfo.Arguments = $Arguments
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { throw "Unable to start process: $FilePath" }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw "Command failed ($($process.ExitCode)): $FilePath $Arguments`n$stderr" }
    return [pscustomobject]@{ StdOut = $stdout; StdErr = $stderr; ExitCode = $process.ExitCode }
}

function Get-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return (Resolve-Path $env:JAVA_HOME).Path
    }
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if (-not $javaCommand) { throw 'Unable to locate java.exe; set JAVA_HOME.' }
    $capture = Invoke-CapturedProcess -FilePath $javaCommand.Source -Arguments '-XshowSettings:properties -version'
    $settings = $capture.StdErr + [Environment]::NewLine + $capture.StdOut
    $match = [regex]::Match($settings, '(?m)^\s*java\.home\s*=\s*(.+?)\s*$')
    if (-not $match.Success) { throw 'Unable to determine java.home; set JAVA_HOME.' }
    return $match.Groups[1].Value.Trim()
}

function Find-InnoCompiler {
    $command = Get-Command ISCC.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidates = @(
        'C:\Program Files (x86)\Inno Setup 6\ISCC.exe',
        'C:\Program Files\Inno Setup 6\ISCC.exe',
        (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe')
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    throw 'Inno Setup 6 ISCC.exe was not found.'
}

function Write-Utf8NoBom([string]$Path, [string]$Content) {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, $Content, $utf8)
}

$outputPath = Resolve-AbsolutePath $OutputDir $projectRoot
$outputPath = Assert-SafeOutputPath $outputPath $projectRoot
$workPath = Join-Path $outputPath 'work'
$payloadPath = Join-Path $workPath 'payload'
$inputPath = Join-Path $workPath 'input'
$imagePath = Join-Path $workPath 'app-image'
$vendorPath = Join-Path $workPath 'vendor'
Write-Host "Project: $projectRoot"
Test-WindowsBrandAssets $assetsPath
if ($CleanOutput) {
    Write-Host "Cleaning known generated output items under: $outputPath"
    Clear-KnownOutput $outputPath $projectRoot
}
$javaHome = Get-JavaHome
$javaExe = Join-Path $javaHome 'bin\java.exe'
$javac = Join-Path $javaHome 'bin\javac.exe'
$jarTool = Join-Path $javaHome 'bin\jar.exe'
$jpackage = Join-Path $javaHome 'bin\jpackage.exe'
foreach ($tool in @($javaExe, $javac, $jarTool, $jpackage)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Java 25 JDK tool not found: $tool" }
}
$javaCapture = Invoke-CapturedProcess -FilePath $javaExe -Arguments '-version'
$javaVersion = @($javaCapture.StdErr -split '\r?\n' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })[0].Trim()
if ($javaVersion -notmatch 'version "25\.') { throw "Java 25 is required; current version: $javaVersion" }
Write-Host "Java: $javaVersion"

$maven = $null
$mvnCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($mvnCommand) {
    $maven = $mvnCommand.Source
} elseif (Test-Path -LiteralPath (Join-Path $projectRoot 'mvnw.cmd')) {
    $maven = Join-Path $projectRoot 'mvnw.cmd'
} else {
    throw 'Maven or mvnw.cmd was not found.'
}
Write-Host "Maven: $maven"
$iscc = if ($Mode -eq 'Full') { Find-InnoCompiler } else { $null }
if ($iscc) { Write-Host "Inno Setup: $iscc" }

Push-Location $projectRoot
try {
    if (-not $NoClean) { Invoke-Checked $maven @('clean') }
    if (-not $SkipTests) {
        # LocalEditionMapperTest opens SQLite before ApplicationRunner can create the parent directory.
        # This is a disposable target fixture, never a business data directory.
        New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'target\local-edition-test-data') | Out-Null
        Invoke-Checked $maven @('test')
    }
    Invoke-Checked $maven @('package', '-DskipTests')

    $jar = Get-ChildItem (Join-Path $projectRoot 'target') -Filter 'fabmatrix-server-*.jar' -File |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) { throw 'FabMatrix Server JAR was not found after packaging.' }

    $traySource = Join-Path $projectRoot 'src\main\java\com\example\farm\tray\FabMatrixTray.java'
    if (-not (Test-Path -LiteralPath $traySource)) { throw "Tray source was not found: $traySource" }
    $trayClasses = Join-Path $workPath 'tray-classes'
    Remove-SafeGeneratedPath $inputPath $outputPath $projectRoot
    Remove-SafeGeneratedPath $trayClasses $outputPath $projectRoot
    New-Item -ItemType Directory -Force -Path $inputPath, $imagePath, $vendorPath, $trayClasses | Out-Null
    Invoke-Checked $javac @('--release', '25', '-encoding', 'UTF-8', '-d', $trayClasses, $traySource)
    Copy-Item -LiteralPath $trayPngPath -Destination (Join-Path $trayClasses 'tray.png') -Force
    $trayJar = Join-Path $inputPath 'FabMatrixTray.jar'
    Remove-SafeGeneratedPath $trayJar $outputPath $projectRoot
    Invoke-Checked $jarTool @('--create', '--file', $trayJar, '-C', $trayClasses, '.')
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $inputPath $jar.Name) -Force
    $inputFiles = @(Get-ChildItem -LiteralPath $inputPath -File)
    $expectedInputNames = @($jar.Name, 'FabMatrixTray.jar')
    if ($inputFiles.Count -ne 2 -or @($inputFiles | Where-Object { $_.Name -notin $expectedInputNames }).Count -ne 0) {
        throw "jpackage input must contain only the current server and tray JARs: $($inputFiles.Name -join ', ')"
    }

    $winswUrl = 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe'
    $winswSha256 = '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'
    $winswPath = Join-Path $vendorPath 'FabMatrixServerService.exe'
    if (-not (Test-Path -LiteralPath $winswPath)) { Invoke-WebRequest -Uri $winswUrl -OutFile $winswPath }
    $actualWinSwHash = (Get-FileHash -LiteralPath $winswPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actualWinSwHash -ne $winswSha256) { throw "WinSW SHA-256 verification failed: $actualWinSwHash" }
    $licensePath = Join-Path $vendorPath 'WinSW-LICENSE.txt'
    if (-not (Test-Path -LiteralPath $licensePath)) {
        Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/winsw/winsw/v2.12.0/LICENSE.txt' -OutFile $licensePath
    }

    Remove-SafeGeneratedPath $imagePath $outputPath $projectRoot
    New-Item -ItemType Directory -Force -Path $imagePath | Out-Null
    $launcherProperties = Join-Path $workPath 'FabMatrixTray.properties'
    Write-Utf8NoBom $launcherProperties @"
main-jar=FabMatrixTray.jar
main-class=com.example.farm.tray.FabMatrixTray
java-options=-Dfile.encoding=UTF-8
win-console=false
icon=$trayIconPropertyPath
"@
    $jpackageArgs = @(
        '--type', 'app-image',
        '--name', 'FabMatrixServer',
        '--app-version', $Version,
        '--vendor', 'FabMatrix',
        '--description', 'FabMatrix Server local 3D printer farm management backend',
        '--input', $inputPath,
        '--main-jar', $jar.Name,
        '--dest', $imagePath,
        '--add-modules', 'ALL-MODULE-PATH',
        '--java-options', '-Dfile.encoding=UTF-8',
        '--java-options', '-Dspring.profiles.active=local',
        '--icon', $serverIconPath,
        '--win-console',
        '--add-launcher', "FabMatrixTray=$launcherProperties"
    )
    Invoke-Checked $jpackage $jpackageArgs
    $appImageRoot = Join-Path $imagePath 'FabMatrixServer'
    if (-not (Test-Path -LiteralPath (Join-Path $appImageRoot 'FabMatrixServer.exe'))) { throw 'jpackage did not create FabMatrixServer.exe.' }
    if (-not (Test-Path -LiteralPath (Join-Path $appImageRoot 'FabMatrixTray.exe'))) { throw 'jpackage did not create FabMatrixTray.exe.' }
    Write-Host "App image: $appImageRoot"
    if ($Mode -eq 'AppImage') { return }

    Remove-SafeGeneratedPath $payloadPath $outputPath $projectRoot
    New-Item -ItemType Directory -Force -Path $payloadPath | Out-Null
    Copy-Item -Path (Join-Path $appImageRoot '*') -Destination $payloadPath -Recurse -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $payloadPath 'service') | Out-Null
    foreach ($name in @('Farm-Configure.ps1', 'capture-upgrade-state.ps1', 'data-acl.ps1', 'install-service.ps1',
            'migrate-legacy-data.ps1', 'portable-config.ps1', 'recover-upgrade-state.ps1',
            'service-control.ps1', 'service-xml.ps1', 'uninstall-service.ps1')) {
        Copy-Item -LiteralPath (Join-Path $scriptRoot "service\$name") -Destination (Join-Path $payloadPath "service\$name") -Force
    }
    Copy-Item -LiteralPath $winswPath -Destination (Join-Path $payloadPath 'service\FabMatrixServerService.exe') -Force
    Copy-Item -LiteralPath $licensePath -Destination (Join-Path $payloadPath 'service\WinSW-LICENSE.txt') -Force
    Copy-Item -LiteralPath (Join-Path $scriptRoot 'README-Windows.md') -Destination (Join-Path $payloadPath 'README-Windows.md') -Force
    $maintenancePath = Join-Path $payloadPath 'maintenance'
    New-Item -ItemType Directory -Force -Path $maintenancePath | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot 'scripts\local-backup.ps1') -Destination (Join-Path $maintenancePath 'local-backup.ps1') -Force
    Copy-Item -LiteralPath (Join-Path $projectRoot 'scripts\local-restore.ps1') -Destination (Join-Path $maintenancePath 'local-restore.ps1') -Force
    Copy-Item -LiteralPath (Join-Path $scriptRoot 'service\data-acl.ps1') -Destination (Join-Path $maintenancePath 'data-acl.ps1') -Force
    Copy-Item -LiteralPath (Join-Path $scriptRoot 'service\portable-config.ps1') -Destination (Join-Path $maintenancePath 'portable-config.ps1') -Force
    Invoke-Checked 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        (Join-Path $scriptRoot 'test-installer-contract.ps1'), '-SkipTrayCompile', '-PayloadPath', $payloadPath)
    Write-Host "Payload: $payloadPath"
    if ($Mode -eq 'Payload') { return }

    New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
    $iss = Join-Path $scriptRoot 'installer\Farm.iss'
    $issArgs = @('/Qp', "/DAppVersion=$Version", "/DPayloadDir=$payloadPath", "/DOutputDir=$outputPath", "/DAssetsDir=$assetsPath", $iss)
    Invoke-Checked $iscc $issArgs

    $installer = Join-Path $outputPath 'FabMatrixServer-Setup.exe'
    if (-not (Test-Path -LiteralPath $installer)) { throw "Inno Setup did not create installer: $installer" }
    $installerHash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToUpperInvariant()
    $installerSize = (Get-Item -LiteralPath $installer).Length
    Write-Utf8NoBom (Join-Path $outputPath 'README-Windows.md') (Get-Content -Raw (Join-Path $scriptRoot 'README-Windows.md'))
    Write-Utf8NoBom (Join-Path $outputPath 'checksums.txt') "$installerHash *FabMatrixServer-Setup.exe`r`n"
    $commit = (git -C $projectRoot rev-parse HEAD).Trim()
    $releaseInfo = @"
FabMatrix Server Windows Local Edition release
Version: $Version
Git commit: $commit
Build time: $([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))
Java: $javaVersion
Packaging: jpackage app-image + Inno Setup EXE
Runtime: bundled Java 25 runtime, shared by server and tray launchers
Service wrapper: WinSW v2.12.0 x64 (MIT), service name FabMatrixServer
WinSW SHA-256: $actualWinSwHash
Installer: FabMatrixServer-Setup.exe
Installer bytes: $installerSize
Installer SHA-256: $installerHash
Default profile: local
Default port: 28081
Data layout: config/database/files/logs/backups
Firewall: TCP 28081, Private profile only (existing configured port is preserved)
"@
    if ($releaseInfo -match 'NativeCommandError|CategoryInfo') {
        throw 'release-info.txt contains PowerShell native error metadata.'
    }
    Write-Utf8NoBom (Join-Path $outputPath 'release-info.txt') $releaseInfo
    Write-Host "Installer: $installer"
    Write-Host "Size: $installerSize bytes"
    Write-Host "SHA-256: $installerHash"
} finally {
    Pop-Location
}
