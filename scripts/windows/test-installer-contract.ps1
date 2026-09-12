param(
    [switch]$SkipTrayCompile,
    [string]$PayloadPath = '',
    [string]$ReleaseInfoPath = ''
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

function Assert-Exists([string]$Path, [string]$Description) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "Contract path missing: $Description ($Path)" }
}

$installer = Join-Path $scriptRoot 'installer\Farm.iss'
$configure = Join-Path $scriptRoot 'service\Farm-Configure.ps1'
$install = Join-Path $scriptRoot 'service\install-service.ps1'
$uninstall = Join-Path $scriptRoot 'service\uninstall-service.ps1'
$capture = Join-Path $scriptRoot 'service\capture-upgrade-state.ps1'
$migrate = Join-Path $scriptRoot 'service\migrate-legacy-data.ps1'
$portableConfig = Join-Path $scriptRoot 'service\portable-config.ps1'
$dataAcl = Join-Path $scriptRoot 'service\data-acl.ps1'
$recovery = Join-Path $scriptRoot 'service\recover-upgrade-state.ps1'
$serviceXml = Join-Path $scriptRoot 'service\service-xml.ps1'
$control = Join-Path $scriptRoot 'service\service-control.ps1'
$build = Join-Path $scriptRoot 'build-installer.ps1'
$tray = Join-Path $projectRoot 'src\main\java\com\example\farm\tray\FabMatrixTray.java'
$backup = Join-Path $projectRoot 'scripts\local-backup.ps1'
$restore = Join-Path $projectRoot 'scripts\local-restore.ps1'

# Parse every Windows PowerShell script without executing service/firewall operations.
Get-ChildItem -LiteralPath $scriptRoot -Recurse -File -Filter '*.ps1' | ForEach-Object {
    $tokens = $null
    $errors = $null
    $source = Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8
    [void][System.Management.Automation.Language.Parser]::ParseInput($source, $_.FullName, [ref]$tokens, [ref]$errors)
    if ($errors.Count -ne 0) { throw "PowerShell AST parse failed: $($_.FullName): $($errors[0].Message)" }
}

Assert-Contains $installer 'AppName=FabMatrix Server' 'installer brand'
Assert-Contains $installer 'DefaultDirName=\{autopf\}\\FabMatrix' 'default install directory'
Assert-Contains $installer 'ExtractFileDrive\(ExpandConstant\(''\{sd\}''\)\)' 'valid system drive constant'
Assert-NotContains $installer '\{sysdrive\}' 'invalid Inno system drive constant'
Assert-Contains $installer 'commonappdata.*FabMatrix|GetDataRoot' 'system drive data root'
Assert-Contains $installer '\{app\}.*data|Result := AddBackslash.*data' 'non-system drive data root'
Assert-Contains $installer 'uninsneveruninstall' 'data retention declaration'
Assert-NotContains $installer 'UninstallDelete[\s\S]*(commonappdata|\\data|\\database|\\files|\\backups)' 'recursive data deletion'
Assert-Contains $installer 'capture-upgrade-state\.ps1|PreserveExistingState|StateFile' 'upgrade service state handoff'
$installerText = Get-Content -LiteralPath $installer -Raw
$runSection = [regex]::Match($installerText, '(?ms)^\[Run\]\s*(.*?)(?=^\[)')
if (-not $runSection.Success -or $runSection.Groups[1].Value -match 'install-service\.ps1') {
    throw 'Service installation must not run from the unchecked Inno [Run] section.'
}
Assert-Contains $installer 'CurStepChanged|InstallServiceChecked' 'checked post-copy service install hook'
Assert-Contains $installer 'Exec\(PowerShell, Parameters.*ResultCode|ResultCode <> 0' 'service install exit-code check'
Assert-Contains $installer 'RestoreExistingServiceState|recover-upgrade-state\.ps1' 'upgrade failure state recovery'
Assert-Contains $installer 'RaiseException' 'service install failure abort'
Assert-Contains $installer 'Name: "tray_autostart"[^\r\n]*Flags: checkedonce' 'default tray autostart choice'

