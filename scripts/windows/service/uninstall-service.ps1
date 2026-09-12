param(
    [string]$InstallDir = 'C:\Program Files\FabMatrix',
    [string]$DataRoot = 'C:\ProgramData\FabMatrix',
    [ValidateRange(1, 65535)]
    [int]$Port = 28081
)

$ErrorActionPreference = 'Stop'

function Get-ServiceIfPresent([string]$Name) {
    return Get-Service -Name $Name -ErrorAction SilentlyContinue
}

function Stop-ServiceIfPresent([string]$Name) {
    $service = Get-ServiceIfPresent $Name
    if ($null -eq $service -or $service.Status -eq [System.ServiceProcess.ServiceControllerStatus]::Stopped) { return }
    Stop-Service -InputObject $service -ErrorAction Stop
    $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(30))
    $service.Refresh()
    if ($service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
        throw "Service did not stop; uninstall aborted: $Name"
    }
}

function Remove-ServiceIfPresent([string]$Name) {
    $service = Get-ServiceIfPresent $Name
    if ($null -eq $service) { return }
    Stop-ServiceIfPresent $Name
    & sc.exe delete $Name | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Windows service deletion failed ($LASTEXITCODE): $Name" }
}

# Release only the known tray launchers before Inno removes EXE/runtime files.
# This never targets backend or printer processes.
Get-Process -Name 'FabMatrixTray', 'FarmTray' -ErrorAction SilentlyContinue |
    Stop-Process -Force -ErrorAction Stop

Remove-ServiceIfPresent 'FabMatrixServer'
# Remove the legacy registration only; legacy Farm data is retained.
Remove-ServiceIfPresent 'Farm'

if (-not (Get-Command Get-NetFirewallRule -ErrorAction SilentlyContinue)) {
    throw 'Windows Firewall PowerShell cmdlets are required to remove the Private inbound rule.'
}
$ruleName = 'FabMatrixServer-Private-TCP'
$rule = Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
if ($null -ne $rule) {
    Remove-NetFirewallRule -Name $ruleName -ErrorAction Stop
}

Write-Output "FabMatrixServer service and private firewall rule removed. Business data retained at: $DataRoot"
