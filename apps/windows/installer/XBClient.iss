#ifndef MyAppName
  #define MyAppName "XBClient"
#endif

#ifndef MyAppVersion
  #define MyAppVersion "0.1.0"
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
procedure StopRunningClient();
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM XBClient.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

function InitializeSetup(): Boolean;
begin
  StopRunningClient();
  Result := True;
end;

function InitializeUninstall(): Boolean;
begin
  StopRunningClient();
  Result := True;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
  begin
    if RegKeyExists(HKCU, 'Software\XBClient\SystemProxy') then
    begin
      RegWriteDWordValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Internet Settings', 'ProxyEnable', 0);
      RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Internet Settings', 'ProxyServer');
      RegDeleteKeyIncludingSubkeys(HKCU, 'Software\XBClient');
    end;
  end;
end;
