param(
    [string]$InstallDir = 'C:\Program Files\FabMatrix',
    [string]$DataRoot = 'C:\ProgramData\FabMatrix',
    [ValidateRange(1, 65535)][int]$Port = 28081,
    [switch]$SelfTestPortConfig,
    [switch]$AutoStart,
    [switch]$PreserveExistingState,
    [string]$StateFile = '',
    [string]$LogPath = '',
    [string]$LegacyDataRoot = 'C:\ProgramData\Farm'
)

$ErrorActionPreference = 'Stop'
$serviceName = 'FabMatrixServer'
$serviceDir = Join-Path $InstallDir 'service'
$winSw = Join-Path $serviceDir 'FabMatrixServerService.exe'
$configDir = Join-Path $DataRoot 'config'
$configPath = Join-Path $configDir 'application.yml'
$databaseDir = Join-Path $DataRoot 'database'
$filesDir = Join-Path $DataRoot 'files'
$logsDir = Join-Path $DataRoot 'logs'
$backupsDir = Join-Path $DataRoot 'backups'
$xmlPath = Join-Path $serviceDir 'FabMatrixServerService.xml'

function Get-ServiceIfPresent([string]$Name) {
    return Get-Service -Name $Name -ErrorAction SilentlyContinue
}

function Test-ServicePresent([string]$Name) {
    $service = Get-ServiceIfPresent $Name
    if ($null -eq $service) { return $false }
    $service.Dispose()
    return $true
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
            throw "Service did not stop: $Name"
        }
    } finally {
        $service.Dispose()
    }
}

function Wait-ServiceAbsent([string]$Name, [int]$TimeoutSeconds = 30) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (-not (Test-ServicePresent $Name)) { return }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Windows service is still pending deletion after $TimeoutSeconds seconds: $Name"
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

function Get-ExistingServiceState {
    foreach ($name in @('FabMatrixServer', 'Farm')) {
        $service = Get-CimInstance Win32_Service -Filter "Name='$name'" -ErrorAction SilentlyContinue
        if ($null -ne $service) {
            return [pscustomobject]@{
                ExistingServiceName = $name
                StartMode = [string]$service.StartMode
                WasRunning = ([string]$service.State -eq 'Running')
            }
        }
    }
    return $null
}

function Read-PreservedServiceState {
    if (-not $PreserveExistingState) { return $null }
    if (-not [string]::IsNullOrWhiteSpace($StateFile) -and (Test-Path -LiteralPath $StateFile -PathType Leaf)) {
        return Get-Content -LiteralPath $StateFile -Raw | ConvertFrom-Json
    }
    return Get-ExistingServiceState
}

function ConvertTo-FabMatrixPort([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $candidate = $Value.Trim() -replace '#.*$', ''
    $candidate = $candidate.Trim().Trim("'").Trim('"')
    $placeholder = [regex]::Match($candidate, '^\$\{\s*SERVER_PORT\s*:\s*(\d{1,5})\s*\}$')
    if ($placeholder.Success) { $candidate = $placeholder.Groups[1].Value }
    if ($candidate -notmatch '^\d{1,5}$') { return $null }
    $parsed = 0
    if (-not [int]::TryParse($candidate, [ref]$parsed) -or $parsed -lt 1 -or $parsed -gt 65535) {
        return $null
    }
    return $parsed
}

function Get-FabMatrixEffectivePort([string]$ConfigPath, [int]$FallbackPort = 28081) {
    $environmentPort = ConvertTo-FabMatrixPort ([Environment]::GetEnvironmentVariable('SERVER_PORT'))
    if ($null -ne $environmentPort) { return $environmentPort }

    if (Test-Path -LiteralPath $ConfigPath -PathType Leaf) {
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
                $configuredPort = ConvertTo-FabMatrixPort $Matches[1]
                if ($null -ne $configuredPort) { return $configuredPort }
                break
            }
        }
    }

    $fallback = ConvertTo-FabMatrixPort $FallbackPort
    return $(if ($null -ne $fallback) { $fallback } else { 28081 })
}

