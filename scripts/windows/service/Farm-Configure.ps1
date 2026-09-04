param(
    [ValidateSet('Local', 'Server')]
    [string]$Edition = 'Local',
    [string]$ConfigDir = 'C:\ProgramData\Farm\config',
    [string]$DataRoot = 'C:\ProgramData\Farm',
    [int]$Port = 8080,
    [string]$SpringProfile = '',
    [string]$MysqlUrl = 'jdbc:mysql://localhost:3306/farm?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=true',
    [string]$MysqlUsername = 'farm',
    [string]$MysqlPassword = '',
    [string]$RedisHost = 'localhost',
    [int]$RedisPort = 6379,
    [string]$RedisPassword = '',
    [string]$RustFsEndpoint = 'http://localhost:9000',
    [string]$RustFsAccessKey = '',
    [string]$RustFsSecretKey = '',
    [string]$JwtSecret = '',
    [string]$AdminSecret = '',
    [string]$CorsOrigins = '*',
    [string]$LocalDataDir = '',
    [string]$LocalFileDir = '',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

function New-RandomSecret {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=')
}

function ConvertTo-YamlSingleQuoted([string]$Value) {
    if ($null -eq $Value) { return "''" }
    return "'" + $Value.Replace("'", "''") + "'"
}

$configDir = [IO.Path]::GetFullPath($ConfigDir)
$dataRoot = [IO.Path]::GetFullPath($DataRoot)
$configPath = Join-Path $configDir 'application.yml'
$logsDir = Join-Path $dataRoot 'logs'
$dataDir = if ([string]::IsNullOrWhiteSpace($LocalDataDir)) { Join-Path $dataRoot 'data' } else { [IO.Path]::GetFullPath($LocalDataDir) }
$fileDir = if ([string]::IsNullOrWhiteSpace($LocalFileDir)) { Join-Path $dataRoot 'uploads' } else { [IO.Path]::GetFullPath($LocalFileDir) }

foreach ($directory in @($dataRoot, $configDir, $logsDir, $dataDir, $fileDir)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

if ((Test-Path -LiteralPath $configPath) -and -not $Force) {
    Write-Output "Farm configuration already exists: $configPath"
    exit 0
}

if ([string]::IsNullOrWhiteSpace($JwtSecret)) { $JwtSecret = New-RandomSecret }
if ([string]::IsNullOrWhiteSpace($AdminSecret)) { $AdminSecret = New-RandomSecret }

$profile = if ([string]::IsNullOrWhiteSpace($SpringProfile)) {
    if ($Edition -eq 'Local') { 'local' } else { 'prod' }
} else { $SpringProfile }

$dataDirYaml = ConvertTo-YamlSingleQuoted ($dataDir -replace '\\', '/')
$fileDirYaml = ConvertTo-YamlSingleQuoted ($fileDir -replace '\\', '/')
$logsDirYaml = ConvertTo-YamlSingleQuoted ($logsDir -replace '\\', '/')
$corsYaml = ConvertTo-YamlSingleQuoted $CorsOrigins

if ($Edition -eq 'Local') {
    $content = @"
# Farm Windows configuration. Generated at install time; do not commit this file.
spring:
  profiles:
    active: local
server:
  port: $Port
logging:
  file:
    path: $logsDirYaml
    name: farm
farm:
  local:
    storage-path: $fileDirYaml
  security:
    cors-allowed-origins: $corsYaml
    first-admin-setup-enabled: true
jwt:
  secret-key: $(ConvertTo-YamlSingleQuoted $JwtSecret)
admin:
  secret-key: $(ConvertTo-YamlSingleQuoted $AdminSecret)
"@
} else {
    $content = @"
# Farm Windows Server Edition configuration. Generated at install time; do not commit this file.
spring:
  profiles:
    active: $(ConvertTo-YamlSingleQuoted $profile)
  datasource:
    url: $(ConvertTo-YamlSingleQuoted $MysqlUrl)
    username: $(ConvertTo-YamlSingleQuoted $MysqlUsername)
    password: $(ConvertTo-YamlSingleQuoted $MysqlPassword)
  data:
    redis:
      host: $(ConvertTo-YamlSingleQuoted $RedisHost)
      port: $RedisPort
      password: $(ConvertTo-YamlSingleQuoted $RedisPassword)
server:
  port: $Port
logging:
  file:
    path: $logsDirYaml
    name: farm
rustfs:
  endpoint: $(ConvertTo-YamlSingleQuoted $RustFsEndpoint)
  access-key: $(ConvertTo-YamlSingleQuoted $RustFsAccessKey)
  secret-key: $(ConvertTo-YamlSingleQuoted $RustFsSecretKey)
  bucket: farm
jwt:
  secret-key: $(ConvertTo-YamlSingleQuoted $JwtSecret)
admin:
  secret-key: $(ConvertTo-YamlSingleQuoted $AdminSecret)
farm:
  security:
    cors-allowed-origins: $corsYaml
"@
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($configPath, $content.Trim() + [Environment]::NewLine, $utf8)
Write-Output "Farm configuration created: $configPath"
