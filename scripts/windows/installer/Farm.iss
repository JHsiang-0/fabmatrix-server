#ifndef AppVersion
#define AppVersion "2.0.0"
#endif
#ifndef PayloadDir
#define PayloadDir "..\..\dist\windows-installer\work\payload"
#endif
#ifndef OutputDir
#define OutputDir "..\..\dist\windows-installer"
#endif

[Setup]
AppId={{A9C3F9A1-4E74-4C8A-9B08-9E9C0B7A4F2D}
AppName=FabMatrix Server
AppVersion={#AppVersion}
AppVerName=FabMatrix Server {#AppVersion}
AppPublisher=FabMatrix
AppPublisherURL=https://github.com/JHsiang-0/3d-printer-farm
DefaultDirName={autopf}\FabMatrix
DefaultGroupName=FabMatrix Server
OutputDir={#OutputDir}
OutputBaseFilename=FabMatrixServer-Setup
UninstallDisplayName=FabMatrix Server
UninstallDisplayIcon={app}\FabMatrixServer.exe
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
DisableProgramGroupPage=yes
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
SetupLogging=yes
VersionInfoDescription=FabMatrix Server Windows Local Edition installer
VersionInfoProductName=FabMatrix Server
VersionInfoProductVersion={#AppVersion}
ChangesEnvironment=no

[Tasks]
Name: "service_autostart"; Description: "随 Windows 自动启动 FabMatrixServer 服务"; Flags: checkedonce
Name: "tray_autostart"; Description: "登录 Windows 时自动启动 FabMatrix 托盘控制器"; Flags: unchecked
Name: "launch_tray"; Description: "安装完成后启动 FabMatrix 托盘控制器"; Flags: checkedonce

[Files]
Source: "{#PayloadDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Dirs]
Name: "{code:GetDataRoot}"; Flags: uninsneveruninstall
Name: "{code:GetDataRoot}\config"; Flags: uninsneveruninstall
Name: "{code:GetDataRoot}\database"; Flags: uninsneveruninstall
Name: "{code:GetDataRoot}\files"; Flags: uninsneveruninstall
Name: "{code:GetDataRoot}\logs"; Flags: uninsneveruninstall
Name: "{code:GetDataRoot}\backups"; Flags: uninsneveruninstall

[Run]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\service\install-service.ps1"" -InstallDir ""{app}"" -DataRoot ""{code:GetDataRoot}"" -Port 8080 -AutoStart"; Tasks: service_autostart; Flags: runhidden waituntilterminated; StatusMsg: "正在注册并启动 FabMatrixServer Windows 服务..."
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\service\install-service.ps1"" -InstallDir ""{app}"" -DataRoot ""{code:GetDataRoot}"" -Port 8080"; Tasks: not service_autostart; Flags: runhidden waituntilterminated; StatusMsg: "正在注册 FabMatrixServer Windows 服务..."
Filename: "{app}\FabMatrixTray.exe"; Tasks: launch_tray; Flags: nowait postinstall skipifsilent; Description: "启动 FabMatrix 托盘控制器"

[UninstallRun]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\service\uninstall-service.ps1"" -InstallDir ""{app}"" -DataRoot ""{code:GetDataRoot}"" -Port 8080"; RunOnceId: "FabMatrixServer"; Flags: runhidden waituntilterminated

[Icons]
Name: "{group}\FabMatrixServerTray"; Filename: "{app}\FabMatrixTray.exe"; WorkingDir: "{app}"
Name: "{userstartup}\FabMatrixServerTray"; Filename: "{app}\FabMatrixTray.exe"; WorkingDir: "{app}"; Tasks: tray_autostart; Flags: runminimized

[Code]
function GetDataRoot(Param: String): String;
begin
  if CompareText(ExtractFileDrive(ExpandConstant('{app}')), ExtractFileDrive(ExpandConstant('{sysdrive}'))) = 0 then
    Result := ExpandConstant('{commonappdata}\FabMatrix')
  else
    Result := AddBackslash(ExpandConstant('{app}')) + 'data';
end;

function StopPreviousProcesses(): Boolean;
var
  ResultCode: Integer;
  PowerShell: String;
  Command: String;
begin
  PowerShell := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
  Command := '$ErrorActionPreference=''Stop''; ' +
    'foreach($n in @(''FabMatrixServer'',''Farm'')){ $s=Get-Service -Name $n -ErrorAction SilentlyContinue; ' +
    'if($s -and $s.Status -ne ''Stopped''){ Stop-Service -InputObject $s -ErrorAction Stop; ' +
    '$s.WaitForStatus(''Stopped'',[TimeSpan]::FromSeconds(30)); $s.Refresh(); ' +
    'if($s.Status -ne ''Stopped''){ throw (''服务未能停止: '' + $n) } } }; ' +
    'Get-Process -Name ''FarmTray'',''FabMatrixTray'' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction Stop';
  Result := Exec(PowerShell, '-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "' + Command + '"',
    '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and (ResultCode = 0);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  if not StopPreviousProcesses() then
    Result := '无法可靠停止旧 Farm/FabMatrixServer 服务或托盘进程，安装已中止。请检查服务状态后重试。';
end;
