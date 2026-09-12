param(
    [switch]$SkipTrayCompile
)

$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptRoot '..\..')).Path

function Assert-Contains([string]$Path, [string]$Pattern, [string]$Description) {
    $content = Get-Content -Raw -LiteralPath $Path
    if ($content -notmatch $Pattern) { throw "Contract check failed: $Description ($Path)" }
}

function Assert-NotContains([string]$Path, [string]$Pattern, [string]$Description) {
    $content = Get-Content -Raw -LiteralPath $Path
    if ($content -match $Pattern) { throw "Contract check must not match: $Description ($Path)" }
}

$installer = Join-Path $scriptRoot 'installer\Farm.iss'
$configure = Join-Path $scriptRoot 'service\Farm-Configure.ps1'
$install = Join-Path $scriptRoot 'service\install-service.ps1'
$control = Join-Path $scriptRoot 'service\service-control.ps1'
$tray = Join-Path $projectRoot 'src\main\java\com\example\farm\tray\FabMatrixTray.java'
$backup = Join-Path $projectRoot 'scripts\local-backup.ps1'
$restore = Join-Path $projectRoot 'scripts\local-restore.ps1'

Assert-Contains $installer 'AppName=FabMatrix Server' 'installer brand'
Assert-Contains $installer 'DefaultDirName=\{autopf\}\\FabMatrix' 'default install directory'
Assert-Contains $installer 'commonappdata.*FabMatrix|GetDataRoot' 'system drive data root'
Assert-Contains $installer '\{app\}.*data|Result := AddBackslash.*data' 'non-system drive data root'
Assert-Contains $installer 'uninsneveruninstall' 'data retention declaration'
Assert-NotContains $installer 'UninstallDelete[\s\S]*(commonappdata|\\data|\\database|\\files|\\backups)' 'recursive data deletion'
Assert-Contains $configure 'address: 0\.0\.0\.0' 'server address'
Assert-Contains $configure 'shutdown: graceful' 'graceful shutdown'
Assert-Contains $configure 'enabled: false' 'scheduler and Swagger disabled'
Assert-Contains $configure 'jdbc:sqlite:.*database' 'SQLite database layout'
Assert-Contains $configure 'storage-path:|Join-Path \$dataRoot ''files''' 'local files layout'
Assert-Contains $install '<id>FabMatrixServer</id>|FabMatrixServerService' 'fixed service name'
Assert-Contains $install 'Private|New-NetFirewallRule' 'private firewall rule'
Assert-NotContains $install 'Profile\s+Private[^\r\n]*Public|Profile.*Public' 'public firewall profile'
Assert-Contains $install 'S-1-5-32-544|icacls' 'data ACL'
Assert-Contains $control 'ValidateSet' 'tray service actions'
Assert-Contains $tray 'SystemTray|FabMatrixTray' 'pure JDK tray controller'
Assert-Contains $tray 'tray\.lock|tryLock' 'single instance'
Assert-Contains $backup 'database.*farm\.db|config.*application\.yml|files' 'backup coverage'
Assert-Contains $restore 'pre-restore|Previous.*preserved' 'pre-restore preservation'

if (-not $SkipTrayCompile) {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $settings = (& java -XshowSettings:properties -version 2>&1 | Out-String)
    $ErrorActionPreference = $previousErrorAction
    $match = [regex]::Match($settings, '(?m)^\s*java\.home\s*=\s*(.+?)\s*$')
    if (-not $match.Success) { throw 'Unable to locate Java for tray compilation.' }
    $javac = Join-Path $match.Groups[1].Value.Trim() 'bin\javac.exe'
    if (-not (Test-Path -LiteralPath $javac)) { throw "javac not found: $javac" }
    $temp = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixTrayContract-' + [guid]::NewGuid().ToString('N'))
    try {
        New-Item -ItemType Directory -Force -Path $temp | Out-Null
        & $javac '--release' '25' '-encoding' 'UTF-8' '-d' $temp $tray
        if ($LASTEXITCODE -ne 0) { throw "Tray source compilation failed: $LASTEXITCODE" }
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
    }
}

Write-Host 'FabMatrix Windows installer contract checks passed.'
