param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('start', 'stop', 'restart')]
    [string]$Action
)

$ErrorActionPreference = 'Stop'
$serviceName = 'FabMatrixServer'

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

function Start-FabMatrixServer {
    $service = Get-RequiredService
    if ($service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Running) {
        Start-Service -InputObject $service -ErrorAction Stop
        Wait-ServiceStatus $service ([System.ServiceProcess.ServiceControllerStatus]::Running)
    }
}

function Stop-FabMatrixServer {
    $service = Get-RequiredService
    if ($service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
        Stop-Service -InputObject $service -ErrorAction Stop
        Wait-ServiceStatus $service ([System.ServiceProcess.ServiceControllerStatus]::Stopped)
    }
}

switch ($Action) {
    'start' { Start-FabMatrixServer }
    'stop' { Stop-FabMatrixServer }
    'restart' { Stop-FabMatrixServer; Start-FabMatrixServer }
}
Write-Output "FabMatrixServer $Action completed"