function Get-FabMatrixEffectivePortMetadataPath([string]$TargetInstallDir) {
    return Join-Path ([IO.Path]::GetFullPath($TargetInstallDir)) 'service-state\effective-port.txt'
}

function Write-FabMatrixEffectivePortMetadata {
    param(
        [Parameter(Mandatory = $true)][string]$TargetInstallDir,
        [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$EffectivePort
    )

    $metadataPath = Get-FabMatrixEffectivePortMetadataPath $TargetInstallDir
    $metadataDir = Split-Path -Parent $metadataPath
    New-Item -ItemType Directory -Force -Path $metadataDir | Out-Null
    $tempPath = Join-Path $metadataDir ('.effective-port-' + [guid]::NewGuid().ToString('N') + '.tmp')
    $backupPath = Join-Path $metadataDir ('.effective-port-' + [guid]::NewGuid().ToString('N') + '.bak')
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    try {
        [IO.File]::WriteAllText($tempPath, [string]$EffectivePort + [Environment]::NewLine, $utf8)
        if (Test-Path -LiteralPath $metadataPath -PathType Leaf) {
            [IO.File]::Replace($tempPath, $metadataPath, $backupPath, $true)
        } else {
            [IO.File]::Move($tempPath, $metadataPath)
        }
    } finally {
        if (Test-Path -LiteralPath $tempPath) { Remove-Item -LiteralPath $tempPath -Force }
        if (Test-Path -LiteralPath $backupPath) { Remove-Item -LiteralPath $backupPath -Force }
    }
    return $metadataPath
}

function Test-FabMatrixPortConfig {
    $tempPath = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixPortContract-' + [guid]::NewGuid().ToString('N') + '.yml')
    $tempInstallDir = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixPortMetadata-' + [guid]::NewGuid().ToString('N'))
    $previousEnvironmentPort = [Environment]::GetEnvironmentVariable('SERVER_PORT')
    try {
        [Environment]::SetEnvironmentVariable('SERVER_PORT', $null)
        [IO.File]::WriteAllText($tempPath, "server:`r`n  port: 8080`r`n")
        if ((Get-FabMatrixEffectivePort $tempPath) -ne 8080) { throw 'Pure numeric server.port was not preserved.' }
        [IO.File]::WriteAllText($tempPath, "server:`r`n  port: `${SERVER_PORT:8181}`r`n")
        if ((Get-FabMatrixEffectivePort $tempPath) -ne 8181) { throw 'SERVER_PORT placeholder fallback was not parsed.' }
        [Environment]::SetEnvironmentVariable('SERVER_PORT', '8282')
        if ((Get-FabMatrixEffectivePort $tempPath) -ne 8282) { throw 'Explicit SERVER_PORT did not take priority.' }
        [Environment]::SetEnvironmentVariable('SERVER_PORT', 'invalid')
        if ((Get-FabMatrixEffectivePort $tempPath) -ne 8181) { throw 'Invalid SERVER_PORT did not fall back to config.' }
        $metadataPath = Write-FabMatrixEffectivePortMetadata -TargetInstallDir $tempInstallDir -EffectivePort 8080
        $expectedMetadataPath = Join-Path ([IO.Path]::GetFullPath($tempInstallDir)) 'service-state\effective-port.txt'
        if ($metadataPath -ne $expectedMetadataPath -or ([IO.File]::ReadAllText($metadataPath)).Trim() -ne '8080') {
            throw 'Effective port metadata did not preserve the legacy port at the installed runtime path.'
        }
        [void](Write-FabMatrixEffectivePortMetadata -TargetInstallDir $tempInstallDir -EffectivePort 8181)
        if (([IO.File]::ReadAllText($metadataPath)).Trim() -ne '8181') {
            throw 'Effective port metadata was not replaced atomically.'
        }
        Remove-Item -LiteralPath $tempPath -Force
        if ((Get-FabMatrixEffectivePort $tempPath) -ne 28081) { throw 'Missing config did not use the new default port.' }
        Write-Output 'FabMatrix effective port parser and metadata self-test passed.'
    } finally {
        [Environment]::SetEnvironmentVariable('SERVER_PORT', $previousEnvironmentPort)
        if (Test-Path -LiteralPath $tempPath) { Remove-Item -LiteralPath $tempPath -Force }
        if (Test-Path -LiteralPath $tempInstallDir) { Remove-Item -LiteralPath $tempInstallDir -Recurse -Force }
    }
}

if ($SelfTestPortConfig) {
    Test-FabMatrixPortConfig
    return
}

. (Join-Path $serviceDir 'service-xml.ps1')
. (Join-Path $serviceDir 'data-acl.ps1')

function Set-PrivateFirewallRule([ValidateRange(1, 65535)][int]$EffectivePort) {
    if (-not (Get-Command Get-NetFirewallRule -ErrorAction SilentlyContinue) -or
        -not (Get-Command New-NetFirewallRule -ErrorAction SilentlyContinue)) {
        throw 'Windows Firewall PowerShell cmdlets are required to create the Private inbound rule.'
    }
    $ruleName = 'FabMatrixServer-Private-TCP'
    Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule -ErrorAction Stop
    New-NetFirewallRule -Name $ruleName -DisplayName 'FabMatrix Server (Private TCP)' `
        -Direction Inbound -Protocol TCP -LocalPort $EffectivePort -Profile Private -Action Allow `
        -Enabled True -ErrorAction Stop | Out-Null
}

function Install-WinSwServiceWithRetry {
    param(
        [Parameter(Mandatory = $true)][string]$WrapperPath,
        [string]$Name = 'FabMatrixServer',
        [int]$TimeoutSeconds = 30
    )

    Wait-ServiceAbsent $Name
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $attempt = 0
    $lastOutput = ''
    do {
        $attempt++
        $lastOutput = (& $WrapperPath install 2>&1 | Out-String).Trim()
        $exitCode = $LASTEXITCODE
        if ($exitCode -eq 0) { return $attempt }
        if (Test-ServicePresent $Name) {
            throw "WinSW returned $exitCode but service $Name is present; refusing to retry an ambiguous registration."
        }
        if ([DateTime]::UtcNow -ge $deadline) { break }
        Start-Sleep -Milliseconds 500
    } while ($true)

    throw "FabMatrixServer registration failed after $attempt attempts; last exit code: $exitCode; output: $lastOutput"
}

function Initialize-InstallLog([string]$RequestedPath, [string]$TargetDataRoot) {
    $resolvedPath = if ([string]::IsNullOrWhiteSpace($RequestedPath)) {
        Join-Path $TargetDataRoot 'logs\installer\service-install.log'
    } else {
        [IO.Path]::GetFullPath($RequestedPath)
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resolvedPath) | Out-Null
    return $resolvedPath
}

function Write-InstallLog([string]$Path, [string]$Message) {
    $line = '{0} {1}{2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'), $Message, [Environment]::NewLine
    [IO.File]::AppendAllText($Path, $line, (New-Object System.Text.UTF8Encoding($false)))
}

