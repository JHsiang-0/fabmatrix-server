param(
    [ValidateSet('Local')]
    [string]$Edition = 'Local',
    [string]$ConfigDir = 'C:\ProgramData\FabMatrix\config',
    [string]$DataRoot = 'C:\ProgramData\FabMatrix',
    [int]$Port = 28081,
    [string]$JwtSecret = '',
    [string]$AdminSecret = '',
    [string]$CorsOrigins = '*',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

function New-RandomSecret {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return ([Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_')).TrimEnd('=')
}

function New-BootstrapCredentialFile([string]$Path) {
    $token = New-RandomSecret
    $expiresAt = [DateTimeOffset]::UtcNow.AddMinutes(30).ToUnixTimeMilliseconds()
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path,
        "expiresAt=$expiresAt$([Environment]::NewLine)token=$token$([Environment]::NewLine)", $utf8)
}

function ConvertTo-YamlSingleQuoted([string]$Value) {
    if ($null -eq $Value) { return "''" }
    return "'" + $Value.Replace("'", "''") + "'"
}

$configDir = [IO.Path]::GetFullPath($ConfigDir)
$dataRoot = [IO.Path]::GetFullPath($DataRoot)
$configPath = Join-Path $configDir 'application.yml'
$databaseDir = Join-Path $dataRoot 'database'
$fileDir = Join-Path $dataRoot 'files'
$logsDir = Join-Path $dataRoot 'logs'
$backupsDir = Join-Path $dataRoot 'backups'
$bootstrapTokenPath = Join-Path $configDir 'bootstrap.token'

foreach ($directory in @($dataRoot, $configDir, $databaseDir, $fileDir, $logsDir, $backupsDir)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

if ((Test-Path -LiteralPath $configPath) -and -not $Force) {
    Write-Output "Farm configuration already exists: $configPath"
    exit 0
}

if ([string]::IsNullOrWhiteSpace($JwtSecret)) { $JwtSecret = New-RandomSecret }
if ([string]::IsNullOrWhiteSpace($AdminSecret)) { $AdminSecret = New-RandomSecret }

$corsYaml = ConvertTo-YamlSingleQuoted $CorsOrigins

    if (-not (Test-Path -LiteralPath $bootstrapTokenPath -PathType Leaf)) {
        New-BootstrapCredentialFile $bootstrapTokenPath
    }
    $portableDataRoot = '${FARM_DATA_DIR}'
    $portableDatabase = ConvertTo-YamlSingleQuoted "jdbc:sqlite:$portableDataRoot/database/farm.db"
    $portableFiles = ConvertTo-YamlSingleQuoted "$portableDataRoot/files"
    $portableLog = ConvertTo-YamlSingleQuoted "$portableDataRoot/logs/farm.log"
    $content = @"
# FabMatrix Server Windows Local Edition configuration. Generated at install time; do not commit this file.
spring:
  profiles:
    active: local
  lifecycle:
    timeout-per-shutdown-phase: 20s
  datasource:
    url: $portableDatabase
  sql:
    init:
      mode: always
server:
  address: 0.0.0.0
  port: $Port
  shutdown: graceful
logging:
  file:
    name: $portableLog
farm:
  local:
    storage-path: $portableFiles
  monitor:
    enabled: true
  discovery:
    enabled: true
    subnets: []
    interval: 5m
  scheduler:
    enabled: false
  security:
    cors-allowed-origins: $corsYaml
    first-admin-setup-enabled: true
    bootstrap-token-file: '${FARM_DATA_DIR}/config/bootstrap.token'
jwt:
  secret-key: $(ConvertTo-YamlSingleQuoted $JwtSecret)
admin:
  secret-key: $(ConvertTo-YamlSingleQuoted $AdminSecret)
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
"@

$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($configPath, $content.Trim() + [Environment]::NewLine, $utf8)
Write-Output "Farm configuration created: $configPath"
