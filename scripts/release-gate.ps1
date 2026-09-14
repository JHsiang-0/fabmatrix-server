param(
    [string]$ClientRoot = '',
    [switch]$AllowDirtyWorkspace,
    [switch]$SkipExternalTools,
    [string]$CandidateRecordPath = ''
)

$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$serverRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path
$resolvedClientRoot = if ([string]::IsNullOrWhiteSpace($ClientRoot)) {
    (Resolve-Path (Join-Path $serverRoot '..\..\Vue\fabmatrix-client')).Path
} else {
    (Resolve-Path $ClientRoot).Path
}

function Invoke-ReleaseStep([string]$Name, [scriptblock]$Action) {
    Write-Host "`n[RELEASE GATE] $Name"
    & $Action
    if ($LASTEXITCODE -ne 0) { throw "Release gate step failed: $Name ($LASTEXITCODE)" }
    Write-Host "[RELEASE GATE] PASS: $Name"
}

function Assert-WorkspaceClean([string]$Root, [string]$Label) {
    $status = @(git -C $Root status --short)
    if ($status.Count -eq 0) { return }
    if ($AllowDirtyWorkspace) {
        Write-Host "[RELEASE GATE] NOT RUN: $Label workspace clean check ($($status -join '; '))"
        return
    }
    throw "$Label workspace is not clean. Commit or isolate all changes before candidate generation: $($status -join '; ')"
}

Assert-WorkspaceClean $serverRoot 'server'
Assert-WorkspaceClean $resolvedClientRoot 'client'
Invoke-ReleaseStep 'release scan contract' {
    & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $serverRoot 'scripts\test-release-scan.ps1')
}
Invoke-ReleaseStep 'version and source safety scan' {
    & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $serverRoot 'scripts\release-scan.ps1') -ServerRoot $serverRoot -ClientRoot $resolvedClientRoot
}

$maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($null -eq $maven -and (Test-Path -LiteralPath (Join-Path $serverRoot 'mvnw.cmd'))) {
    $mavenPath = Join-Path $serverRoot 'mvnw.cmd'
} elseif ($null -ne $maven) {
    $mavenPath = $maven.Source
} else {
    throw 'Maven or mvnw.cmd was not found.'
}
Push-Location $serverRoot
try {
    Invoke-ReleaseStep 'Maven full test' { & $mavenPath 'test' }
    Invoke-ReleaseStep 'Maven package build' { & $mavenPath 'package' '-DskipTests' }
} finally {
    Pop-Location
}

$installerContract = Join-Path $serverRoot 'scripts\windows\test-installer-contract.ps1'
$inno = Get-Command ISCC.exe -ErrorAction SilentlyContinue
$contractArgs = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $installerContract, '-ClientRoot', $resolvedClientRoot)
if ($SkipExternalTools -or $null -eq $inno) {
    if ($null -eq $inno) { Write-Host '[RELEASE GATE] NOT RUN: Inno Setup ISCC.exe unavailable; static installer contract checks will run.' }
    else { Write-Host '[RELEASE GATE] NOT RUN: external installer tools explicitly skipped.' }
    $contractArgs += '-SkipExternalTools'
}
Invoke-ReleaseStep 'Windows installer contract' { & powershell.exe @contractArgs }

if (-not [string]::IsNullOrWhiteSpace($CandidateRecordPath)) {
    if ($AllowDirtyWorkspace) { throw 'Candidate acceptance record cannot be generated with -AllowDirtyWorkspace.' }
    $candidateScript = Join-Path $serverRoot 'scripts\new-release-candidate.ps1'
    & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $candidateScript -AutomaticGateStatus PASS -OutputPath $CandidateRecordPath
    if ($LASTEXITCODE -ne 0) { throw "Candidate acceptance record generation failed: $LASTEXITCODE" }
    Write-Host "[RELEASE GATE] Candidate acceptance template: $CandidateRecordPath"
}

Write-Host "`n[RELEASE GATE] PASS: version, Maven test/package and installer contract"
Write-Host '[RELEASE GATE] Manual Windows install/upgrade/uninstall and real-device checks remain NOT RUN until recorded on clean Windows hardware.'
