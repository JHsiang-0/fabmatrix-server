param(
    [string]$OutputDir = 'dist\windows-installer',
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version = '2.0.0',
    [ValidateSet('Full', 'AppImage', 'Payload')]
    [string]$Mode = 'Full',
    [switch]$SkipTests,
    [switch]$NoClean
)

$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptRoot '..\..')).Path
$outputPath = if ([IO.Path]::IsPathRooted($OutputDir)) { [IO.Path]::GetFullPath($OutputDir) } else { [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDir)) }
$workPath = Join-Path $outputPath 'work'
$payloadPath = Join-Path $workPath 'payload'
$inputPath = Join-Path $workPath 'input'
$imagePath = Join-Path $workPath 'app-image'
$vendorPath = Join-Path $workPath 'vendor'

function Invoke-Checked([string]$FilePath, [string[]]$Arguments) {
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed ($LASTEXITCODE): $FilePath $($Arguments -join ' ')"
    }
}

function Get-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return (Resolve-Path $env:JAVA_HOME).Path
    }
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $settings = (& java -XshowSettings:properties -version 2>&1 | Out-String)
    $ErrorActionPreference = $previousErrorAction
    $match = [regex]::Match($settings, '(?m)^\s*java\.home\s*=\s*(.+?)\s*$')
    if (-not $match.Success) { throw '无法从 Java 获取 java.home；请设置 JAVA_HOME。' }
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
    throw '未找到 Inno Setup ISCC.exe。请安装 Inno Setup 6。'
}

function Write-Utf8NoBom([string]$Path, [string]$Content) {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, $Content, $utf8)
}

Write-Host "Project: $projectRoot"
$javaHome = Get-JavaHome
$javaExe = Join-Path $javaHome 'bin\java.exe'
$javac = Join-Path $javaHome 'bin\javac.exe'
$jarTool = Join-Path $javaHome 'bin\jar.exe'
$jpackage = Join-Path $javaHome 'bin\jpackage.exe'
foreach ($tool in @($javaExe, $javac, $jarTool, $jpackage)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Java 25 JDK 中未找到工具: $tool" }
}
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersion = (& $javaExe -version 2>&1 | Select-Object -First 1 | Out-String).Trim()
$ErrorActionPreference = $previousErrorAction
if ($javaVersion -notmatch 'version "25\.') { throw "必须使用 Java 25，当前为: $javaVersion" }
Write-Host "Java: $javaVersion"

$maven = $null
$mvnCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($mvnCommand) {
    $maven = $mvnCommand.Source
} elseif (Test-Path -LiteralPath (Join-Path $projectRoot 'mvnw.cmd')) {
    $maven = Join-Path $projectRoot 'mvnw.cmd'
} else {
    throw '未找到 Maven 或 mvnw.cmd。'
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
    if (-not $jar) { throw '打包后未找到 FabMatrix Server JAR。' }

    $traySource = Join-Path $projectRoot 'src\main\java\com\example\farm\tray\FabMatrixTray.java'
    if (-not (Test-Path -LiteralPath $traySource)) { throw "未找到托盘源码: $traySource" }
    $trayClasses = Join-Path $workPath 'tray-classes'
    Remove-Item -LiteralPath $trayClasses -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $inputPath, $imagePath, $vendorPath, $trayClasses | Out-Null
    Invoke-Checked $javac @('--release', '25', '-encoding', 'UTF-8', '-d', $trayClasses, $traySource)
    $trayJar = Join-Path $inputPath 'FabMatrixTray.jar'
    Remove-Item -LiteralPath $trayJar -Force -ErrorAction SilentlyContinue
    Invoke-Checked $jarTool @('--create', '--file', $trayJar, '-C', $trayClasses, '.')
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $inputPath $jar.Name) -Force

    $winswUrl = 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe'
    $winswSha256 = '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'
    $winswPath = Join-Path $vendorPath 'FabMatrixServerService.exe'
    if (-not (Test-Path -LiteralPath $winswPath)) { Invoke-WebRequest -Uri $winswUrl -OutFile $winswPath }
    $actualWinSwHash = (Get-FileHash -LiteralPath $winswPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actualWinSwHash -ne $winswSha256) { throw "WinSW SHA-256 校验失败：$actualWinSwHash" }
    $licensePath = Join-Path $vendorPath 'WinSW-LICENSE.txt'
    if (-not (Test-Path -LiteralPath $licensePath)) {
        Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/winsw/winsw/v2.12.0/LICENSE.txt' -OutFile $licensePath
    }

    Remove-Item -LiteralPath $imagePath -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $imagePath | Out-Null
    $launcherProperties = Join-Path $workPath 'FabMatrixTray.properties'
    Write-Utf8NoBom $launcherProperties @"
