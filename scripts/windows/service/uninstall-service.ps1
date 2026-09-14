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

function Wait-ServiceAbsent([string]$Name, [int]$TimeoutSeconds = 30) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $service = Get-ServiceIfPresent $Name
        if ($null -eq $service) { return }
        $service.Dispose()
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Windows service is still pending deletion after $TimeoutSeconds seconds: $Name"
}

function Stop-ServiceIfPresent([string]$Name) {
    $service = Get-ServiceIfPresent $Name
    if ($null -eq $service) { return }
    try {
        if ($service.Status -eq [System.ServiceProcess.ServiceControllerStatus]::Stopped) { return }
        Stop-Service -InputObject $service -ErrorAction Stop
        $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(30))
        $service.Refresh()
        if ($service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
            throw "Service did not stop; uninstall aborted: $Name"
        }
    } finally {
        $service.Dispose()
    }
}

function Remove-ServiceIfPresent([string]$Name) {
    $service = Get-ServiceIfPresent $Name
    if ($null -eq $service) { return }
    $service.Dispose()
    Stop-ServiceIfPresent $Name
    & sc.exe delete $Name | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Windows service deletion failed ($LASTEXITCODE): $Name" }
    Wait-ServiceAbsent $Name
}

function Test-FabMatrixPathWithinDirectory([string]$Path, [string]$Root) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    $resolvedPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $resolvedRoot = [IO.Path]::GetFullPath($Root).TrimEnd('\')
    return [string]::Equals($resolvedPath, $resolvedRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $resolvedPath.StartsWith($resolvedRoot + '\', [StringComparison]::OrdinalIgnoreCase)
}

function Stop-FabMatrixTrayProcesses([string]$InstallDir) {
    $processes = @(Get-Process -Name 'FabMatrixTray', 'FarmTray' -ErrorAction SilentlyContinue)
    foreach ($process in $processes) {
        try {
            $processPath = $null
            try { $processPath = $process.MainModule.FileName } catch {
                throw "Unable to verify tray executable path for PID $($process.Id): $($_.Exception.Message)"
            }
            if (-not (Test-FabMatrixPathWithinDirectory $processPath $InstallDir)) { continue }

            if (-not $process.HasExited -and $process.MainWindowHandle -ne 0) {
                [void]$process.CloseMainWindow()
                [void]$process.WaitForExit(10000)
            }
            if (-not $process.HasExited) {
                Stop-Process -Id $process.Id -Force -ErrorAction Stop
                [void]$process.WaitForExit(10000)
            }
            if (-not $process.HasExited) {
                throw "Tray process did not stop; uninstall aborted: PID $($process.Id)"
            }
        } finally {
            $process.Dispose()
        }
    }
}

try {
    # Release only the known tray launchers before Inno removes EXE/runtime files.
    # This never targets backend or printer processes.
    Stop-FabMatrixTrayProcesses $InstallDir

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
