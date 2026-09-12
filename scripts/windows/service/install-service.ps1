param(
    [string]$InstallDir = 'C:\Program Files\FabMatrix',
    [string]$DataRoot = 'C:\ProgramData\FabMatrix',
    [ValidateRange(1, 65535)][int]$Port = 8080,
    [switch]$AutoStart,
    [switch]$PreserveExistingState,
    [string]$StateFile = '',
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

. (Join-Path $serviceDir 'service-xml.ps1')

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
        throw "Service did not stop: $Name"
    }
}

function Remove-ServiceIfPresent([string]$Name) {
    $service = Get-ServiceIfPresent $Name
    if ($null -eq $service) { return }
    Stop-ServiceIfPresent $Name
    & sc.exe delete $Name | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Windows service deletion failed ($LASTEXITCODE): $Name" }
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

function Invoke-Icacls([string]$Path, [string[]]$Arguments) {
    & icacls.exe $Path @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "ACL update failed ($LASTEXITCODE): $Path" }
}

function Set-DataPermissions {
    if (-not (Get-Command icacls.exe -ErrorAction SilentlyContinue)) {
        throw 'icacls.exe is required to protect FabMatrix config, database and backups.'
    }

    $system = '*S-1-5-18:(OI)(CI)F'
    $administrators = '*S-1-5-32-544:(OI)(CI)F'
    $usersReadOnly = '*S-1-5-32-545:(OI)(CI)RX'

    foreach ($privateDir in @($configDir, $databaseDir, $backupsDir)) {
        Invoke-Icacls $privateDir @('/inheritance:r', '/grant:r', $system, $administrators, '/T', '/C')
    }
    foreach ($sharedDir in @($filesDir, $logsDir)) {
        Invoke-Icacls $sharedDir @('/inheritance:r', '/grant:r', $system, $administrators, $usersReadOnly, '/T', '/C')
    }
}

function Set-PrivateFirewallRule {
    if (-not (Get-Command Get-NetFirewallRule -ErrorAction SilentlyContinue) -or
        -not (Get-Command New-NetFirewallRule -ErrorAction SilentlyContinue)) {
        throw 'Windows Firewall PowerShell cmdlets are required to create the Private inbound rule.'
    }
    $ruleName = 'FabMatrixServer-Private-TCP'
    Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule -ErrorAction Stop
    New-NetFirewallRule -Name $ruleName -DisplayName 'FabMatrix Server (Private TCP)' `
        -Direction Inbound -Protocol TCP -LocalPort $Port -Profile Private -Action Allow `
        -Enabled True -ErrorAction Stop | Out-Null
}

$preservedState = Read-PreservedServiceState
$servicePlan = Resolve-FabMatrixServicePlan -PreservedState $preservedState -AutoStartRequested ([bool]$AutoStart)

# The capture step normally stopped these before files were replaced; repeat the checked stop for direct invocation.
Stop-ServiceIfPresent 'FabMatrixServer'
Stop-ServiceIfPresent 'Farm'

# Migration is only attempted after both legacy/new services are confirmed stopped.
& (Join-Path $serviceDir 'migrate-legacy-data.ps1') -DataRoot $DataRoot -LegacyDataRoot $LegacyDataRoot

foreach ($directory in @($DataRoot, $configDir, $databaseDir, $filesDir, $logsDir, $backupsDir, (Join-Path $logsDir 'service'))) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

if (-not (Test-Path -LiteralPath $configPath)) {
    & (Join-Path $serviceDir 'Farm-Configure.ps1') -Edition Local -ConfigDir $configDir -DataRoot $DataRoot -Port $Port
}
if (-not (Test-Path -LiteralPath $winSw)) { throw "WinSW service wrapper not found: $winSw" }

if ($null -ne (Get-ServiceIfPresent 'FabMatrixServer')) {
    & $winSw uninstall
    if ($LASTEXITCODE -ne 0) { throw "FabMatrixServer uninstall failed: $LASTEXITCODE" }
}
Remove-ServiceIfPresent 'Farm'

Set-DataPermissions

$xml = New-FabMatrixServiceXml -InstallDir $InstallDir -DataRoot $DataRoot -StartMode $servicePlan.StartMode
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($xmlPath, $xml.Trim() + [Environment]::NewLine, $utf8)

& $winSw install
if ($LASTEXITCODE -ne 0) { throw "FabMatrixServer registration failed: $LASTEXITCODE" }
if ($servicePlan.DisableAfterInstall) {
    & sc.exe config $serviceName 'start=' 'disabled' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "FabMatrixServer Disabled start mode restoration failed: $LASTEXITCODE" }
}
try {
    Set-PrivateFirewallRule
} catch {
    & $winSw uninstall 2>$null | Out-Null
    throw
}

if ($servicePlan.ShouldStart) {
    & $winSw start
    if ($LASTEXITCODE -ne 0) { throw "FabMatrixServer start failed: $LASTEXITCODE" }
}

$stateDescription = if ($servicePlan.IsUpgrade) { "preserved $($preservedState.StartMode), wasRunning=$($preservedState.WasRunning)" } else { $servicePlan.StartMode }
Write-Output "FabMatrixServer service registered: $stateDescription; data retained at $DataRoot"