main-jar=FabMatrixTray.jar
main-class=com.example.farm.tray.FabMatrixTray
java-options=-Dfile.encoding=UTF-8
win-console=false
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
        '--java-options', '-Dfile.encoding=UTF-8',
        '--java-options', '-Dspring.profiles.active=local',
        '--java-options', '-Xrs',
        '--add-launcher', "FabMatrixTray=$launcherProperties"
    )
    Invoke-Checked $jpackage $jpackageArgs
    $appImageRoot = Join-Path $imagePath 'FabMatrixServer'
    if (-not (Test-Path -LiteralPath (Join-Path $appImageRoot 'FabMatrixServer.exe'))) { throw 'jpackage 未生成 FabMatrixServer.exe。' }
    if (-not (Test-Path -LiteralPath (Join-Path $appImageRoot 'FabMatrixTray.exe'))) { throw 'jpackage 未生成 FabMatrixTray.exe。' }
    Write-Host "App image: $appImageRoot"
    if ($Mode -eq 'AppImage') { return }

    Remove-Item -LiteralPath $payloadPath -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $payloadPath | Out-Null
    Copy-Item -Path (Join-Path $appImageRoot '*') -Destination $payloadPath -Recurse -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $payloadPath 'service') | Out-Null
    foreach ($name in @('Farm-Configure.ps1', 'install-service.ps1', 'uninstall-service.ps1', 'service-control.ps1')) {
        Copy-Item -LiteralPath (Join-Path $scriptRoot "service\$name") -Destination (Join-Path $payloadPath "service\$name") -Force
    }
    Copy-Item -LiteralPath $winswPath -Destination (Join-Path $payloadPath 'service\FabMatrixServerService.exe') -Force
    Copy-Item -LiteralPath $licensePath -Destination (Join-Path $payloadPath 'service\WinSW-LICENSE.txt') -Force
    Copy-Item -LiteralPath (Join-Path $scriptRoot 'README-Windows.md') -Destination (Join-Path $payloadPath 'README-Windows.md') -Force
    Write-Host "Payload: $payloadPath"
    if ($Mode -eq 'Payload') { return }

    New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
    $iss = Join-Path $scriptRoot 'installer\Farm.iss'
    $issArgs = @('/Qp', "/DAppVersion=$Version", "/DPayloadDir=$payloadPath", "/DOutputDir=$outputPath", $iss)
    Invoke-Checked $iscc $issArgs

    $installer = Join-Path $outputPath 'FabMatrixServer-Setup.exe'
    if (-not (Test-Path -LiteralPath $installer)) { throw "Inno Setup 未生成安装包: $installer" }
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
Default port: 8080
Data layout: config/database/files/logs/backups
Firewall: TCP 8080, Private profile only
"@
    Write-Utf8NoBom (Join-Path $outputPath 'release-info.txt') $releaseInfo
    Write-Host "Installer: $installer"
    Write-Host "Size: $installerSize bytes"
    Write-Host "SHA-256: $installerHash"
} finally {
    Pop-Location
}
