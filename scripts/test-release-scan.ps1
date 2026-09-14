$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$scanScript = Join-Path $scriptRoot 'release-scan.ps1'
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixReleaseScanContract-' + [guid]::NewGuid().ToString('N'))

try {
    New-Item -ItemType Directory -Force -Path (Join-Path $fixtureRoot 'scripts\windows\installer') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $fixtureRoot 'scripts\windows') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $fixtureRoot 'src\main\resources') | Out-Null
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'pom.xml') -Value '<artifactId>fabmatrix-server</artifactId><version>1.0.0</version>' -Encoding UTF8
    foreach ($relativePath in @('scripts\windows\installer\Farm.iss', 'scripts\windows\build-installer.ps1', 'scripts\windows\README-Windows.md')) {
        Set-Content -LiteralPath (Join-Path $fixtureRoot $relativePath) -Value '' -Encoding UTF8
    }

    $output = & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $scanScript -ServerRoot $fixtureRoot | Out-String
    if ($LASTEXITCODE -ne 0) { throw "Release scan rejected a clean fixture without application-prod.yaml: $LASTEXITCODE`n$output" }
    $result = $output | ConvertFrom-Json
    if ($result.status -ne 'PASS') { throw "Release scan fixture did not pass: $output" }
    if (@($result.findings | Where-Object { $_.rule -eq 'missing-input' }).Count -ne 0) {
        throw 'Optional application-prod.yaml was reported as a missing input.'
    }
    Write-Host 'Release scan optional application-prod.yaml contract passed.'
} finally {
    if (Test-Path -LiteralPath $fixtureRoot) { Remove-Item -LiteralPath $fixtureRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
