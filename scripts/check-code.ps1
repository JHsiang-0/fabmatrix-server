param(
    [switch]$SkipTests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

Push-Location -LiteralPath $projectRoot
try {
    & git diff --check
    if ($LASTEXITCODE -ne 0) {
        throw 'Whitespace check failed. Fix the reported changes first.'
    }

    # Includes new source files as well as tracked changes. Never read local secrets.
    $paths = @(& git diff --name-only --diff-filter=ACMR) +
        @(& git ls-files --others --exclude-standard)
    foreach ($relativePath in ($paths | Select-Object -Unique)) {
        if ($relativePath -notmatch '\.(java|xml|ya?ml|ps1|properties)$') { continue }
        $absolutePath = Join-Path $projectRoot $relativePath
        if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) { continue }
        $source = [System.IO.File]::ReadAllText($absolutePath)
        if ($source -match '(?m)[\t ]+$' -or $source.Contains("`t")) {
            throw "Tabs or trailing whitespace found: $relativePath"
        }
        if ($source.Length -gt 0 -and -not $source.EndsWith("`n")) {
            throw "Missing final newline: $relativePath"
        }
    }

    $maven = if ($IsWindows) { Join-Path $projectRoot 'mvnw.cmd' } else { Join-Path $projectRoot 'mvnw' }
    $goal = if ($SkipTests) { 'compile' } else { 'test' }
    & $maven --batch-mode $goal
    if ($LASTEXITCODE -ne 0) {
        throw "Maven $goal failed."
    }
    Write-Host "Code checks passed ($goal)."
}
finally {
    Pop-Location
}