Assert-Contains $configure 'address: 0\.0\.0\.0' 'server address'
Assert-Contains $configure 'shutdown: graceful' 'graceful shutdown'
Assert-Contains $configure 'enabled: false' 'scheduler and Swagger disabled'
Assert-Contains $configure "portableDataRoot = '\$\{FARM_DATA_DIR\}'" 'portable data root placeholder'
Assert-Contains $configure 'portableDataRoot/database/farm\.db|database/farm\.db' 'portable SQLite path'
Assert-Contains $configure 'portableDataRoot/files|portableFiles' 'portable files path'
Assert-Contains $install '<id>FabMatrixServer</id>|New-FabMatrixServiceXml' 'fixed service name'
Assert-Contains $install 'Private|New-NetFirewallRule' 'private firewall rule'
Assert-NotContains $install 'Profile\s+Private[^\r\n]*Public|Profile.*Public' 'public firewall profile'
Assert-Contains $install 'Set-FabMatrixDataAcl' 'shared data ACL policy'
Assert-Contains $dataAcl 'S-1-5-32-545|S-1-5-11' 'explicit broad ACL removal identities'
Assert-Contains $dataAcl '/remove:g|/remove:d' 'explicit broad ACL removal operations'
Assert-Contains $capture 'StartMode|WasRunning|Stop-ServiceReliably' 'upgrade state capture'
Assert-Contains $migrate 'data\\farm\.db|uploads|pre-fabmatrix-migration|New data root is already initialized' 'legacy migration mapping and guard'
Assert-Contains $migrate 'Set-FabMatrixPortableConfig' 'legacy config normalization'
Assert-Contains $portableConfig 'datasource|FARM_DATA_DIR|storage-path|logs/farm\.log' 'portable path insertion and normalization'
Assert-Contains $recovery 'StartMode|WasRunning|sc\.exe config' 'best-effort original service recovery'
Assert-Contains $serviceXml '&quot;|ConvertTo-XmlText|stoptimeout>30sec' 'quoted escaped WinSW arguments and stop timeout'
Assert-Contains $control 'ValidateSet' 'tray service actions'
Assert-Contains $tray 'SystemTray|FabMatrixTray' 'pure JDK tray controller'
Assert-Contains $tray 'tray\.lock|tryLock' 'single instance'
Assert-Contains $tray 'EncodedCommand|self-test-uac-command' 'encoded UAC command construction'
Assert-NotContains $tray 'dataRoot\.resolve\("backups"\)' 'ordinary tray cannot browse private backups'
Assert-Contains $uninstall "Get-Process -Name 'FabMatrixTray', 'FarmTray'" 'uninstall closes only tray executables'
Assert-NotContains $build "'\-Xrs'" 'service launcher must receive console control events'
Assert-Contains $build "'--win-console'" 'console main launcher'
Assert-Contains $build 'Remove-Item -LiteralPath \$inputPath|inputFiles.Count -ne 2' 'clean and exclusive jpackage input'
Assert-Contains $build 'maintenance.*local-backup\.ps1|local-backup\.ps1.*maintenance' 'maintenance payload scripts'
Assert-Contains $build 'System\.Diagnostics\.ProcessStartInfo|Invoke-CapturedProcess' 'safe native Java metadata capture'
Assert-Contains $build "releaseInfo -match 'NativeCommandError\|CategoryInfo'" 'release metadata error pollution guard'
Assert-NotContains $build '& \$javaExe -version 2>&1|& java -XshowSettings:properties -version 2>&1' 'PowerShell native error-stream Java capture'
Assert-Contains $backup 'database.*farm\.db|config.*application\.yml|files' 'backup coverage'
Assert-Contains $backup '\$\{FARM_DATA_DIR\}' 'portable backup config'
Assert-Contains $restore 'Move-Item|restore-staging|installedNames|rollback was attempted' 'replacement restore and rollback'
Assert-Contains $restore 'Set-FabMatrixDataAcl' 'restore reapplies data ACL policy'
Assert-Contains $restore "Import-MaintenanceHelper 'data-acl\.ps1'" 'packaged restore ACL helper'

