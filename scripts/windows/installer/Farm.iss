#ifndef AppVersion
#define AppVersion "2.0.0"
#endif
#ifndef PayloadDir
#define PayloadDir "..\..\dist\windows-installer\work\payload"
#endif
#ifndef OutputDir
#define OutputDir "..\..\dist\windows-installer"
#endif
#ifndef AssetsDir
#define AssetsDir "..\assets"
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
SetupIconFile={#AssetsDir}\installer.ico

[Tasks]
Name: "service_autostart"; Description: "随 Windows 自动启动 FabMatrixServer 服务"; Flags: checkedonce
Name: "tray_autostart"; Description: "登录 Windows 时自动启动 FabMatrix 托盘控制器"; Flags: checkedonce
Name: "launch_tray"; Description: "安装完成后启动 FabMatrix 托盘控制器"; Flags: checkedonce

[Files]
Source: "{#PayloadDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#PayloadDir}\service\capture-upgrade-state.ps1"; Flags: dontcopy

[Dirs]
Name: "{code:GetDataRoot}"; Flags: uninsneveruninstall
Name: "{code:GetDataRoot}\config"; Flags: uninsneveruninstall
Name: "{code:GetDataRoot}\database"; Flags: uninsneveruninstall
Name: "{code:GetDataRoot}\files"; Flags: uninsneveruninstall
Name: "{code:GetDataRoot}\logs"; Flags: uninsneveruninstall
Name: "{code:GetDataRoot}\backups"; Flags: uninsneveruninstall

[Run]
Filename: "{app}\FabMatrixTray.exe"; Tasks: launch_tray; Flags: nowait postinstall skipifsilent; Description: "启动 FabMatrix 托盘控制器"; Check: ShouldLaunchTray

[UninstallRun]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\service\uninstall-service.ps1"" -InstallDir ""{app}"" -DataRoot ""{code:GetDataRoot}"" -Port 28081"; RunOnceId: "FabMatrixServer"; Flags: runhidden waituntilterminated

[Icons]
Name: "{group}\FabMatrixServerTray"; Filename: "{app}\FabMatrixTray.exe"; WorkingDir: "{app}"
Name: "{userstartup}\FabMatrixServerTray"; Filename: "{app}\FabMatrixTray.exe"; WorkingDir: "{app}"; Tasks: tray_autostart; Flags: runminimized

[Code]
var
  ServiceInstallSucceeded: Boolean;

function GetDataRoot(Param: String): String;
begin
  if CompareText(ExtractFileDrive(ExpandConstant('{app}')), ExtractFileDrive(ExpandConstant('{sd}'))) = 0 then
    Result := ExpandConstant('{commonappdata}\FabMatrix')
  else
    Result := AddBackslash(ExpandConstant('{app}')) + 'data';
end;

function ShouldLaunchTray(): Boolean;
begin
  Result := ServiceInstallSucceeded;
end;

function CaptureStateAndStopPreviousProcesses(): Boolean;
var
  ResultCode: Integer;
  PowerShell: String;
  CaptureScript: String;
  StateFile: String;
begin
  ExtractTemporaryFile('capture-upgrade-state.ps1');
  PowerShell := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
  CaptureScript := ExpandConstant('{tmp}\capture-upgrade-state.ps1');
  StateFile := ExpandConstant('{tmp}\FabMatrixServer-upgrade-state.json');
  Result := Exec(PowerShell, '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + CaptureScript +
    '" -StateFile "' + StateFile + '"',
    '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and (ResultCode = 0);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  if not CaptureStateAndStopPreviousProcesses() then
    Result := '无法可靠停止旧 Farm/FabMatrixServer 服务或托盘进程，安装已中止。请检查服务状态后重试。';
end;

procedure RestoreExistingServiceState();
var
  ResultCode: Integer;
  PowerShell: String;
  RecoveryScript: String;
  StateFile: String;
begin
  ResultCode := -1;
  PowerShell := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
  RecoveryScript := ExpandConstant('{app}\service\recover-upgrade-state.ps1');
  StateFile := ExpandConstant('{tmp}\FabMatrixServer-upgrade-state.json');
  if FileExists(RecoveryScript) then
    Exec(PowerShell, '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + RecoveryScript +
      '" -StateFile "' + StateFile + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

procedure InstallServiceChecked();
var
  ResultCode: Integer;
  PowerShell: String;
  InstallScript: String;
  StateFile: String;
  ServiceLog: String;
  Parameters: String;
begin
  ServiceInstallSucceeded := False;
  ResultCode := -1;
  PowerShell := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
  InstallScript := ExpandConstant('{app}\service\install-service.ps1');
  StateFile := ExpandConstant('{tmp}\FabMatrixServer-upgrade-state.json');
  ServiceLog := AddBackslash(GetDataRoot('')) + 'logs\installer\service-install.log';
  Parameters := '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + InstallScript +
    '" -InstallDir "' + ExpandConstant('{app}') + '" -DataRoot "' + GetDataRoot('') +
    '" -Port 28081 -PreserveExistingState -StateFile "' + StateFile +
    '" -LogPath "' + ServiceLog + '"';
  if WizardIsTaskSelected('service_autostart') then
    Parameters := Parameters + ' -AutoStart';

  WizardForm.StatusLabel.Caption := '正在注册 FabMatrixServer Windows 服务...';
  if (not Exec(PowerShell, Parameters, '', SW_HIDE, ewWaitUntilTerminated, ResultCode)) or
     (ResultCode <> 0) then
  begin
    RestoreExistingServiceState();
    SuppressibleMsgBox('FabMatrixServer 服务安装失败（退出码 ' + IntToStr(ResultCode) +
      '）。安装将中止；已尽力恢复升级前仍存在的服务状态。详细日志：' + ServiceLog,
      mbCriticalError, MB_OK, IDOK);
    RaiseException('FabMatrixServer service installation failed with exit code ' + IntToStr(ResultCode));
  end;
  ServiceInstallSucceeded := True;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    InstallServiceChecked();
end;
