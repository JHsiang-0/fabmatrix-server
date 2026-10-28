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
AppName=嘉东三维服务器
AppVersion={#AppVersion}
AppVerName=嘉东三维服务器 {#AppVersion}
AppPublisher=FabMatrix
AppPublisherURL=https://github.com/JHsiang-0/3d-printer-farm
DefaultDirName={autopf}\FabMatrix
DefaultGroupName=嘉东三维服务器
OutputDir={#OutputDir}
OutputBaseFilename=FabMatrix-Server-Setup-{#AppVersion}
UninstallDisplayName=嘉东三维服务器
UninstallDisplayIcon={app}\FabMatrixServer.exe
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
DisableProgramGroupPage=yes
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
WizardSizePercent=120,125
DisableWelcomePage=no
DisableDirPage=no
ShowLanguageDialog=no
SetupLogging=yes
VersionInfoDescription=嘉东三维服务器安装程序
VersionInfoProductName=嘉东三维服务器
VersionInfoVersion=1.0.0.0
VersionInfoProductVersion=1.0.0.0
ChangesEnvironment=no
SetupIconFile={#AssetsDir}\installer.ico

[Languages]
Name: "chinesesimplified"; MessagesFile: "languages\ChineseSimplified.isl"

[Messages]
WelcomeLabel2=此向导将安装 FabMatrix 局域网服务端。%n%n一台电脑运行服务端，多台电脑通过独立安装的 FabMatrix 客户端连接。%n%n安装包已包含 Java 运行时和 SQLite，无需另装数据库。升级前请备份业务数据。
SelectTasksLabel2=选择服务和托盘的启动方式。服务负责后台运行；托盘用于查看状态、复制地址和管理服务。退出托盘不会停止服务。升级安装会保留原服务的启动方式和运行状态。

[Tasks]
Name: "service_autostart"; Description: "开机自动运行服务（新安装同时启动服务，无需登录 Windows）"; Flags: checkedonce
Name: "tray_autostart"; Description: "当前用户登录 Windows 后显示托盘图标（退出托盘不停止服务）"; Flags: checkedonce
Name: "launch_tray"; Description: "安装完成后打开托盘，查看服务状态和连接地址"; Flags: checkedonce

[Files]
Source: "{#PayloadDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#PayloadDir}\service\capture-upgrade-state.ps1"; Flags: dontcopy
Source: "{#PayloadDir}\service\installation-progress.ps1"; Flags: dontcopy

[Dirs]
Name: "{code:GetDataRoot}"; Flags: uninsneveruninstall
Name: "{code:GetDataRoot}\config"; Flags: uninsneveruninstall
Name: "{code:GetDataRoot}\database"; Flags: uninsneveruninstall
Name: "{code:GetDataRoot}\files"; Flags: uninsneveruninstall
Name: "{code:GetDataRoot}\logs"; Flags: uninsneveruninstall
Name: "{code:GetDataRoot}\backups"; Flags: uninsneveruninstall

[UninstallDelete]
; effective-port.txt is runtime metadata, not an installed payload file. Remove
; only this exact directory under the controlled installation root on both
; ordinary uninstall and complete uninstall.
Type: filesandordirs; Name: "{app}\service-state"

[InstallDelete]
; Remove only the previous shortcut names when upgrading this installation.
Type: files; Name: "{group}\FabMatrixServerTray.lnk"
Type: files; Name: "{userstartup}\FabMatrixServerTray.lnk"

[Run]
Filename: "{app}\FabMatrixTray.exe"; Tasks: launch_tray; Flags: nowait postinstall skipifsilent; Description: "启动 FabMatrix 托盘控制器"; Check: ShouldLaunchTray

[Icons]
Name: "{group}\嘉东三维服务器"; Filename: "{app}\FabMatrixTray.exe"; WorkingDir: "{app}"
Name: "{userstartup}\嘉东三维服务器"; Filename: "{app}\FabMatrixTray.exe"; WorkingDir: "{app}"; Tasks: tray_autostart; Flags: runminimized

[Code]
var
  ServiceInstallSucceeded: Boolean;
  PurgeDataRequested: Boolean;
  UninstallCleanupExecuted: Boolean;
  ConnectionInfoPage: TOutputMsgWizardPage;
  ProgressIsPreflight: Boolean;

function InstallationStageText(Stage: String): String;
begin
  if Stage = 'capture service state' then Result := '正在记录原服务状态'
  else if Stage = 'close previous tray' then Result := '正在关闭旧托盘'
  else if Pos('stop service ', Stage) = 1 then Result := '正在正常停止旧服务'
  else if Stage = 'wait for previous processes' then Result := '正在确认旧程序已退出'
  else if Pos('recover previous processes ', Stage) = 1 then Result := '正在清理已确认的旧程序残留'
  else if Stage = 'preflight complete' then Result := '升级预检完成，即将复制文件'
  else if Stage = 'migrate legacy data' then Result := '正在检查并迁移旧版数据'
  else if Stage = 'repair legacy data permissions' then Result := '正在检查旧数据访问权限'
  else if Stage = 'prepare data directories' then Result := '正在准备数据目录'
  else if Stage = 'prepare local configuration' then Result := '正在准备本地配置'
  else if Stage = 'resolve preserved service state' then Result := '正在读取原服务启动方式'
  else if Stage = 'stop existing services' then Result := '正在确认旧服务已停止'
  else if Stage = 'migrate legacy default port' then Result := '正在确认服务端口'
  else if Stage = 'remove previous FabMatrixServer service' then Result := '正在替换旧服务注册'
  else if Stage = 'remove legacy Farm service' then Result := '正在清理旧版服务注册'
  else if Stage = 'publish effective port metadata' then Result := '正在记录实际连接端口'
  else if Stage = 'write WinSW configuration' then Result := '正在写入服务配置'
  else if Stage = 'restore disabled start mode' then Result := '正在恢复原服务启动方式'
  else if Stage = 'protect data directories' then Result := '正在设置数据目录权限'
  else if Stage = 'register FabMatrixServer service' then Result := '正在注册 Windows 服务'
  else if Stage = 'configure private firewall rule' then Result := '正在设置专用网络防火墙'
  else if Stage = 'start FabMatrixServer service' then Result := '正在启动服务'
  else if Stage = 'wait for FabMatrixServer health' then Result := '正在等待服务就绪'
  else if Stage = 'service install complete' then Result := '服务安装完成'
  else Result := '正在配置服务';
end;

procedure OnInstallationProgress(const S: String; const Error, FirstLine: Boolean);
var
  Data: String;
  Separator: Integer;
  Caption: String;
begin
  { Only accept the fixed, non-sensitive stage protocol, not arbitrary process output. }
  if Error or (Pos('FABMATRIX_PROGRESS|', S) <> 1) then Exit;
  Data := Copy(S, Length('FABMATRIX_PROGRESS|') + 1, Length(S));
  Separator := Pos('|', Data);
  if Separator = 0 then Exit;
  Caption := InstallationStageText(Copy(Data, 1, Separator - 1)) + '（已用 ' +
    IntToStr(StrToIntDef(Copy(Data, Separator + 1, Length(Data)), 0)) + ' 秒）';
  if ProgressIsPreflight then WizardForm.PreparingLabel.Caption := Caption
  else WizardForm.StatusLabel.Caption := Caption;
  WizardForm.Refresh;
  Log(S);
end;

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

function InstallationDetails(): String;
begin
  Result := '程序安装位置：' + ExpandConstant('{app}') + #13#10 + #13#10 +
    '业务数据位置：' + GetDataRoot('') + #13#10 +
    '账号、数据库、打印文件、配置和备份保存在此目录；升级和普通卸载会保留。' + #13#10 + #13#10 +
    '新安装默认端口：28081。升级保留现有自定义端口，以安装完成页和托盘显示为准。' + #13#10 + #13#10 +
    '在独立安装的 FabMatrix 客户端中填写服务端地址。服务端本身不包含前端网页。' + #13#10 +
    '新安装连接后，按客户端提示创建首个管理员；已有数据请使用原账号登录。';
end;

procedure InitializeWizard();
begin
  ConnectionInfoPage := CreateOutputMsgPage(wpSelectDir, '服务与数据位置',
    '确认数据保存位置和客户端连接方式', '');
end;

function UpdateReadyMemo(Space, NewLine, MemoUserInfoInfo, MemoDirInfo,
  MemoTypeInfo, MemoComponentsInfo, MemoGroupInfo, MemoTasksInfo: String): String;
begin
  Result := InstallationDetails() + NewLine + NewLine + MemoTasksInfo;
end;

procedure CurPageChanged(CurPageID: Integer);
var
  ResultCode: Integer;
  SummaryFile: String;
  SummaryScript: String;
  State: String;
  StatusText: String;
begin
  if CurPageID = ConnectionInfoPage.ID then
    ConnectionInfoPage.MsgLabel.Caption := InstallationDetails();
  if CurPageID = wpFinished then
  begin
    SummaryFile := ExpandConstant('{tmp}\FabMatrix-installation-summary.ini');
    SummaryScript := ExpandConstant('{app}\service\installation-summary.ps1');
    State := 'UNKNOWN';
    if ServiceInstallSucceeded and Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
      '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + SummaryScript +
      '" -InstallDir "' + ExpandConstant('{app}') + '" -OutputPath "' + SummaryFile + '"',
      '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and (ResultCode = 0) then
      State := GetIniString('Installation', 'State', 'UNKNOWN', SummaryFile);
    if State = 'READY' then
      StatusText := '服务已运行，健康检查通过。'
    else if State = 'STOPPED' then
      StatusText := '服务已安装，当前未运行。可在托盘中选择“启动服务”。'
    else if State = 'UNAVAILABLE' then
      StatusText := '服务已安装，但健康检查未通过。请在托盘中刷新状态或查看日志。'
    else
      StatusText := '服务已安装，状态暂时无法确认。请打开托盘刷新状态。';
    WizardForm.FinishedLabel.Caption := StatusText + #13#10 + #13#10 +
      '本机连接：' + GetIniString('Installation', 'LocalAddress', '请在托盘中查看实际地址', SummaryFile) + #13#10 +
      '局域网连接：' + GetIniString('Installation', 'LanAddress', '请在托盘中查看局域网地址', SummaryFile) + #13#10 +
      '数据位置：' + GetDataRoot('') + #13#10 + #13#10 +
      '请在 FabMatrix 客户端填写以上地址。首次安装按客户端提示创建管理员，已有数据使用原账号登录。' + #13#10 +
      '局域网无法连接时，请检查网络和 Windows 专用网络防火墙规则。';
    WizardForm.FinishedLabel.AdjustHeight;
    WizardForm.RunList.Top := WizardForm.FinishedLabel.Top + WizardForm.FinishedLabel.Height + ScaleY(12);
    WizardForm.RunList.Height := ScaleY(48);
  end;
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

  ModeChoice := TaskDialogMsgBox('卸载 FabMatrix 服务端',
    '请选择如何处理业务数据。建议先备份。' + #13#10 +
    '当前数据位置：' + GetDataRoot(''),
    mbConfirmation, MB_YESNOCANCEL, ['保留数据并卸载（推荐）' + #13#10 + '移除程序和服务，保留账号、文件、任务、配置及备份。',
     '删除全部数据并卸载' + #13#10 + '下一步需要再次确认；将永久删除业务数据和旧版残留。',
     '取消'], 0);
  if (ModeChoice <> IDYES) and (ModeChoice <> IDNO) then
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
  ExtractTemporaryFile('installation-progress.ps1');
  PowerShell := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
  CaptureScript := ExpandConstant('{tmp}\capture-upgrade-state.ps1');
  StateFile := ExpandConstant('{tmp}\FabMatrixServer-upgrade-state.json');
  ProgressIsPreflight := True;
  WizardForm.PreparingLabel.Caption := '正在关闭旧程序并保留服务状态...';
  Result := ExecAndLogOutput(PowerShell, '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + CaptureScript +
    '" -StateFile "' + StateFile + '" -InstallDir "' + ExpandConstant('{app}') +
    '" -LogPath "' + ExpandConstant('{commonappdata}\FabMatrix\installer\upgrade-preflight.log') + '"',
    '', SW_HIDE, ewWaitUntilTerminated, ResultCode, @OnInstallationProgress) and (ResultCode = 0);
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
  ProgressIsPreflight := False;
  if (not ExecAndLogOutput(PowerShell, Parameters, '', SW_HIDE, ewWaitUntilTerminated, ResultCode, @OnInstallationProgress)) or
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