# Generate and parse WinSW XML for paths with spaces; this does not install a service.
. $serviceXml
$autoStopped = Resolve-FabMatrixServicePlan -PreservedState ([pscustomobject]@{
        ExistingServiceName = 'Farm'; StartMode = 'Auto'; WasRunning = $false
    }) -AutoStartRequested $false
$manualRunning = Resolve-FabMatrixServicePlan -PreservedState ([pscustomobject]@{
        ExistingServiceName = 'FabMatrixServer'; StartMode = 'Manual'; WasRunning = $true
    }) -AutoStartRequested $false
$disabled = Resolve-FabMatrixServicePlan -PreservedState ([pscustomobject]@{
        ExistingServiceName = 'FabMatrixServer'; StartMode = 'Disabled'; WasRunning = $false
    }) -AutoStartRequested $true
if ($autoStopped.StartMode -ne 'Automatic' -or $autoStopped.ShouldStart -or
    $manualRunning.StartMode -ne 'Manual' -or -not $manualRunning.ShouldStart -or
    -not $disabled.DisableAfterInstall -or $disabled.ShouldStart) {
    throw 'Upgrade service StartType/running-state plan was not preserved.'
}
$xmlText = New-FabMatrixServiceXml -InstallDir 'D:\Program Files\FabMatrix' -DataRoot 'D:\Program Files\FabMatrix\data' -StartMode Automatic
$xmlDocument = [xml]$xmlText
$expectedArguments = '--spring.config.additional-location="file:D:/Program Files/FabMatrix/data/config/"'
if ([string]$xmlDocument.service.arguments -ne $expectedArguments -or $xmlText -notmatch '&quot;') {
    throw 'WinSW XML dry-run did not preserve quoted Program Files config URL.'
}
if ([int](([string]$xmlDocument.service.stoptimeout) -replace '\D', '') -lt 20) {
    throw 'WinSW stop timeout is shorter than graceful shutdown timeout.'
}
$specialXml = New-FabMatrixServiceXml -InstallDir 'D:\Program Files\Fab&Matrix' -DataRoot 'D:\Program Files\Fab&Matrix\data'
if ($specialXml -notmatch 'Fab&amp;Matrix' -or ([xml]$specialXml).service.arguments -notmatch 'Fab&Matrix') {
    throw 'WinSW XML dry-run did not XML-escape special path characters.'
}

# Generate a disposable Local config and verify it contains no source-machine data path.
$configTemp = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixConfigContract-' + [guid]::NewGuid().ToString('N'))
try {
    $configData = Join-Path $configTemp 'D Program Files\FabMatrix\data'
    $configDir = Join-Path $configData 'config'
    & $configure -Edition Local -ConfigDir $configDir -DataRoot $configData -Force | Out-Null
    $generatedConfig = Join-Path $configDir 'application.yml'
    Assert-Contains $generatedConfig '\$\{FARM_DATA_DIR\}/database/farm\.db' 'generated portable SQLite path'
    Assert-Contains $generatedConfig '\$\{FARM_DATA_DIR\}/files' 'generated portable files path'
    if ((Get-Content -LiteralPath $generatedConfig -Raw).Contains(($configData -replace '\\', '/'))) {
        throw 'Generated Local config retained its source-machine absolute path.'
    }
} finally {
    if (Test-Path -LiteralPath $configTemp) { Remove-Item -LiteralPath $configTemp -Recurse -Force }
}

