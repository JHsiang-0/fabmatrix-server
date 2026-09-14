param(
    [string]$ServerRoot = '',
    [string]$ClientRoot = '',
    [string[]]$ArtifactPath = @()
)

$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$resolvedServerRoot = if ([string]::IsNullOrWhiteSpace($ServerRoot)) {
    (Resolve-Path (Join-Path $scriptRoot '..')).Path
} else {
    (Resolve-Path $ServerRoot).Path
}
$resolvedClientRoot = if ([string]::IsNullOrWhiteSpace($ClientRoot)) { $null } else { (Resolve-Path $ClientRoot).Path }
$findings = @()

function Add-Finding([string]$Path, [string]$Rule, [string]$Detail) {
    $script:findings += [pscustomobject]@{ path = $Path; rule = $Rule; detail = $Detail }
}

function Assert-VersionContract {
    $pomPath = Join-Path $resolvedServerRoot 'pom.xml'
    $pom = Get-Content -Raw -LiteralPath $pomPath
    if ($pom -notmatch '(?s)<artifactId>\s*fabmatrix-server\s*</artifactId>\s*<version>\s*1\.0\.0\s*</version>') {
        Add-Finding 'pom.xml' 'version' 'Maven project version must be 1.0.0'
    }
    if ($pom -match '(?s)<artifactId>\s*fabmatrix-server\s*</artifactId>\s*<version>[^<]*SNAPSHOT') {
        Add-Finding 'pom.xml' 'version' 'Maven project must not contain SNAPSHOT'
    }
    if ($null -ne $resolvedClientRoot) {
        $clientPackagePath = Join-Path $resolvedClientRoot 'package.json'
        $clientPackage = Get-Content -Raw -LiteralPath $clientPackagePath | ConvertFrom-Json
        if ([string]$clientPackage.version -ne '1.0.0') { Add-Finding 'client/package.json' 'version' 'Frontend version must be 1.0.0' }
    }
}

function Test-TextPath([string]$Path, [string]$RelativePath, [bool]$Artifact) {
    $extension = [IO.Path]::GetExtension($Path).ToLowerInvariant()
    if (@('.bat', '.cmd', '.css', '.html', '.ini', '.iss', '.java', '.json', '.md', '.mjs', '.properties', '.ps1', '.txt', '.xml', '.yaml', '.yml') -notcontains $extension) { return }
    if ($Path -match '(?i)(^|[\\/])\.env(?!\.example)(?:\.[^\\/]*)?$') {
        Add-Finding $RelativePath 'environment-file' 'Environment files are not release inputs'
        return
    }
    $content = Get-Content -Raw -LiteralPath $Path -Encoding UTF8
    $rules = if ($Artifact) {
        @(
            @{ Name = 'mock-mode'; Pattern = '(?i)desktop-mock|FARM_ELECTRON_E2E|VITE_USE_MOCK\s*[:=]\s*["'']?true|farmMode\s*[:=]\s*["'']mock' },
            @{ Name = 'development-credential'; Pattern = '(?i)Farm3DPrinterSuperSecretKey|FarmAdmin2024|Admin123|admin123|JWT_SECRET_KEY\s*[:=]\s*[^$\{\s]|ADMIN_SECRET_KEY\s*[:=]\s*[^$\{\s]' },
            @{ Name = 'sample-data'; Pattern = '(?i)gearbox\.gcode|Printer_C0DA|createTc008HistoryJobs' }
        )
    } else {
        @(
            @{ Name = 'hard-coded-credential'; Pattern = '(?i)(Farm3DPrinterSuperSecretKey|FarmAdmin2024|Admin123|admin123)' },
            @{ Name = 'hard-coded-environment-value'; Pattern = '(?i)(JWT_SECRET_KEY|ADMIN_SECRET_KEY|MYSQL_PASSWORD|REDIS_PASSWORD|RUSTFS_SECRET_KEY)\s*[:=]\s*["''](?!\$\{)[^"'']{8,}["'']' }
        )
    }
    foreach ($rule in $rules) {
        if ($content -match $rule.Pattern) { Add-Finding $RelativePath $rule.Name 'Release safety rule matched' }
    }
}

Assert-VersionContract
$requiredSourceFiles = @(
    'pom.xml',
    'scripts\windows\installer\Farm.iss',
    'scripts\windows\build-installer.ps1',
    'scripts\windows\README-Windows.md'
)
foreach ($relativePath in $requiredSourceFiles) {
    $path = Join-Path $resolvedServerRoot $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Add-Finding $relativePath 'missing-input' 'Release scan input is missing'; continue }
    Test-TextPath $path $relativePath $false
}

$optionalSourceFiles = @('src\main\resources\application-prod.yaml')
foreach ($relativePath in $optionalSourceFiles) {
    $path = Join-Path $resolvedServerRoot $relativePath
    if (Test-Path -LiteralPath $path -PathType Leaf) { Test-TextPath $path $relativePath $false }
}

foreach ($inputPath in $ArtifactPath) {
    $resolvedArtifact = (Resolve-Path $inputPath).Path
    $artifactItem = Get-Item -LiteralPath $resolvedArtifact
    $artifactRoot = if ($artifactItem.PSIsContainer) { $resolvedArtifact } else { Split-Path $resolvedArtifact -Parent }
    $artifactFiles = if ($artifactItem.PSIsContainer) {
        @(Get-ChildItem -LiteralPath $resolvedArtifact -Recurse -File | Where-Object { $_.FullName -notmatch '(?i)[\\/](node_modules|target|logs|test-results)[\\/]' })
    } else { @($artifactItem) }
    foreach ($file in $artifactFiles) {
        $relativePath = [IO.Path]::GetRelativePath($artifactRoot, $file.FullName).Replace('\', '/')
        Test-TextPath $file.FullName $relativePath $true
    }
}

$result = [pscustomobject]@{
    schemaVersion = 1
    product = 'FabMatrix Server'
    version = '1.0.0'
    status = if ($findings.Count -eq 0) { 'PASS' } else { 'FAIL' }
    findings = @($findings)
}
Write-Output ($result | ConvertTo-Json -Depth 5)
if ($findings.Count -gt 0) { exit 1 }