$installLogPath = Initialize-InstallLog -RequestedPath $LogPath -TargetDataRoot $DataRoot
$installStage = 'initialize'
Write-InstallLog $installLogPath "BEGIN installDir=$InstallDir dataRoot=$DataRoot requestedPort=$Port"
try {
    $installStage = 'resolve preserved service state'
    $preservedState = Read-PreservedServiceState
    $servicePlan = Resolve-FabMatrixServicePlan -PreservedState $preservedState -AutoStartRequested ([bool]$AutoStart)

    # The capture step normally stopped these before files were replaced; repeat the checked stop for direct invocation.
    $installStage = 'stop existing services'
    Stop-ServiceIfPresent 'FabMatrixServer'
    Stop-ServiceIfPresent 'Farm'

    # Migration is only attempted after both legacy/new services are confirmed stopped.
    $installStage = 'migrate legacy data'
    & (Join-Path $serviceDir 'migrate-legacy-data.ps1') -DataRoot $DataRoot -LegacyDataRoot $LegacyDataRoot

    $installStage = 'prepare data directories'
    foreach ($directory in @($DataRoot, $configDir, $databaseDir, $filesDir, $logsDir, $backupsDir, (Join-Path $logsDir 'service'))) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }

    $installStage = 'prepare local configuration'
    if (-not (Test-Path -LiteralPath $configPath)) {
        & (Join-Path $serviceDir 'Farm-Configure.ps1') -Edition Local -ConfigDir $configDir -DataRoot $DataRoot -Port $Port
    }
    $effectivePort = Get-FabMatrixEffectivePort -ConfigPath $configPath -FallbackPort $Port
    Write-InstallLog $installLogPath "Resolved effective port: $effectivePort"
    if (-not (Test-Path -LiteralPath $winSw)) { throw "WinSW service wrapper not found: $winSw" }

    $installStage = 'remove previous FabMatrixServer service'
    if (Test-ServicePresent 'FabMatrixServer') {
        & $winSw uninstall
        if ($LASTEXITCODE -ne 0) { throw "FabMatrixServer uninstall failed: $LASTEXITCODE" }
        Wait-ServiceAbsent 'FabMatrixServer'
    }
    $installStage = 'remove legacy Farm service'
    Remove-ServiceIfPresent 'Farm'

    $installStage = 'protect data directories'
    Set-FabMatrixDataAcl -DataRoot $DataRoot
    $installStage = 'publish effective port metadata'
    $portMetadataPath = Write-FabMatrixEffectivePortMetadata -TargetInstallDir $InstallDir -EffectivePort $effectivePort
    Set-FabMatrixRuntimeMetadataAcl -InstallDir $InstallDir

    $installStage = 'write WinSW configuration'
    $xml = New-FabMatrixServiceXml -InstallDir $InstallDir -DataRoot $DataRoot -Port $effectivePort -StartMode $servicePlan.StartMode
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($xmlPath, $xml.Trim() + [Environment]::NewLine, $utf8)

    $installStage = 'register FabMatrixServer service'
    $installAttempts = Install-WinSwServiceWithRetry -WrapperPath $winSw -Name $serviceName
    Write-InstallLog $installLogPath "Service registration succeeded on attempt $installAttempts"
    if ($servicePlan.DisableAfterInstall) {
        $installStage = 'restore disabled start mode'
        & sc.exe config $serviceName 'start=' 'disabled' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "FabMatrixServer Disabled start mode restoration failed: $LASTEXITCODE" }
    }
    try {
        $installStage = 'configure private firewall rule'
        Set-PrivateFirewallRule -EffectivePort $effectivePort
    } catch {
        & $winSw uninstall 2>$null | Out-Null
        Wait-ServiceAbsent $serviceName
        throw
    }

    if ($servicePlan.ShouldStart) {
        $installStage = 'start FabMatrixServer service'
        & $winSw start
        if ($LASTEXITCODE -ne 0) { throw "FabMatrixServer start failed: $LASTEXITCODE" }
    }

    $stateDescription = if ($servicePlan.IsUpgrade) { "preserved $($preservedState.StartMode), wasRunning=$($preservedState.WasRunning)" } else { $servicePlan.StartMode }
    $successMessage = "FabMatrixServer service registered: $stateDescription; effective port $effectivePort; tray metadata $portMetadataPath; data retained at $DataRoot"
    Write-InstallLog $installLogPath "SUCCESS $successMessage"
    Write-Output $successMessage
} catch {
    $failure = $_.Exception.ToString() -replace '[\r\n]+', ' | '
    try { Write-InstallLog $installLogPath "FAILED stage=$installStage error=$failure" } catch { }
    throw "FabMatrixServer service installation failed at stage '$installStage'. $($_.Exception.Message) Detailed log: $installLogPath"
}
