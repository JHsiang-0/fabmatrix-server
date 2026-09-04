param(
    [string]$InstallDir = 'C:\Program Files\Farm',
    [string]$DataRoot = 'C:\ProgramData\Farm',
    [switch]$AutoStart
)

$ErrorActionPreference = 'Stop'
$serviceDir = Join-Path $InstallDir 'service'
$winSw = Join-Path $serviceDir 'FarmService.exe'
$configPath = Join-Path (Join-Path $DataRoot 'config') 'application.yml'
$logsDir = Join-Path $DataRoot 'logs'
$dataDir = Join-Path $DataRoot 'data'
$uploadsDir = Join-Path $DataRoot 'uploads'

foreach ($directory in @($DataRoot, (Split-Path $configPath), $logsDir, $dataDir, $uploadsDir, (Join-Path $logsDir 'service'))) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

if (-not (Test-Path -LiteralPath $configPath)) {
    & (Join-Path $serviceDir 'Farm-Configure.ps1') -ConfigDir (Split-Path $configPath) -DataRoot $DataRoot
    if ($LASTEXITCODE -ne 0) { throw "Farm configuration generation failed: $LASTEXITCODE" }
}
if (-not (Test-Path -LiteralPath $winSw)) { throw "WinSW service wrapper not found: $winSw" }

if (Get-Command icacls.exe -ErrorAction SilentlyContinue) {
    & icacls.exe $DataRoot /inheritance:e /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not secure ProgramData permissions: $DataRoot" }
}

$xmlEscape = { param([string]$Value) [System.Security.SecurityElement]::Escape($Value) }
$installXml = & $xmlEscape $InstallDir
$dataXml = & $xmlEscape $DataRoot
$logsXml = & $xmlEscape (Join-Path $logsDir 'service')
$startMode = if ($AutoStart) { 'Automatic' } else { 'Manual' }
$configDirectory = Split-Path $configPath
$configLocation = (($configDirectory -replace '\\', '/') + '/')

$xml = @"
<service>
  <id>Farm</id>
  <name>Farm 3D Printer Management</name>
  <description>Farm local 3D printer farm management backend.</description>
  <executable>$installXml\Farm.exe</executable>
  <workingdirectory>$installXml</workingdirectory>
  <arguments>--spring.config.additional-location=file:$configLocation</arguments>
  <env name="FARM_DATA_DIR" value="$dataXml\data" />
  <env name="LOG_PATH" value="$dataXml\logs" />
  <logpath>$logsXml</logpath>
  <log mode="roll-by-size">
    <sizeThreshold>10485760</sizeThreshold>
    <keepFiles>5</keepFiles>
  </log>
  <startmode>$startMode</startmode>
  <stoptimeout>15sec</stoptimeout>
  <stopparentprocessfirst>true</stopparentprocessfirst>
  <onfailure action="restart" delay="10 sec" />
</service>
"@

$xmlPath = Join-Path $serviceDir 'FarmService.xml'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($xmlPath, $xml.Trim() + [Environment]::NewLine, $utf8)

& $winSw stop 2>$null | Out-Null
& $winSw uninstall 2>$null | Out-Null
& $winSw install
if ($LASTEXITCODE -ne 0) { throw "Farm Windows service registration failed: $LASTEXITCODE" }

if ($AutoStart) {
    & $winSw start
    if ($LASTEXITCODE -ne 0) { throw "Farm Windows service start failed: $LASTEXITCODE" }
}

Write-Output "Farm service registered: Farm ($startMode)"