# Exercise legacy migration only against disposable temp fixtures.
$migrationTemp = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixMigrationContract-' + [guid]::NewGuid().ToString('N'))
try {
    $legacy = Join-Path $migrationTemp 'legacy'
    $newData = Join-Path $migrationTemp 'new-data'
    New-Item -ItemType Directory -Force -Path (Join-Path $legacy 'data'), (Join-Path $legacy 'uploads'), (Join-Path $legacy 'config'),
        (Join-Path $newData 'database'), (Join-Path $newData 'files'), (Join-Path $newData 'config') | Out-Null
    [IO.File]::WriteAllText((Join-Path $legacy 'data\farm.db'), 'fixture')
    [IO.File]::WriteAllText((Join-Path $legacy 'uploads\sample.gcode'), 'G1 X1')
    $legacyConfigText = @"
# Farm Windows configuration. Generated by Farm-Configure.ps1.
spring:
  profiles:
    active: local
server:
  port: 8181
logging:
  file:
    path: 'C:/ProgramData/Farm/logs'
    name: farm
farm:
  local:
    storage-path: 'C:/ProgramData/Farm/uploads'
  security:
    cors-allowed-origins: 'http://legacy.example:5173'
    first-admin-setup-enabled: true
jwt:
  secret-key: 'legacy-jwt-secret'
admin:
  secret-key: 'legacy-admin-secret'
"@
    [IO.File]::WriteAllText((Join-Path $legacy 'config\application.yml'), $legacyConfigText)
    & $migrate -DataRoot $newData -LegacyDataRoot $legacy | Out-Null
    Assert-Exists (Join-Path $newData 'database\farm.db') 'migrated database'
    Assert-Exists (Join-Path $newData 'files\sample.gcode') 'migrated uploads'
    $migratedConfig = Join-Path $newData 'config\application.yml'
    Assert-Contains $migratedConfig 'jdbc:sqlite:\$\{FARM_DATA_DIR\}/database/farm\.db' 'inserted portable migrated database URL'
    Assert-Contains $migratedConfig '\$\{FARM_DATA_DIR\}/files' 'portable migrated files path'
    Assert-Contains $migratedConfig '\$\{FARM_DATA_DIR\}/logs/farm\.log' 'portable migrated logs path'
    Assert-Contains $migratedConfig 'port: 8181' 'preserved legacy port'
    Assert-Contains $migratedConfig 'http://legacy\.example:5173' 'preserved legacy CORS'
    Assert-Contains $migratedConfig 'legacy-jwt-secret' 'preserved legacy JWT secret'
    Assert-Contains $migratedConfig 'legacy-admin-secret' 'preserved legacy admin secret'
    $migrationBackup = Get-ChildItem -LiteralPath (Join-Path $legacy 'backups') -Directory -Filter 'pre-fabmatrix-migration-*' | Select-Object -First 1
    if ($null -eq $migrationBackup) { throw 'Legacy migration backup was not created.' }

    [IO.File]::WriteAllText((Join-Path $newData 'database\farm.db'), 'new-data-wins')
    & $migrate -DataRoot $newData -LegacyDataRoot $legacy | Out-Null
    if ([IO.File]::ReadAllText((Join-Path $newData 'database\farm.db')) -ne 'new-data-wins') {
        throw 'Initialized new data root was overwritten by legacy migration.'
    }
} finally {
    if (Test-Path -LiteralPath $migrationTemp) { Remove-Item -LiteralPath $migrationTemp -Recurse -Force }
}

