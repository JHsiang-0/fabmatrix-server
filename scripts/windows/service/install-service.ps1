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

function Get-FabMatrixConfiguredPort([string]$ConfigPath) {
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
    return $null
}

function Get-FabMatrixEffectivePort([string]$ConfigPath, [int]$FallbackPort = 28081) {
    $environmentPort = ConvertTo-FabMatrixPort ([Environment]::GetEnvironmentVariable('SERVER_PORT'))
    if ($null -ne $environmentPort) { return $environmentPort }

    $configuredPort = Get-FabMatrixConfiguredPort $ConfigPath
    if ($null -ne $configuredPort) { return $configuredPort }

    $fallback = ConvertTo-FabMatrixPort $FallbackPort
    return $(if ($null -ne $fallback) { $fallback } else { 28081 })
}

function Write-FabMatrixAtomicUtf8File {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )

    $resolvedPath = [IO.Path]::GetFullPath($Path)
    $directory = Split-Path -Parent $resolvedPath
    $tempPath = Join-Path $directory ('.fabmatrix-config-' + [guid]::NewGuid().ToString('N') + '.tmp')
    $backupPath = Join-Path $directory ('.fabmatrix-config-' + [guid]::NewGuid().ToString('N') + '.bak')
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    try {
        [IO.File]::WriteAllText($tempPath, $Content, $utf8)
        if (Test-Path -LiteralPath $resolvedPath -PathType Leaf) {
            [IO.File]::Replace($tempPath, $resolvedPath, $backupPath, $true)
        } else {
            [IO.File]::Move($tempPath, $resolvedPath)
        }
    } finally {
        if (Test-Path -LiteralPath $tempPath) { Remove-Item -LiteralPath $tempPath -Force }
        if (Test-Path -LiteralPath $backupPath) { Remove-Item -LiteralPath $backupPath -Force }
    }
}

