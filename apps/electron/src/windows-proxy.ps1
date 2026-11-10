$ErrorActionPreference = 'Stop'
$settingsPath = 'Software\Microsoft\Windows\CurrentVersion\Internet Settings'
$backupPath = 'Software\MoeclubM\XBClient\SystemProxy'
$names = @('ProxyEnable', 'ProxyServer', 'ProxyOverride', 'AutoConfigURL', 'AutoDetect')
$connections = @('DefaultConnectionSettings', 'SavedLegacySettings')
$userRegistry = [Microsoft.Win32.Registry]::CurrentUser
$saved = $userRegistry.OpenSubKey($backupPath, $true)

if ($Action -eq 'clear' -and $null -eq $saved) { exit 0 }
$settings = $userRegistry.CreateSubKey($settingsPath)
$connectionSettings = $userRegistry.CreateSubKey("$settingsPath\Connections")

if ($Action -eq 'set' -and $null -eq $saved) {
    $saved = $userRegistry.CreateSubKey($backupPath)
    foreach ($name in $names) {
        if ($settings.GetValueNames() -contains $name) {
            $saved.SetValue($name, $settings.GetValue($name, $null, 'DoNotExpandEnvironmentNames'), $settings.GetValueKind($name))
        }
    }
    foreach ($name in $connections) {
        if ($connectionSettings.GetValueNames() -contains $name) {
            $saved.SetValue("Connection.$name", $connectionSettings.GetValue($name), $connectionSettings.GetValueKind($name))
        }
    }
}

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class XBClientInternet {
    [StructLayout(LayoutKind.Sequential)] public struct Option { public int Id; public IntPtr Value; }
    [StructLayout(LayoutKind.Sequential)] public struct Options {
        public int Size; public IntPtr Connection; public int Count; public int Error; public IntPtr Values;
    }
    [DllImport("wininet.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern bool InternetSetOptionW(IntPtr handle, int option, IntPtr buffer, int length);
    public static void SetProxy(string server) {
        var text = Marshal.StringToHGlobalUni(server);
        var bypass = Marshal.StringToHGlobalUni("<local>;localhost;127.*;[::1]");
        var size = Marshal.SizeOf(typeof(Option));
        var values = Marshal.AllocHGlobal(size * 3);
        var list = new Options { Size = Marshal.SizeOf(typeof(Options)), Count = 3, Values = values };
        var pointer = Marshal.AllocHGlobal(list.Size);
        try {
            Marshal.StructureToPtr(new Option { Id = 1, Value = (IntPtr)3 }, values, false);
            Marshal.StructureToPtr(new Option { Id = 2, Value = text }, IntPtr.Add(values, size), false);
            Marshal.StructureToPtr(new Option { Id = 3, Value = bypass }, IntPtr.Add(values, size * 2), false);
            Marshal.StructureToPtr(list, pointer, false);
            if (!InternetSetOptionW(IntPtr.Zero, 75, pointer, list.Size))
                throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        } finally {
            Marshal.FreeHGlobal(pointer); Marshal.FreeHGlobal(values);
            Marshal.FreeHGlobal(text); Marshal.FreeHGlobal(bypass);
        }
    }
    public static void Refresh() {
        if (!InternetSetOptionW(IntPtr.Zero, 39, IntPtr.Zero, 0) || !InternetSetOptionW(IntPtr.Zero, 37, IntPtr.Zero, 0))
            throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
    }
}
'@

try {
    if ($Action -eq 'set') {
        [XBClientInternet]::SetProxy("http=127.0.0.1:$Port;https=127.0.0.1:$Port")
    } else {
        foreach ($name in $names) {
            if ($saved.GetValueNames() -contains $name) {
                $settings.SetValue($name, $saved.GetValue($name, $null, 'DoNotExpandEnvironmentNames'), $saved.GetValueKind($name))
            } else { $settings.DeleteValue($name, $false) }
        }
        foreach ($name in $connections) {
            if ($saved.GetValueNames() -contains "Connection.$name") {
                $connectionSettings.SetValue($name, $saved.GetValue("Connection.$name"), $saved.GetValueKind("Connection.$name"))
            } else { $connectionSettings.DeleteValue($name, $false) }
        }
    }
    [XBClientInternet]::Refresh()
    if ($Action -eq 'clear') {
        $saved.Close()
        $saved = $null
        $userRegistry.DeleteSubKeyTree($backupPath)
    }
} finally {
    if ($null -ne $saved) { $saved.Close() }
    $connectionSettings.Close()
    $settings.Close()
}
