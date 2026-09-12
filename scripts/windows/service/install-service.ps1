param(
    [string]$InstallDir = 'C:\Program Files\FabMatrix',
    [string]$DataRoot = 'C:\ProgramData\FabMatrix',
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [switch]$AutoStart
)

$ErrorActionPreference = 'Stop'
$serviceDir = Join-Path $InstallDir 'service'
$winSw = Join-Path $serviceDir 'FabMatrixServerService.exe'
$configDir = Join-Path $DataRoot 'config'
$configPath = Join-Path $configDir 'application.yml'
$databaseDir = Join-Path $DataRoot 'database'
$filesDir = Join-Path $DataRoot 'files'
$logsDir = Join-Path $DataRoot 'logs'
$backupsDir = Join-Path $DataRoot 'backups'

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
        throw "服务未能停止: $Name"
    }
}

function Remove-ServiceIfPresent([string]$Name) {
    $service = Get-ServiceIfPresent $Name
    if ($null -eq $service) { return }
    Stop-ServiceIfPresent $Name
    & sc.exe delete $Name | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "旧 Windows 服务删除失败 ($LASTEXITCODE): $Name" }
}

function Invoke-Icacls([string]$Path, [string[]]$Arguments) {
    & icacls.exe $Path @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "ACL 设置失败 ($LASTEXITCODE): $Path" }
}

function Set-DataPermissions {
    if (-not (Get-Command icacls.exe -ErrorAction SilentlyContinue)) {
        throw '未找到 icacls.exe，无法保护 FabMatrix 配置和 SQLite 数据库。'
    }

    $system = '*S-1-5-18:(OI)(CI)F'
    $administrators = '*S-1-5-32-544:(OI)(CI)F'
    $usersReadOnly = '*S-1-5-32-545:(OI)(CI)RX'

    foreach ($privateDir in @($configDir, $databaseDir)) {
        Invoke-Icacls $privateDir @('/inheritance:r', '/grant:r', $system, $administrators, '/T', '/C')
    }
    foreach ($sharedDir in @($filesDir, $logsDir, $backupsDir)) {
        Invoke-Icacls $sharedDir @('/inheritance:r', '/grant:r', $system, $administrators, $usersReadOnly, '/T', '/C')
    }
}

function Set-PrivateFirewallRule {
    if (-not (Get-Command Get-NetFirewallRule -ErrorAction SilentlyContinue) -or
        -not (Get-Command New-NetFirewallRule -ErrorAction SilentlyContinue)) {
        throw '当前 Windows 没有可用的 Windows Firewall PowerShell cmdlet，无法创建私有网络入站规则。'
    }
    $ruleName = 'FabMatrixServer-Private-TCP'
    Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule -ErrorAction Stop
    New-NetFirewallRule -Name $ruleName -DisplayName 'FabMatrix Server (Private TCP)' `
        -Direction Inbound -Protocol TCP -LocalPort $Port -Profile Private -Action Allow `
        -Enabled True -ErrorAction Stop | Out-Null
}

foreach ($directory in @($DataRoot, $configDir, $databaseDir, $filesDir, $logsDir, $backupsDir, (Join-Path $logsDir 'service'))) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

if (-not (Test-Path -LiteralPath $configPath)) {
    & (Join-Path $serviceDir 'Farm-Configure.ps1') -Edition Local -ConfigDir $configDir -DataRoot $DataRoot -Port $Port
    if ($LASTEXITCODE -ne 0) { throw "FabMatrix 配置生成失败: $LASTEXITCODE" }
}
if (-not (Test-Path -LiteralPath $winSw)) { throw "WinSW service wrapper not found: $winSw" }

# 升级时先正常停止新旧服务，再移除旧的 Farm 服务注册；不删除任何数据目录。
Stop-ServiceIfPresent 'FabMatrixServer'
Stop-ServiceIfPresent 'Farm'
if ($null -ne (Get-ServiceIfPresent 'FabMatrixServer')) {
    & $winSw uninstall
    if ($LASTEXITCODE -ne 0) { throw "FabMatrixServer 服务卸载失败: $LASTEXITCODE" }
}
Remove-ServiceIfPresent 'Farm'

Set-DataPermissions

$xmlEscape = { param([string]$Value) [System.Security.SecurityElement]::Escape($Value) }
$installXml = & $xmlEscape $InstallDir
$dataXml = & $xmlEscape $DataRoot
$logsXml = & $xmlEscape (Join-Path $logsDir 'service')
$startMode = if ($AutoStart) { 'Automatic' } else { 'Manual' }
$configLocation = (($configDir -replace '\\', '/') + '/')

$xml = @"
<service>
  <id>FabMatrixServer</id>
  <name>FabMatrixServer</name>
  <description>FabMatrix Server local 3D printer farm management backend.</description>
  <executable>$installXml\FabMatrixServer.exe</executable>
  <workingdirectory>$installXml</workingdirectory>
  <arguments>--spring.config.additional-location=file:$configLocation</arguments>
  <env name="FARM_DATA_DIR" value="$dataXml" />
  <env name="LOG_PATH" value="$dataXml\logs" />
  <logpath>$logsXml</logpath>
  <log mode="roll-by-size">
    <sizeThreshold>10485760</sizeThreshold>
    <keepFiles>5</keepFiles>
  </log>
  <startmode>$startMode</startmode>
  <stoptimeout>20sec</stoptimeout>
  <stopparentprocessfirst>true</stopparentprocessfirst>
  <onfailure action="restart" delay="10 sec" />
</service>
"@

$xmlPath = Join-Path $serviceDir 'FabMatrixServerService.xml'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($xmlPath, $xml.Trim() + [Environment]::NewLine, $utf8)

& $winSw install
if ($LASTEXITCODE -ne 0) { throw "FabMatrixServer 服务注册失败: $LASTEXITCODE" }
try {
    Set-PrivateFirewallRule
} catch {
    & $winSw uninstall 2>$null | Out-Null
    throw
}

if ($AutoStart) {
    & $winSw start
    if ($LASTEXITCODE -ne 0) { throw "FabMatrixServer 服务启动失败: $LASTEXITCODE" }
}

Write-Output "FabMatrixServer service registered: $startMode; data retained at $DataRoot"
