param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('start', 'stop', 'restart')]
    [string]$Action
)

$ErrorActionPreference = 'Stop'
$serviceName = 'FabMatrixServer'
$defaultPort = 28081
$installDir = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
. (Join-Path $installDir 'service\health-check.ps1')

function ConvertTo-FabMatrixPort([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $candidate = $Value.Trim() -replace '#.*$', ''
    $candidate = $candidate.Trim().Trim("'").Trim('"')
    $placeholder = [regex]::Match($candidate, '^\$\{\s*SERVER_PORT\s*:\s*(\d{1,5})\s*\}$')
    if ($placeholder.Success) { $candidate = $placeholder.Groups[1].Value }
    if ($candidate -notmatch '^\d{1,5}$') { return $null }
    $parsed = 0
    if (-not [int]::TryParse($candidate, [ref]$parsed) -or $parsed -lt 1 -or $parsed -gt 65535) { return $null }
    return $parsed
}

function Get-FabMatrixConfiguredPort([string]$ConfigPath) {
    if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) { return $null }
    $inServer = $false
    $serverIndent = -1
    foreach ($line in (Get-Content -LiteralPath $ConfigPath -Encoding UTF8)) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith('#')) { continue }
        $indent = $line.Length - $line.TrimStart().Length
        if (-not $inServer) {
            if ($trimmed -match '^server:\s*(?:#.*)?$') {
                $inServer = $true
                $serverIndent = $indent
            }
            continue
        }
        if ($indent -le $serverIndent) { break }
        if ($trimmed -match '^port\s*:\s*(.+?)\s*(?:#.*)?$') {
            return ConvertTo-FabMatrixPort $Matches[1]
        }
    }
    return $null
}

function Get-FabMatrixServiceDataRoot {
    $commonData = [Environment]::GetFolderPath([Environment+SpecialFolder]::CommonApplicationData)
    if ([string]::IsNullOrWhiteSpace($commonData)) { $commonData = $env:ProgramData }
    $systemDrive = [IO.Path]::GetPathRoot([Environment]::SystemDirectory)
    $installDrive = [IO.Path]::GetPathRoot($installDir)
    if ([string]::Equals($systemDrive, $installDrive, [StringComparison]::OrdinalIgnoreCase)) {
        return Join-Path $commonData 'FabMatrix'
    }
    return Join-Path $installDir 'data'
}

function Get-FabMatrixEffectivePort {
    $metadataPath = Join-Path $installDir 'service-state\effective-port.txt'
    $metadataPort = if (Test-Path -LiteralPath $metadataPath -PathType Leaf) {
        ConvertTo-FabMatrixPort (Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8)
    } else { $null }
    if ($null -ne $metadataPort) { return $metadataPort }

    $environmentPort = ConvertTo-FabMatrixPort ([Environment]::GetEnvironmentVariable('SERVER_PORT'))
    if ($null -ne $environmentPort) { return $environmentPort }

    $configuredPort = Get-FabMatrixConfiguredPort (Join-Path (Get-FabMatrixServiceDataRoot) 'config\application.yml')
    # A missing/legacy 8080 value must not make a repaired installation probe the
    # wrong endpoint. An explicit SERVER_PORT=8080 above remains supported.
    if ($null -ne $configuredPort -and $configuredPort -ne 8080) { return $configuredPort }
    return $defaultPort
}

function Get-RequiredService {
    $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if ($null -eq $service) { throw "Windows service not found: $serviceName" }
    return $service
}

function Wait-ServiceStatus($Service, [System.ServiceProcess.ServiceControllerStatus]$Expected) {
    $Service.WaitForStatus($Expected, [TimeSpan]::FromSeconds(30))
    $Service.Refresh()
    if ($Service.Status -ne $Expected) { throw "Service did not reach expected state: $serviceName -> $Expected" }
}

function Test-FabMatrixHealth([int]$Port) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 2 -ErrorAction Stop
        return Test-FabMatrixHealthResponse -Response $response
    } catch {
        return $false
    }
}

function Wait-FabMatrixHealth([int]$Port, [int]$TimeoutSeconds = 60) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (Test-FabMatrixHealth $Port) { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "FabMatrixServer health check failed after $TimeoutSeconds seconds: http://127.0.0.1:$Port/actuator/health"
}

function Start-FabMatrixServer {
    $service = Get-RequiredService
    try {
        if ($service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Running) {
            Start-Service -InputObject $service -ErrorAction Stop
            Wait-ServiceStatus $service ([System.ServiceProcess.ServiceControllerStatus]::Running)
        }
        Wait-FabMatrixHealth (Get-FabMatrixEffectivePort)
    } finally {
        $service.Dispose()
    }
}

function Stop-FabMatrixServer {
    $service = Get-RequiredService
    try {
        if ($service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
            Stop-Service -InputObject $service -ErrorAction Stop
            Wait-ServiceStatus $service ([System.ServiceProcess.ServiceControllerStatus]::Stopped)
        }
    } finally {
        $service.Dispose()
    }
}

switch ($Action) {
    'start' { Start-FabMatrixServer }
    'stop' { Stop-FabMatrixServer }
    'restart' { Stop-FabMatrixServer; Start-FabMatrixServer }
}
Write-Output "FabMatrixServer $Action completed"
