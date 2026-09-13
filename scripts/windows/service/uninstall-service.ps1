param(
    [string]$InstallDir = 'C:\Program Files\FabMatrix',
    [string]$DataRoot = 'C:\ProgramData\FabMatrix',
    [string]$LegacyDataRoot = 'C:\ProgramData\Farm',
    [string]$DiagnosticsRoot = 'C:\ProgramData\FabMatrix',
    [ValidateRange(1, 65535)]
    [int]$Port = 28081,
    [switch]$PurgeData,
    [switch]$Silent
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
        & $purgeScript -InstallDir $InstallDir -DataRoot $DataRoot -LegacyDataRoot $LegacyDataRoot `
            -DiagnosticsRoot $DiagnosticsRoot
        Write-Output 'FabMatrixServer service, tray, firewall rule, current data, legacy Farm data and diagnostics removed.'
    } else {
        Write-Output "FabMatrixServer service and private firewall rule removed. Current data retained at: $DataRoot; legacy data retained at: $LegacyDataRoot; diagnostics retained at: $DiagnosticsRoot"
    }
} catch {
    $failure = "FabMatrix 卸载未完成。数据未确认完全删除。当前数据：$DataRoot；旧版数据：$LegacyDataRoot；诊断残留：$DiagnosticsRoot`r`n$($_.Exception.Message)"
    if ($Silent) {
        [Console]::Error.WriteLine($failure)
    } else {
        Write-Error -Message $failure -ErrorAction Continue
    }
    exit 1
}