# Exercise the real Windows ACL policy only against disposable temp directories.
$aclTemp = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixAclContract-' + [guid]::NewGuid().ToString('N'))
$currentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
try {
    foreach ($relative in @('config', 'database', 'backups', 'files', 'logs')) {
        $path = Join-Path $aclTemp $relative
        New-Item -ItemType Directory -Force -Path $path | Out-Null
        # Keep the non-elevated test owner able to inspect/clean the fixture; production calls run elevated.
        & icacls.exe $path '/grant' "*$currentSid`:(OI)(CI)F" '*S-1-5-32-545:(OI)(CI)F' '*S-1-5-11:(OI)(CI)F' '/T' '/C' '/Q' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Unable to seed disposable ACL fixture: $path" }
    }
    . $dataAcl
    Set-FabMatrixDataAcl -DataRoot $aclTemp

    foreach ($relative in @('config', 'database', 'backups')) {
        $rules = (Get-Acl -LiteralPath (Join-Path $aclTemp $relative)).Access
        $sids = @($rules | ForEach-Object {
                try { $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value }
                catch { $_.IdentityReference.Value }
            })
        foreach ($forbiddenSid in @('S-1-5-32-545', 'S-1-5-11', 'S-1-1-0')) {
            if ($sids -contains $forbiddenSid) { throw "Private ACL retained broad principal $forbiddenSid on $relative" }
        }
        foreach ($requiredSid in @('S-1-5-18', 'S-1-5-32-544')) {
            if ($sids -notcontains $requiredSid) { throw "Private ACL is missing $requiredSid on $relative" }
        }
    }
    foreach ($relative in @('files', 'logs')) {
        $usersRule = (Get-Acl -LiteralPath (Join-Path $aclTemp $relative)).Access | Where-Object {
            try { $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value -eq 'S-1-5-32-545' }
            catch { $false }
        } | Select-Object -First 1
        if ($null -eq $usersRule -or ($usersRule.FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::Write) -ne 0) {
            throw "Shared ACL must grant Users read-only access on $relative"
        }
    }
} finally {
    if (Test-Path -LiteralPath $aclTemp) {
        & icacls.exe $aclTemp '/grant' "*$currentSid`:(OI)(CI)F" '/T' '/C' '/Q' | Out-Null
        Remove-Item -LiteralPath $aclTemp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if (-not [string]::IsNullOrWhiteSpace($PayloadPath)) {
    $payload = [IO.Path]::GetFullPath($PayloadPath)
    foreach ($relative in @('FabMatrixServer.exe', 'FabMatrixTray.exe', 'maintenance\local-backup.ps1',
            'maintenance\local-restore.ps1', 'maintenance\data-acl.ps1', 'maintenance\portable-config.ps1',
            'service\capture-upgrade-state.ps1', 'service\data-acl.ps1', 'service\migrate-legacy-data.ps1',
            'service\portable-config.ps1', 'service\recover-upgrade-state.ps1', 'service\service-xml.ps1')) {
        Assert-Exists (Join-Path $payload $relative) "payload $relative"
    }
}

if (-not [string]::IsNullOrWhiteSpace($ReleaseInfoPath)) {
    Assert-Exists $ReleaseInfoPath 'release metadata'
    Assert-NotContains $ReleaseInfoPath 'NativeCommandError|CategoryInfo' 'PowerShell native error metadata'
}

if (-not $SkipTrayCompile) {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $settings = (& java -XshowSettings:properties -version 2>&1 | Out-String)
    $ErrorActionPreference = $previousErrorAction
    $match = [regex]::Match($settings, '(?m)^\s*java\.home\s*=\s*(.+?)\s*$')
    if (-not $match.Success) { throw 'Unable to locate Java for tray compilation.' }
    $javaHome = $match.Groups[1].Value.Trim()
    $javac = Join-Path $javaHome 'bin\javac.exe'
    $java = Join-Path $javaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) { throw "Java tools not found: $javaHome" }
    $temp = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrixTrayContract-' + [guid]::NewGuid().ToString('N'))
    try {
        New-Item -ItemType Directory -Force -Path $temp | Out-Null
        & $javac '--release' '25' '-encoding' 'UTF-8' '-d' $temp $tray
        if ($LASTEXITCODE -ne 0) { throw "Tray source compilation failed: $LASTEXITCODE" }
        & $java '-cp' $temp 'com.example.farm.tray.FabMatrixTray' '--self-test-uac-command'
        if ($LASTEXITCODE -ne 0) { throw "Tray UAC command self-test failed: $LASTEXITCODE" }
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
    }
}

Write-Host 'FabMatrix Windows installer contract checks passed.'
