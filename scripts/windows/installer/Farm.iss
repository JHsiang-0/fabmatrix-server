#ifndef AppVersion
#define AppVersion "1.0.0"
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
OutputBaseFilename=FabMatrix-Server-Setup-{#AppVersion}
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
VersionInfoVersion=1.0.0.0
VersionInfoProductVersion=1.0.0.0
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

[Icons]
Name: "{group}\FabMatrixServerTray"; Filename: "{app}\FabMatrixTray.exe"; WorkingDir: "{app}"
Name: "{userstartup}\FabMatrixServerTray"; Filename: "{app}\FabMatrixTray.exe"; WorkingDir: "{app}"; Tasks: tray_autostart; Flags: runminimized

[Code]
var
  ServiceInstallSucceeded: Boolean;
  PurgeDataRequested: Boolean;
  UninstallCleanupExecuted: Boolean;

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

function HasPurgeDataConfirmSwitch(): Boolean;
var
  Tail: String;
  Token: String;
  StartAt: Integer;
  EndAt: Integer;
  ValidStart: Boolean;
  ValidEnd: Boolean;
begin
  Tail := Uppercase(GetCmdTail());
  Token := '/PURGEDATA=CONFIRM';
  StartAt := Pos(Token, Tail);
  while StartAt > 0 do
  begin
    EndAt := StartAt + Length(Token);
    if StartAt = 1 then
      ValidStart := True
    else
      ValidStart := Tail[StartAt - 1] in [' ', #9];
    if EndAt > Length(Tail) then
      ValidEnd := True
    else
      ValidEnd := Tail[EndAt] in [' ', #9];
    if ValidStart and ValidEnd then
    begin
      Result := True;
      Exit;
    end;
    Delete(Tail, 1, EndAt - 1);
    StartAt := Pos(Token, Tail);
  end;
  Result := False;
end;

function ConfirmCompleteUninstall(): Boolean;
begin
  Result := MsgBox(
    '完全卸载将永久删除 FabMatrix 的账号、数据库、任务、文件、配置和备份，且无法恢复。请先确认已完成备份。' + #13#10 + #13#10 +
    '将清理以下项目专属位置：' + #13#10 +
    '当前业务数据：' + GetDataRoot('') + #13#10 +
    '旧版 Farm 数据：' + ExpandConstant('{commonappdata}\Farm') + '（data/config/uploads/logs/backups）' + #13#10 +
    'FabMatrix 安装诊断及残留：' + ExpandConstant('{commonappdata}\FabMatrix') + #13#10 + #13#10 +
    '删除后重装不会再迁回旧账号或旧端口。确定继续吗？选择“否”将退出卸载。',
    mbCriticalError, MB_YESNO or MB_DEFBUTTON2) = IDYES;
end;

function InitializeUninstall(): Boolean;
var
  ModeChoice: Integer;
begin
  UninstallCleanupExecuted := False;
  PurgeDataRequested := HasPurgeDataConfirmSwitch();
  if PurgeDataRequested then
  begin
    if UninstallSilent then
      Result := True
    else
      Result := ConfirmCompleteUninstall();
    Exit;
  end;

  if UninstallSilent then
  begin
    { /SILENT never implies data removal. }
    Result := True;
    Exit;
  end;

  ModeChoice := MsgBox(
    '请选择 FabMatrix 卸载模式：' + #13#10 + #13#10 +
    '“是”=普通卸载：删除服务、托盘、程序和防火墙规则，保留全部业务数据。' + #13#10 +
    '“否”=完全卸载：进入下一步不可逆确认，确认后永久删除当前数据、旧版 Farm 数据及项目诊断残留。' + #13#10 +
    '“取消”=退出卸载。' + #13#10 + #13#10 +
    '建议先备份业务数据。',
    mbConfirmation, MB_YESNOCANCEL);
  if ModeChoice = IDCANCEL then
  begin
    Result := False;
    Exit;
  end;
  if ModeChoice = IDNO then
  begin
    PurgeDataRequested := ConfirmCompleteUninstall();
    Result := PurgeDataRequested;
    Exit;
  end;
  Result := True;
end;

procedure RunUninstallCleanupChecked();
var
  ResultCode: Integer;
  PowerShell: String;
  UninstallScript: String;
  DataRoot: String;
  LegacyDataRoot: String;
  DiagnosticsRoot: String;
  Parameters: String;
  FailureMessage: String;
begin
  if UninstallCleanupExecuted then
  begin
    Log('FabMatrix uninstall cleanup was already executed; duplicate callback ignored.');
    Exit;
  end;
  UninstallCleanupExecuted := True;

  ResultCode := -1;
  PowerShell := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
  UninstallScript := ExpandConstant('{app}\service\uninstall-service.ps1');
  DataRoot := GetDataRoot('');
  LegacyDataRoot := ExpandConstant('{commonappdata}\Farm');
  DiagnosticsRoot := ExpandConstant('{commonappdata}\FabMatrix');
  Parameters := '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + UninstallScript +
    '" -InstallDir "' + ExpandConstant('{app}') + '" -DataRoot "' + DataRoot +
    '" -LegacyDataRoot "' + LegacyDataRoot + '" -DiagnosticsRoot "' + DiagnosticsRoot + '" -Port 28081';
  if PurgeDataRequested then
    Parameters := Parameters + ' -PurgeData';
  if UninstallSilent then
    Parameters := Parameters + ' -Silent';

  Log('Starting checked FabMatrix uninstall cleanup before file removal.');
  if (not Exec(PowerShell, Parameters, '', SW_HIDE, ewWaitUntilTerminated, ResultCode)) or
     (ResultCode <> 0) then
  begin
    FailureMessage := 'FabMatrix 卸载清理失败（退出码 ' + IntToStr(ResultCode) +
      '），卸载已中止。数据未确认完全删除。当前数据：' + DataRoot +
      '；旧版数据：' + LegacyDataRoot + '；诊断残留：' + DiagnosticsRoot;
    Log(FailureMessage);
    if not UninstallSilent then
      MsgBox(FailureMessage, mbCriticalError, MB_OK);
    RaiseException('FabMatrix uninstall cleanup failed with exit code ' + IntToStr(ResultCode));
  end;

  if PurgeDataRequested then
    Log('FabMatrix service, tray, firewall rule, current data, legacy Farm data and diagnostics were removed.')
  else
    Log('FabMatrix service, tray and firewall rule were removed. Current data retained at: ' + DataRoot +
      '; legacy data retained at: ' + LegacyDataRoot + '; diagnostics retained at: ' + DiagnosticsRoot);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
    RunUninstallCleanupChecked();
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
    '" -StateFile "' + StateFile + '" -InstallDir "' + ExpandConstant('{app}') + '"',
    '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and (ResultCode = 0);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  if not CaptureStateAndStopPreviousProcesses() then
    Result := '升级安装预检未能在文件复制前释放旧 Farm/FabMatrixServer 服务、托盘或 runtime 子进程，安装已中止。请检查服务状态后重试。';
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
  { Keep diagnostics outside the hardened business-data subdirectories so an ACL
    failure can never hide its own error from the interactive installer user. }
  ServiceLog := ExpandConstant('{commonappdata}\FabMatrix\installer\service-install.log');
  Parameters := '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + InstallScript +
    '" -InstallDir "' + ExpandConstant('{app}') + '" -DataRoot "' + GetDataRoot('') +
    '" -LegacyDataRoot "' + ExpandConstant('{commonappdata}\Farm') +
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
