param(
    [string]$ServerRoot = '',
    [string]$ClientRoot = ''
)

$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$defaultServerRoot = (Resolve-Path (Join-Path $scriptRoot '..\..')).Path
$resolvedServerRoot = if ([string]::IsNullOrWhiteSpace($ServerRoot)) {
    $defaultServerRoot
} else {
    (Resolve-Path $ServerRoot).Path
}
$resolvedClientRoot = if ([string]::IsNullOrWhiteSpace($ClientRoot)) {
    (Resolve-Path (Join-Path $resolvedServerRoot '..\..\Vue\fabmatrix-client')).Path
} else {
    (Resolve-Path $ClientRoot).Path
}

$releaseVersion = '1.0.0'
$windowsFileVersion = '1.0.0.0'
$clientAppId = 'com.jiadong3d.fabmatrix.client'
$serverAppId = 'com.jiadong3d.fabmatrix.server'

function Get-RequiredFile([string]$Root, [string]$RelativePath) {
    $path = Join-Path $Root $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Release version contract file is missing: $path"
    }
    return $path
}

function Assert-ContainsText([string]$Path, [string]$Pattern, [string]$Description) {
    $content = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
    if ($content -notmatch $Pattern) {
        throw "Release version contract failed: $Description ($Path)"
    }
}

function Assert-EqualValue([string]$Actual, [string]$Expected, [string]$Description) {
    if ($Actual -ne $Expected) {
        throw "Release version contract failed: $Description (expected '$Expected', got '$Actual')"
    }
}

$clientPackagePath = Get-RequiredFile $resolvedClientRoot 'package.json'
$clientLockPath = Get-RequiredFile $resolvedClientRoot 'package-lock.json'
$pomPath = Get-RequiredFile $resolvedServerRoot 'pom.xml'
$installerPath = Get-RequiredFile $resolvedServerRoot 'scripts\windows\installer\Farm.iss'
$buildPath = Get-RequiredFile $resolvedServerRoot 'scripts\windows\build-installer.ps1'
$compatBuildPath = Get-RequiredFile $resolvedServerRoot 'scripts\build-local-installer.ps1'
$springDocPath = Get-RequiredFile $resolvedServerRoot 'src\main\java\com\example\farm\config\SpringDocConfig.java'
$trayPath = Get-RequiredFile $resolvedServerRoot 'src\main\java\com\example\farm\tray\FabMatrixTray.java'

$clientPackage = Get-Content -LiteralPath $clientPackagePath -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-EqualValue ([string]$clientPackage.version) $releaseVersion 'frontend package version'
Assert-EqualValue ([string]$clientPackage.build.appId) $clientAppId 'Electron client appId'
Assert-EqualValue ([string]$clientPackage.build.artifactName) 'FabMatrix-Client-Setup-${version}.${ext}' 'Electron installer artifact name'
Assert-ContainsText $clientLockPath '(?ms)^\{\s*"name"\s*:\s*"fabmatrix-client"\s*,\s*"version"\s*:\s*"1\.0\.0"' 'frontend lockfile root version'

$pomText = Get-Content -LiteralPath $pomPath -Raw -Encoding UTF8
if ($pomText -notmatch '(?s)<artifactId>\s*fabmatrix-server\s*</artifactId>\s*<version>\s*1\.0\.0\s*</version>') {
    throw "Release version contract failed: backend Maven project version ($pomPath)"
}
if ($pomText -match '(?s)<artifactId>\s*fabmatrix-server\s*</artifactId>\s*<version>[^<]*SNAPSHOT') {
    throw "Release version contract failed: backend Maven version still contains SNAPSHOT ($pomPath)"
}

Assert-ContainsText $installerPath '(?m)^#define AppVersion "1\.0\.0"' 'Inno default release version'
Assert-ContainsText $installerPath '(?m)^OutputBaseFilename=FabMatrix-Server-Setup-\{#AppVersion\}' 'server installer filename'
Assert-ContainsText $installerPath '(?m)^VersionInfoVersion=1\.0\.0\.0' 'Windows file version'
Assert-ContainsText $installerPath '(?m)^VersionInfoProductVersion=1\.0\.0\.0' 'Windows product file version'
Assert-ContainsText $buildPath "\[ValidateSet\('1\.0\.0'\)\]" 'Windows build version is fixed'
Assert-ContainsText $buildPath 'FabMatrix-Server-Setup-\$Version\.exe' 'server build output filename'
Assert-ContainsText $buildPath ([regex]::Escape($serverAppId)) 'server jpackage application ID'
Assert-ContainsText $compatBuildPath "\[ValidateSet\('1\.0\.0'\)\]" 'compatibility build version is fixed'
Assert-ContainsText $springDocPath '\.version\("1\.0\.0"\)' 'existing OpenAPI info version'
Assert-ContainsText $trayPath ([regex]::Escape($serverAppId)) 'tray application ID metadata'
Assert-ContainsText $trayPath 'APPLICATION_VERSION = "1\.0\.0"' 'tray application version metadata'

foreach ($path in @($clientPackagePath, $pomPath, $installerPath, $buildPath, $compatBuildPath, $springDocPath, $trayPath)) {
    $content = Get-Content -LiteralPath $path -Raw -Encoding UTF8
    if ($content -match '2\.0\.0|0\.0\.1-SNAPSHOT') {
        throw "Release version contract found a legacy release version in a versioned file: $path"
    }
}

Write-Output "FabMatrix release version contract passed: $releaseVersion / $windowsFileVersion"
