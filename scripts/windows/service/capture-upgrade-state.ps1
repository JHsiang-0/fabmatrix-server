param(
    [Parameter(Mandatory = $true)][string]$StateFile,
    [Parameter(Mandatory = $true)][string]$InstallDir,
    [string]$ProgressFile = '',
    [string]$LogPath = ''
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'installation-progress.ps1')
$serviceNames = @('FabMatrixServer', 'Farm')
$trayExecutableNames = @('FabMatrixTray.exe', 'FarmTray.exe')
$maxRecoveryAttempts = 3

function Get-NormalizedFullPath([string]$Path) {
    return [IO.Path]::GetFullPath($Path).TrimEnd('\')
}

function Test-PathWithinDirectory([string]$Root, [string]$Candidate) {
    if ([string]::IsNullOrWhiteSpace($Candidate)) { return $false }
    try {
        $rootPath = Get-NormalizedFullPath $Root
        $candidatePath = Get-NormalizedFullPath $Candidate
    } catch {
        return $false
    }
    return [string]::Equals($rootPath, $candidatePath, [StringComparison]::OrdinalIgnoreCase) -or
        $candidatePath.StartsWith($rootPath + '\', [StringComparison]::OrdinalIgnoreCase)
}

function Test-FabMatrixExecutablePath([string]$Path, [string]$Root) {
    if (-not (Test-PathWithinDirectory $Root $Path)) { return $false }
    $rootPath = Get-NormalizedFullPath $Root
    $candidatePath = Get-NormalizedFullPath $Path
    $relativePath = $candidatePath.Substring($rootPath.Length).TrimStart('\')
    if ($relativePath -in @('FabMatrixServer.exe', 'FabMatrixTray.exe', 'FarmTray.exe', 'service\FabMatrixServerService.exe')) {
        return $true
    }
    return $relativePath -match '^runtime\\bin\\java(?:w)?\.exe$'
}

function Get-ServiceSnapshot([string]$Name) {
    $service = Get-CimInstance Win32_Service -Filter "Name='$Name'" -ErrorAction SilentlyContinue
    if ($null -eq $service) { return $null }
    return [pscustomobject]@{
        ExistingServiceName = $Name
        StartMode = [string]$service.StartMode
        WasRunning = ([string]$service.State -eq 'Running')
    }
}

function Get-ServiceController([string]$Name) {
    return Get-Service -Name $Name -ErrorAction SilentlyContinue
}

function Disable-ServiceStartDuringUpgrade([string]$Name) {
    $service = Get-ServiceController $Name
    if ($null -eq $service) { return }
    try {
        & sc.exe config $Name 'start=' 'disabled' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Unable to temporarily disable service start ($LASTEXITCODE): $Name" }
    } finally {
        $service.Dispose()
    }
}

function Stop-ServiceReliably([string]$Name) {
    $service = Get-ServiceController $Name
    if ($null -eq $service) { return }
    try {
        $service.Refresh()
        if ($service.Status -eq [System.ServiceProcess.ServiceControllerStatus]::Stopped) { return }
        Stop-Service -InputObject $service -ErrorAction Stop
        $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(30))
        $service.Refresh()
        if ($service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
            throw "Service did not stop normally: $Name"
        }
    } finally {
        $service.Dispose()
    }
}

function Get-FabMatrixProcessSnapshot([string]$Root) {
    $processes = @(Get-CimInstance Win32_Process -ErrorAction Stop)
    $owned = @{}
    foreach ($process in $processes) {
        if (Test-FabMatrixExecutablePath ([string]$process.ExecutablePath) $Root) {
            $owned[[int]$process.ProcessId] = $process
        }
    }

    $changed = $true
    while ($changed) {
        $changed = $false
        foreach ($process in $processes) {
            $processId = [int]$process.ProcessId
            if ($owned.ContainsKey($processId) -or -not $owned.ContainsKey([int]$process.ParentProcessId)) { continue }
            if (Test-PathWithinDirectory $Root ([string]$process.ExecutablePath)) {
                $owned[$processId] = $process
                $changed = $true
            }
        }
    }
    return @($owned.Values)
}

function Get-FabMatrixTrayProcesses([string]$Root) {
    return @(Get-FabMatrixProcessSnapshot $Root | Where-Object {
            [IO.Path]::GetFileName([string]$_.ExecutablePath) -in $trayExecutableNames
        })
}

function Request-FabMatrixTrayExit([string]$Root) {
    foreach ($processInfo in @(Get-FabMatrixTrayProcesses $Root)) {
        $process = Get-Process -Id ([int]$processInfo.ProcessId) -ErrorAction SilentlyContinue
        if ($null -eq $process) { continue }
        try {
            if ($process.HasExited) { continue }
            if ($process.MainWindowHandle -ne 0) {
                [void]$process.CloseMainWindow()
                [void]$process.WaitForExit(2000)
            }
            if (-not $process.HasExited) {
                # A tray without a main window cannot handle CloseMainWindow.
                # It owns no production tasks. Recheck PID/path before ending
                # only this installation's tray; services still stop via SCM.
                $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($processInfo.ProcessId)" -ErrorAction Stop
                if ($null -ne $current) {
                    $currentPath = [string]$current.ExecutablePath
                    if (-not (Test-FabMatrixExecutablePath $currentPath $Root) -or
                        [IO.Path]::GetFileName($currentPath) -notin $trayExecutableNames -or
                        -not [string]::Equals($currentPath, [string]$processInfo.ExecutablePath, [StringComparison]::OrdinalIgnoreCase) -or
                        $current.CreationDate -ne $processInfo.CreationDate) {
                        throw 'Tray identity changed during upgrade; refusing to terminate it.'
                    }
                    Write-FabMatrixInstallEvent $LogPath "Closing verified tray PID=$($processInfo.ProcessId) path=$currentPath"
                    Stop-Process -InputObject $process -Force -ErrorAction Stop
                    if (-not $process.WaitForExit(3000)) { throw 'Tray did not exit after bounded cleanup.' }
                }
            }
        } finally {
            $process.Dispose()
        }
    }
}

function Stop-FabMatrixResidualProcesses([string]$Root) {
    $residual = @(Get-FabMatrixProcessSnapshot $Root)
    foreach ($processInfo in $residual | Sort-Object ParentProcessId -Descending) {
        $processPath = [string]$processInfo.ExecutablePath
        if (-not (Test-FabMatrixExecutablePath $processPath $Root)) {
            throw "Refusing to terminate an unverified process: PID $($processInfo.ProcessId)"
        }
        Stop-Process -Id ([int]$processInfo.ProcessId) -Force -ErrorAction Stop
    }
    return $residual.Count
}

function Wait-FabMatrixProcessesExited([string]$Root, [int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        Write-FabMatrixInstallHeartbeat
        $remaining = @(Get-FabMatrixProcessSnapshot $Root)
        if ($remaining.Count -eq 0) { return $true }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    return $false
}

function ConvertTo-ScStartMode([string]$StartMode) {
    switch ($StartMode) {
        'Auto' { return 'auto' }
        'Disabled' { return 'disabled' }
        default { return 'demand' }
    }
}

function Restore-ServiceSnapshotBestEffort($Snapshot) {
    if ($null -eq $Snapshot -or [string]::IsNullOrWhiteSpace([string]$Snapshot.ExistingServiceName)) { return }
    $name = [string]$Snapshot.ExistingServiceName
    $service = Get-ServiceController $name
    if ($null -eq $service) { return }
    $service.Dispose()
    try {
        & sc.exe config $name 'start=' (ConvertTo-ScStartMode ([string]$Snapshot.StartMode)) | Out-Null
        if ($LASTEXITCODE -eq 0 -and [bool]$Snapshot.WasRunning -and [string]$Snapshot.StartMode -ne 'Disabled') {
            & sc.exe start $name | Out-Null
        }
    } catch {
        Write-Warning "Best-effort service state recovery failed for ${name}: $($_.Exception.Message)"
    }
}

$installRoot = Get-NormalizedFullPath $InstallDir
$statePath = [IO.Path]::GetFullPath($StateFile)
$stateParent = Split-Path -Parent $statePath
Set-FabMatrixInstallStage 'capture service state' $ProgressFile $LogPath
$snapshots = @($serviceNames | ForEach-Object { Get-ServiceSnapshot $_ } | Where-Object { $null -ne $_ })
$snapshot = $snapshots | Select-Object -First 1
if ($null -eq $snapshot) {
    $snapshot = [pscustomobject]@{ ExistingServiceName = $null; StartMode = $null; WasRunning = $false }
}

# Write the upgrade handoff before changing any service state. The installer can
# then recover the original service if this preflight fails before file copy.
New-Item -ItemType Directory -Force -Path $stateParent | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($statePath, ($snapshot | ConvertTo-Json -Compress), $utf8)

try {
    # A disabled SCM entry prevents WinSW onfailure=restart from racing this
    # preflight after the normal stop request. The original mode is in state.
    foreach ($serviceSnapshot in $snapshots) {
        Disable-ServiceStartDuringUpgrade ([string]$serviceSnapshot.ExistingServiceName)
    }

    Set-FabMatrixInstallStage 'close previous tray' $ProgressFile $LogPath
    Request-FabMatrixTrayExit $installRoot
    foreach ($name in $serviceNames) {
        Set-FabMatrixInstallStage ('stop service ' + $name) $ProgressFile $LogPath
        Stop-ServiceReliably $name
    }

    Set-FabMatrixInstallStage 'wait for previous processes' $ProgressFile $LogPath
    if (Wait-FabMatrixProcessesExited $installRoot 45) {
        Set-FabMatrixInstallStage 'preflight complete' $ProgressFile $LogPath
        Write-Output "FabMatrix upgrade preflight completed for install directory: $installRoot"
        return
    }

    # Normal shutdown had a bounded grace period. Any fallback termination is
    # restricted to verified FabMatrix executables under this exact {app} path.
    for ($attempt = 1; $attempt -le $maxRecoveryAttempts; $attempt++) {
        Set-FabMatrixInstallStage ('recover previous processes ' + $attempt) $ProgressFile $LogPath
        foreach ($serviceSnapshot in $snapshots) {
            Disable-ServiceStartDuringUpgrade ([string]$serviceSnapshot.ExistingServiceName)
        }
        foreach ($name in $serviceNames) {
            Stop-ServiceReliably $name
        }
        [void](Stop-FabMatrixResidualProcesses $installRoot)
        if (Wait-FabMatrixProcessesExited $installRoot 15) {
            Set-FabMatrixInstallStage 'preflight complete' $ProgressFile $LogPath
            Write-Output "FabMatrix upgrade preflight completed after bounded recovery attempt $attempt."
            return
        }
    }

    $remaining = @(Get-FabMatrixProcessSnapshot $installRoot)
    $remainingNames = @($remaining | ForEach-Object { "PID $($_.ProcessId) $($_.ExecutablePath)" }) -join '; '
    throw "FabMatrix processes remain under install directory after $maxRecoveryAttempts bounded attempts: $remainingNames"
} catch {
    Write-FabMatrixInstallEvent $LogPath "FAILED stage=$installStage error=$($_.Exception.Message)"
    Restore-ServiceSnapshotBestEffort $snapshot
    throw "Upgrade preflight failed before file copy: $($_.Exception.Message)"
}
