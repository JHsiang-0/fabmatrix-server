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

$wrapperPosition = $source.IndexOf("Test-Path -LiteralPath (Join-Path `$serverRoot 'mvnw.cmd')")
$pathMavenPosition = $source.IndexOf('Get-Command mvn.cmd')
if ($wrapperPosition -lt 0 -or $pathMavenPosition -lt 0 -or $wrapperPosition -gt $pathMavenPosition) {
    throw 'Release gate no longer prefers repository mvnw.cmd over PATH mvn.cmd.'
}
Write-Host 'Release gate explicit MavenPath contract passed.'
