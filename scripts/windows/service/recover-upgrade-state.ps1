param([Parameter(Mandatory = $true)][string]$StateFile)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $StateFile -PathType Leaf)) { return }

$state = Get-Content -LiteralPath $StateFile -Raw | ConvertFrom-Json
$serviceName = [string]$state.ExistingServiceName
if ([string]::IsNullOrWhiteSpace($serviceName)) { return }
if ($null -eq (Get-Service -Name $serviceName -ErrorAction SilentlyContinue)) {
    Write-Warning "Original service no longer exists and cannot be restored: $serviceName"
    return
}

$scStartMode = switch ([string]$state.StartMode) {
    'Auto' { 'auto' }
    'Disabled' { 'disabled' }
    default { 'demand' }
}
& sc.exe config $serviceName 'start=' $scStartMode | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to restore service start mode ($LASTEXITCODE): $serviceName" }

if ([bool]$state.WasRunning -and $scStartMode -ne 'disabled') {
    & sc.exe start $serviceName | Out-Null
    if ($LASTEXITCODE -notin @(0, 1056)) { throw "Unable to restart original service ($LASTEXITCODE): $serviceName" }
}

Write-Output "Original service state restoration attempted: $serviceName, $($state.StartMode), wasRunning=$($state.WasRunning)"
