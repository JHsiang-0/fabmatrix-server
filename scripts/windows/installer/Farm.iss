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
AppName=Farm 3D Printer Management
AppVersion={#AppVersion}
AppVerName=Farm 3D Printer Management {#AppVersion}
AppPublisher=Farm
AppPublisherURL=https://github.com/JHsiang-0/3d-printer-farm
DefaultDirName={autopf}\Farm
DefaultGroupName=Farm
OutputDir={#OutputDir}
OutputBaseFilename=Farm-Setup
UninstallDisplayName=Farm 3D Printer Management
UninstallDisplayIcon={app}\Farm.exe
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
DisableProgramGroupPage=yes
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
SetupLogging=yes
VersionInfoDescription=Farm 3D Printer Management installer
VersionInfoProductName=Farm
VersionInfoProductVersion={#AppVersion}
ChangesEnvironment=no

[Tasks]
Name: "autostart"; Description: "安装后自动启动 Farm 服务，并设置为 Windows 启动时自动运行"; Flags: checkedonce

[Files]
Source: "{#PayloadDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Dirs]
Name: "{commonappdata}\Farm"; Flags: uninsneveruninstall
Name: "{commonappdata}\Farm\config"; Flags: uninsneveruninstall
Name: "{commonappdata}\Farm\data"; Flags: uninsneveruninstall
Name: "{commonappdata}\Farm\logs"; Flags: uninsneveruninstall
Name: "{commonappdata}\Farm\uploads"; Flags: uninsneveruninstall

[Run]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\service\install-service.ps1"" -InstallDir ""{app}"" -DataRoot ""{commonappdata}\Farm"" -AutoStart"; Tasks: autostart; Flags: runhidden waituntilterminated; StatusMsg: "正在注册并启动 Farm Windows 服务..."
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\service\install-service.ps1"" -InstallDir ""{app}"" -DataRoot ""{commonappdata}\Farm"""; Tasks: not autostart; Flags: runhidden waituntilterminated; StatusMsg: "正在注册 Farm Windows 服务..."

[UninstallRun]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\service\uninstall-service.ps1"" -InstallDir ""{app}"" -DataRoot ""{commonappdata}\Farm"""; RunOnceId: "FarmService"; Flags: runhidden waituntilterminated

[UninstallDelete]
Type: filesandordirs; Name: "{app}\service"
Type: filesandordirs; Name: "{app}\logs"
