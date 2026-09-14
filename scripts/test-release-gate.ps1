$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gateScript = Join-Path $scriptRoot 'release-gate.ps1'
$source = Get-Content -Raw -LiteralPath $gateScript

if (-not $source.Contains('[string]$MavenPath =')) {
    throw 'Release gate does not expose optional -MavenPath.'
}
if (-not $source.Contains('Specified MavenPath does not exist')) {
    throw 'Release gate does not reject a missing explicit MavenPath.'
}
if (-not $source.Contains('Resolve-Path -LiteralPath $mavenCandidate')) {
    throw 'Release gate does not resolve an explicit MavenPath.'
}
if (-not $source.Contains('& $resolvedMavenPath ''test''') -or
    -not $source.Contains('& $resolvedMavenPath ''package'' ''-DskipTests''')) {
    throw 'Release gate does not execute both Maven phases through the resolved path.'
}
$testDataAssignment = '$localEditionTestData = Join-Path $serverRoot ''target\local-edition-test-data'''
if (-not $source.Contains($testDataAssignment) -or
    -not $source.Contains('New-Item -ItemType Directory -Force -Path $localEditionTestData')) {
    throw 'Release gate does not create the Local Edition test data directory.'
}
$testDataPosition = $source.IndexOf($testDataAssignment)
$mavenTestPosition = $source.IndexOf("Invoke-ReleaseStep 'Maven full test'")
if ($testDataPosition -lt 0 -or $mavenTestPosition -lt 0 -or $testDataPosition -gt $mavenTestPosition) {
    throw 'Local Edition test data directory must be created before Maven full test.'
}

$wrapperPosition = $source.IndexOf("Test-Path -LiteralPath (Join-Path `$serverRoot 'mvnw.cmd')")
$pathMavenPosition = $source.IndexOf('Get-Command mvn.cmd')
if ($wrapperPosition -lt 0 -or $pathMavenPosition -lt 0 -or $wrapperPosition -gt $pathMavenPosition) {
    throw 'Release gate no longer prefers repository mvnw.cmd over PATH mvn.cmd.'
}
$installerContract = Get-Content -Raw -LiteralPath (Join-Path $scriptRoot 'windows\test-installer-contract.ps1')
if (-not $installerContract.Contains('(?m)^icon=\$trayIconPropertyPath\r?$')) {
    throw 'Installer contract icon wiring check is not CRLF/LF robust.'
}
$candidateScriptPath = Join-Path $scriptRoot 'new-release-candidate.ps1'
$candidateBytes = [IO.File]::ReadAllBytes($candidateScriptPath)
if ($candidateBytes.Length -lt 3 -or $candidateBytes[0] -ne 0xEF -or
    $candidateBytes[1] -ne 0xBB -or $candidateBytes[2] -ne 0xBF) {
    throw 'Release candidate script must be UTF-8 with BOM for Windows PowerShell 5.1.'
}
$candidateOutput = & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $candidateScriptPath 2>&1
if ($LASTEXITCODE -ne 0) {
    throw 'Release candidate script could not be parsed or executed by powershell.exe -File.'
}
$buildInstaller = Get-Content -Raw -LiteralPath (Join-Path $scriptRoot 'windows\build-installer.ps1')
if (-not $buildInstaller.Contains('function Assert-NoShortNamePath') -or
    -not $buildInstaller.Contains('Assert-NoShortNamePath $candidatePath')) {
    throw 'Installer build path validation does not reject short-name components before normalization.'
}
Write-Host 'Release gate explicit MavenPath contract passed.'
