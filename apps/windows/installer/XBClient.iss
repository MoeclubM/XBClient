#ifndef MyAppName
  #define MyAppName "XBClient"
#endif

#ifndef MyAppVersion
  #define MyAppVersion "0.0.15"
#endif

#ifndef PublishDir
  #define PublishDir "..\..\..\dist\XBClient"
#endif

#ifndef DistDir
  #define DistDir "..\..\..\dist"
#endif

#ifndef OutputBaseFilename
  #define OutputBaseFilename "XBClient-win-x64-setup"
#endif

[Setup]
AppId={{C47E91A5-3B82-46F0-8A19-5E2D6F4B8C30}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher=MoeclubM
AppPublisherURL=https://github.com/MoeclubM/XBClient
AppSupportURL=https://github.com/MoeclubM/XBClient/releases
AppUpdatesURL=https://github.com/MoeclubM/XBClient/releases
DefaultDirName={autopf}\XBClient
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputDir={#DistDir}
OutputBaseFilename={#OutputBaseFilename}
SetupIconFile=..\Assets\app.ico
UninstallDisplayIcon={app}\XBClient.exe
UninstallDisplayName={#MyAppName}
Compression=lzma2/fast
SolidCompression=yes
WizardStyle=modern
CloseApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#PublishDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\XBClient.exe"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\XBClient.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueName: "XBClient"; Flags: uninsdeletevalue

[Run]
Filename: "{app}\XBClient.exe"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

[Code]
function SanitizeServiceAppId(const AppId: String): String;
var
  I: Integer;
  C: Char;
  Clean: String;
begin
  Clean := '';
  for I := 1 to Length(AppId) do
  begin
    C := AppId[I];
    if ((C >= '0') and (C <= '9')) or ((C >= 'A') and (C <= 'Z')) or ((C >= 'a') and (C <= 'z')) or (C = '-') or (C = '_') then
      Clean := Clean + C;
  end;
  if Clean = '' then
    Result := 'XBClient'
  else
    Result := Clean;
end;

function HasInstalledTunService(): Boolean;
var
  SvcKey: String;
begin
  SvcKey := 'SYSTEM\CurrentControlSet\Services\' + SanitizeServiceAppId('{#MyAppName}') + 'TunService';
  Result := RegKeyExists(HKLM, SvcKey) or RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\XBClientTunService');
end;

procedure RunServiceCommand(const Args: String);
var
  SvcExe: String;
  ResultCode: Integer;
begin
  SvcExe := ExpandConstant('{app}\neto-service.exe');
  if not FileExists(SvcExe) then Exit;
  if IsAdminInstallMode() then
    Exec(SvcExe, Args, '', SW_HIDE, ewWaitUntilTerminated, ResultCode)
  else
    ShellExec('runas', SvcExe, Args, '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

procedure StopRunningClient();
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM XBClient.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  if HasInstalledTunService() then
    RunServiceCommand('stop --id "{#MyAppName}"');
end;

function InitializeSetup(): Boolean;
begin
  StopRunningClient();
  Result := True;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssPostInstall) and HasInstalledTunService() then
    RunServiceCommand('install --id "{#MyAppName}"');
end;

function InitializeUninstall(): Boolean;
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM XBClient.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := True;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
  begin
    if HasInstalledTunService() then
      RunServiceCommand('uninstall --id "{#MyAppName}"');
    if RegKeyExists(HKCU, 'Software\{#MyAppName}\SystemProxy') or RegKeyExists(HKCU, 'Software\XBClient\SystemProxy') then
    begin
      RegWriteDWordValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Internet Settings', 'ProxyEnable', 0);
      RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Internet Settings', 'ProxyServer');
      RegDeleteKeyIncludingSubkeys(HKCU, 'Software\{#MyAppName}');
      RegDeleteKeyIncludingSubkeys(HKCU, 'Software\XBClient');
    end;
  end;
end;