function Ensure-FabMatrixBootstrapCredential([string]$DataRoot) {
    $configDir = Join-Path $DataRoot 'config'
    $tokenPath = Join-Path $configDir 'bootstrap.token'
    if (Test-Path -LiteralPath $tokenPath -PathType Leaf) { return $false }

    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $token = ([Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_')).TrimEnd('=')
    $expiresAt = [DateTimeOffset]::UtcNow.AddMinutes(30).ToUnixTimeMilliseconds()
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $content = "expiresAt=$expiresAt$([Environment]::NewLine)token=$token$([Environment]::NewLine)"
    [IO.File]::WriteAllText($tokenPath, $content, $utf8)
    return $true
}

function Set-FabMatrixLegacyDefaultPortForUpgrade {
    param(
        [Parameter(Mandatory = $true)][string]$ConfigPath,
        [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$RequestedPort
    )

    if ($RequestedPort -ne 28081 -or
        $null -ne (ConvertTo-FabMatrixPort ([Environment]::GetEnvironmentVariable('SERVER_PORT'))) -or
        -not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
        return $false
    }

    $lines = [IO.File]::ReadAllLines($ConfigPath, [Text.Encoding]::UTF8)
    $inServer = $false
    $serverIndent = -1
    $portLineIndex = -1
    $replacement = $null
    for ($index = 0; $index -lt $lines.Count; $index++) {
        $line = $lines[$index]
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
        if ($trimmed -match '^(?<prefix>port\s*:\s*)8080(?<suffix>\s*(?:#.*)?)$') {
            $portLineIndex = $index
            $leadingWhitespace = $line.Length - $line.TrimStart().Length
            $replacement = $Matches['prefix'] + '28081' + $Matches['suffix']
            break
        }
    }
    if ($portLineIndex -lt 0) { return $false }

    $lines[$portLineIndex] = (' ' * $leadingWhitespace) + $replacement
    $original = [IO.File]::ReadAllText($ConfigPath, [Text.Encoding]::UTF8)
    $newline = if ($original.Contains("`r`n")) { "`r`n" } else { "`n" }
    $updated = $lines -join $newline
    if ($original.EndsWith("`r`n") -or $original.EndsWith("`n")) { $updated += $newline }
    Write-FabMatrixAtomicUtf8File -Path $ConfigPath -Content $updated
    return $true
}

function Set-FabMatrixLegacyDefaultPortForInstallContext {
    param(
        [Parameter(Mandatory = $true)][string]$ConfigPath,
        [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$RequestedPort,
        [Parameter(Mandatory = $true)][bool]$IsServiceUpgrade,
        [Parameter(Mandatory = $true)][bool]$LegacyDataMigrated
    )

    # The installer always requests 28081 as the product default. A pre-existing
    # numeric 8080 is the legacy default even when the service registration was
    # lost or the data root was initialized by an earlier setup attempt, so it
    # must not become the effective port merely because this is not an upgrade.
    # Custom configured ports remain untouched and an explicit SERVER_PORT still
    # wins inside Set-FabMatrixLegacyDefaultPortForUpgrade.
    return Set-FabMatrixLegacyDefaultPortForUpgrade -ConfigPath $ConfigPath -RequestedPort $RequestedPort
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
        if (-not (Set-FabMatrixLegacyDefaultPortForInstallContext -ConfigPath $tempPath -RequestedPort 28081 `
                -IsServiceUpgrade $false -LegacyDataMigrated $true) -or
            (Get-FabMatrixEffectivePort $tempPath) -ne 28081 -or
            ([IO.File]::ReadAllText($tempPath) -notmatch '(?m)^\s*port:\s*28081\s*$')) {
            throw 'Fresh legacy migration did not atomically migrate server.port 8080 to 28081.'
        }
        [IO.File]::WriteAllText($tempPath, "server:`r`n  port: 8080`r`n")
        if (-not (Set-FabMatrixLegacyDefaultPortForInstallContext -ConfigPath $tempPath -RequestedPort 28081 `
                -IsServiceUpgrade $false -LegacyDataMigrated $false) -or
            (Get-FabMatrixEffectivePort $tempPath) -ne 28081 -or
            ([IO.File]::ReadAllText($tempPath) -notmatch '(?m)^\s*port:\s*28081\s*$')) {
            throw 'Existing unregistered configuration did not migrate legacy default 8080 to 28081.'
        }
        [IO.File]::WriteAllText($tempPath, "server:`r`n  port: 29090`r`n")
        if ((Set-FabMatrixLegacyDefaultPortForInstallContext -ConfigPath $tempPath -RequestedPort 28081 `
                -IsServiceUpgrade $false -LegacyDataMigrated $true) -or
            (Get-FabMatrixEffectivePort $tempPath) -ne 29090 -or
            ([IO.File]::ReadAllText($tempPath) -notmatch '(?m)^\s*port:\s*29090\s*$')) {
            throw 'Custom server.port was not preserved during fresh legacy migration.'
        }
        [IO.File]::WriteAllText($tempPath, "server:`r`n  port: 28081`r`n")
        if ((Set-FabMatrixLegacyDefaultPortForUpgrade -ConfigPath $tempPath -RequestedPort 28081) -or
            (Get-FabMatrixEffectivePort $tempPath) -ne 28081) {
            throw 'New default server.port was not preserved.'
        }
        [IO.File]::WriteAllText($tempPath, "server:`r`n  port: 8080`r`n")
        [Environment]::SetEnvironmentVariable('SERVER_PORT', '29090')
        if ((Set-FabMatrixLegacyDefaultPortForInstallContext -ConfigPath $tempPath -RequestedPort 28081 `
                -IsServiceUpgrade $false -LegacyDataMigrated $true) -or
            (Get-FabMatrixEffectivePort $tempPath) -ne 29090 -or
            ([IO.File]::ReadAllText($tempPath) -notmatch '(?m)^\s*port:\s*8080\s*$')) {
            throw 'Explicit SERVER_PORT priority was not preserved during migration.'
        }
        [Environment]::SetEnvironmentVariable('SERVER_PORT', $null)
        [IO.File]::WriteAllText($tempPath, "server:`r`n  port: `${SERVER_PORT:8181}`r`n")
        if ((Get-FabMatrixEffectivePort $tempPath) -ne 8181) { throw 'SERVER_PORT placeholder fallback was not parsed.' }
        [Environment]::SetEnvironmentVariable('SERVER_PORT', '8282')
        if ((Get-FabMatrixEffectivePort $tempPath) -ne 8282) { throw 'Explicit SERVER_PORT did not take priority.' }
        [Environment]::SetEnvironmentVariable('SERVER_PORT', 'invalid')
        if ((Get-FabMatrixEffectivePort $tempPath) -ne 8181) { throw 'Invalid SERVER_PORT did not fall back to config.' }
        $metadataPath = Write-FabMatrixEffectivePortMetadata -TargetInstallDir $tempInstallDir -EffectivePort 28081
        $expectedMetadataPath = Join-Path ([IO.Path]::GetFullPath($tempInstallDir)) 'service-state\effective-port.txt'
        if ($metadataPath -ne $expectedMetadataPath -or ([IO.File]::ReadAllText($metadataPath)).Trim() -ne '28081') {
            throw 'Effective port metadata did not publish the migrated port at the installed runtime path.'
        }
        [void](Write-FabMatrixEffectivePortMetadata -TargetInstallDir $tempInstallDir -EffectivePort 8181)
        if (([IO.File]::ReadAllText($metadataPath)).Trim() -ne '8181') {
            throw 'Effective port metadata was not replaced atomically.'
        }
        Remove-Item -LiteralPath $tempPath -Force
        if ((Get-FabMatrixEffectivePort $tempPath) -ne 28081) { throw 'Missing config did not use the new default port.' }
        Write-Output 'FabMatrix effective port parser, fresh legacy migration and metadata self-test passed.'
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
. (Join-Path $serviceDir 'health-check.ps1')

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
        if ($exitCode -eq 0) {
            if (-not (Test-ServicePresent $Name)) {
                throw "WinSW returned success but service $Name is not registered."
            }
            return $attempt
        }
        if (Test-ServicePresent $Name) {
            throw "WinSW returned $exitCode but service $Name is present; refusing to retry an ambiguous registration."
        }
        if ([DateTime]::UtcNow -ge $deadline) { break }
        Start-Sleep -Milliseconds 500
    } while ($true)

    throw "FabMatrixServer registration failed after $attempt attempts; last exit code: $exitCode; output: $lastOutput"
}

function Test-FabMatrixHealth([ValidateRange(1, 65535)][int]$EffectivePort) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$EffectivePort/actuator/health" -TimeoutSec 2 -ErrorAction Stop
        return Test-FabMatrixHealthResponse -Response $response
    } catch {
        return $false
    }
}

function Wait-FabMatrixHealth {
    param(
        [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$EffectivePort,
        [int]$TimeoutSeconds = 60
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (Test-FabMatrixHealth -EffectivePort $EffectivePort) { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "FabMatrixServer health check failed after $TimeoutSeconds seconds: http://127.0.0.1:$EffectivePort/actuator/health"
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
$migrationResultPath = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixLegacyMigrationResult-' + [guid]::NewGuid().ToString('N') + '.json')
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

    # Restore elevated installer access before reading/migrating legacy configuration.
    # The final restrictive ACL policy is applied after migration and configuration.
    $installStage = 'repair legacy data permissions'
    Repair-FabMatrixInstallerDataAccess -DataRoot $DataRoot

    # Migration is only attempted after both legacy/new services are confirmed stopped.
    $installStage = 'migrate legacy data'
    & (Join-Path $serviceDir 'migrate-legacy-data.ps1') -DataRoot $DataRoot -LegacyDataRoot $LegacyDataRoot `
        -ResultPath $migrationResultPath | Out-Null
    if (-not (Test-Path -LiteralPath $migrationResultPath -PathType Leaf)) {
        throw 'Legacy migration did not publish its required structured result.'
    }
    $migrationResult = Get-Content -LiteralPath $migrationResultPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([int]$migrationResult.SchemaVersion -ne 1 -or $null -eq $migrationResult.Migrated) {
        throw 'Legacy migration published an invalid structured result.'
    }
    Write-InstallLog $installLogPath "Legacy migration result: migrated=$([bool]$migrationResult.Migrated) reason=$($migrationResult.Reason)"

    $installStage = 'prepare data directories'
    foreach ($directory in @($DataRoot, $configDir, $databaseDir, $filesDir, $logsDir, $backupsDir, (Join-Path $logsDir 'service'))) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }

    $installStage = 'prepare local configuration'
    if (-not (Test-Path -LiteralPath $configPath)) {
        & (Join-Path $serviceDir 'Farm-Configure.ps1') -Edition Local -ConfigDir $configDir -DataRoot $DataRoot -Port $Port
    }
    if (Ensure-FabMatrixBootstrapCredential $DataRoot) {
        Write-InstallLog $installLogPath 'Generated Local Edition bootstrap credential in protected config directory.'
    }
    $installStage = 'migrate legacy default port'
    if (Set-FabMatrixLegacyDefaultPortForInstallContext -ConfigPath $configPath -RequestedPort $Port `
            -IsServiceUpgrade ([bool]$servicePlan.IsUpgrade) -LegacyDataMigrated ([bool]$migrationResult.Migrated)) {
        $portMigrationContext = if ([bool]$migrationResult.Migrated) { 'after confirmed legacy Farm data migration' } else { 'during service upgrade' }
        Write-InstallLog $installLogPath "Migrated legacy Local configuration server.port from 8080 to requested default 28081 $portMigrationContext."
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
    $firewallWarning = $null
    $installStage = 'configure private firewall rule'
    try {
        Set-PrivateFirewallRule -EffectivePort $effectivePort
    } catch {
        # The server remains usable on localhost when Windows policy or endpoint
        # protection blocks firewall automation. Keep the registered service and
        # expose a precise warning instead of rolling the entire installation back.
        $firewallWarning = $_.Exception.Message
        Write-InstallLog $installLogPath "WARNING stage=$installStage error=$($firewallWarning -replace '[\r\n]+', ' | ')"
    }

    if ($servicePlan.ShouldStart) {
        $installStage = 'start FabMatrixServer service'
        & $winSw start
        if ($LASTEXITCODE -ne 0) { throw "FabMatrixServer start failed: $LASTEXITCODE" }
        $installStage = 'wait for FabMatrixServer health'
        Wait-FabMatrixHealth -EffectivePort $effectivePort
        Write-InstallLog $installLogPath "Health check succeeded on port $effectivePort"
    } else {
        Write-InstallLog $installLogPath 'Service start was not requested; health check deferred because the preserved/new service state is stopped.'
    }

    $stateDescription = if ($servicePlan.IsUpgrade) { "preserved $($preservedState.StartMode), wasRunning=$($preservedState.WasRunning)" } else { $servicePlan.StartMode }
    $successMessage = "FabMatrixServer service registered: $stateDescription; effective port $effectivePort; tray metadata $portMetadataPath; data retained at $DataRoot"
    if (-not [string]::IsNullOrWhiteSpace($firewallWarning)) {
        $successMessage += "; WARNING: private firewall rule was not created ($firewallWarning)"
    }
    Write-InstallLog $installLogPath "SUCCESS $successMessage"
    Write-Output $successMessage
} catch {
    $failure = $_.Exception.ToString() -replace '[\r\n]+', ' | '
    try { Write-InstallLog $installLogPath "FAILED stage=$installStage error=$failure" } catch { }
    throw "FabMatrixServer service installation failed at stage '$installStage'. $($_.Exception.Message) Detailed log: $installLogPath"
} finally {
    if (Test-Path -LiteralPath $migrationResultPath) { Remove-Item -LiteralPath $migrationResultPath -Force -ErrorAction SilentlyContinue }
}
