param(
    [Parameter(Mandatory = $true)][string]$StateFile
)

$ErrorActionPreference = 'Stop'

function Get-ServiceSnapshot([string]$Name) {
    $service = Get-CimInstance Win32_Service -Filter "Name='$Name'" -ErrorAction SilentlyContinue
    if ($null -eq $service) { return $null }
    return [pscustomobject]@{
        ExistingServiceName = $Name
        StartMode = [string]$service.StartMode
        WasRunning = ([string]$service.State -eq 'Running')
    }
}

function Stop-ServiceReliably([string]$Name) {
    $service = Get-Service -Name $Name -ErrorAction SilentlyContinue
    if ($null -eq $service -or $service.Status -eq [System.ServiceProcess.ServiceControllerStatus]::Stopped) { return }
    Stop-Service -InputObject $service -ErrorAction Stop
    $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(30))
    $service.Refresh()
    if ($service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
        throw "Service did not stop normally: $Name"
    }
}

$snapshot = Get-ServiceSnapshot 'FabMatrixServer'
if ($null -eq $snapshot) { $snapshot = Get-ServiceSnapshot 'Farm' }
if ($null -eq $snapshot) {
    $snapshot = [pscustomobject]@{ ExistingServiceName = $null; StartMode = $null; WasRunning = $false }
}

$statePath = [IO.Path]::GetFullPath($StateFile)
$stateParent = Split-Path -Parent $statePath
New-Item -ItemType Directory -Force -Path $stateParent | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($statePath, ($snapshot | ConvertTo-Json -Compress), $utf8)

# Only the two known services and tray executables are stopped. Backend processes are never force-killed.
Stop-ServiceReliably 'FabMatrixServer'
Stop-ServiceReliably 'Farm'
Get-Process -Name 'FabMatrixTray', 'FarmTray' -ErrorAction SilentlyContinue |
    Stop-Process -Force -ErrorAction Stop
