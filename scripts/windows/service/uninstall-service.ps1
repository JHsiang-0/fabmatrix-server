param(
    [string]$InstallDir = 'C:\Program Files\FabMatrix',
    [string]$DataRoot = 'C:\ProgramData\FabMatrix',
    [ValidateRange(1, 65535)]
    [int]$Port = 28081,
    [switch]$PurgeData,
    [string]$FailureMarker = ''
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

function Write-UninstallFailureMarker([string]$Message) {
    if ([string]::IsNullOrWhiteSpace($FailureMarker)) { return }
    try {
        $markerParent = Split-Path -LiteralPath $FailureMarker -Parent
        if (-not [string]::IsNullOrWhiteSpace($markerParent)) {
            [IO.Directory]::CreateDirectory($markerParent) | Out-Null
        }
        [IO.File]::WriteAllText($FailureMarker, $Message, (New-Object System.Text.UTF8Encoding($false)))
    } catch {
        Write-Error "Unable to write uninstall failure marker: $($_.Exception.Message)"
    }
}

function Show-UninstallFailure([string]$Message) {
    try {
        Add-Type -AssemblyName System.Windows.Forms -ErrorAction Stop
        [System.Windows.Forms.MessageBox]::Show($Message, 'FabMatrix 卸载失败',
            [System.Windows.Forms.MessageBoxButtons]::OK,
            [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    } catch {
        Write-Error "Unable to show the interactive uninstall failure message: $($_.Exception.Message)"
    }
}

try {
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

    if ($PurgeData) {
        $purgeScript = Join-Path $PSScriptRoot 'purge-local-data.ps1'
        if (-not (Test-Path -LiteralPath $purgeScript -PathType Leaf)) {
            throw "Complete uninstall script is missing: $purgeScript"
        }
        & $purgeScript -InstallDir $InstallDir -DataRoot $DataRoot
        if ($LASTEXITCODE -ne 0) { throw "Complete uninstall script failed with exit code $LASTEXITCODE." }
        Write-Output 'FabMatrixServer service, tray, firewall rule and business data removed.'
    } else {
        Write-Output "FabMatrixServer service and private firewall rule removed. Business data retained at: $DataRoot"
    }
} catch {
    $failure = "FabMatrix 卸载未完成。业务数据未确认完全删除，位置：$DataRoot`r`n$($_.Exception.Message)"
    Write-UninstallFailureMarker $failure
    Show-UninstallFailure $failure
    Write-Error $failure
    exit 1
}
