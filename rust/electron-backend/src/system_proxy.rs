#[cfg(windows)]
use anyhow::{Context, Result};

#[cfg(windows)]
pub fn set_socks(host: &str, port: u16) -> Result<()> {
    use std::ptr;
    use windows_sys::Win32::Networking::WinInet::{
        INTERNET_OPTION_REFRESH, INTERNET_OPTION_SETTINGS_CHANGED, InternetSetOptionW,
    };
    use winreg::RegKey;
    use winreg::enums::{HKEY_CURRENT_USER, KEY_SET_VALUE};

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let path = r"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
    let key = hkcu
        .open_subkey_with_flags(path, KEY_SET_VALUE)
        .context("open Internet Settings registry key")?;

    let server = format!("socks={host}:{port}");
    key.set_value("ProxyServer", &server)
        .context("write ProxyServer")?;
    key.set_value("ProxyEnable", &1u32)
        .context("write ProxyEnable")?;
    key.set_value("ProxyOverride", &"<local>".to_string())
        .context("write ProxyOverride")?;

    unsafe {
        InternetSetOptionW(
            ptr::null_mut(),
            INTERNET_OPTION_SETTINGS_CHANGED,
            ptr::null(),
            0,
        );
        InternetSetOptionW(ptr::null_mut(), INTERNET_OPTION_REFRESH, ptr::null(), 0);
    }

    Ok(())
}

#[cfg(windows)]
pub fn clear() -> Result<()> {
    use std::ptr;
    use windows_sys::Win32::Networking::WinInet::{
        INTERNET_OPTION_REFRESH, INTERNET_OPTION_SETTINGS_CHANGED, InternetSetOptionW,
    };
    use winreg::RegKey;
    use winreg::enums::{HKEY_CURRENT_USER, KEY_SET_VALUE};

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let path = r"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
    let key = hkcu
        .open_subkey_with_flags(path, KEY_SET_VALUE)
        .context("open Internet Settings registry key")?;

    key.set_value("ProxyEnable", &0u32)
        .context("write ProxyEnable")?;

    unsafe {
        InternetSetOptionW(
            ptr::null_mut(),
            INTERNET_OPTION_SETTINGS_CHANGED,
            ptr::null(),
            0,
        );
        InternetSetOptionW(ptr::null_mut(), INTERNET_OPTION_REFRESH, ptr::null(), 0);
    }

    Ok(())
}

#[cfg(target_os = "linux")]
use anyhow::{Context, Result, bail};

#[cfg(target_os = "linux")]
fn gsettings_set(schema: &str, key: &str, value: &str) -> Result<()> {
    let status = std::process::Command::new("gsettings")
        .args(["set", schema, key, value])
        .status()
        .with_context(|| format!("gsettings set {schema} {key}"))?;
    if !status.success() {
        bail!("gsettings set {schema} {key} failed (exit {status})");
    }
    Ok(())
}

#[cfg(target_os = "linux")]
pub fn set_socks(host: &str, port: u16) -> Result<()> {
    gsettings_set("org.gnome.system.proxy", "mode", "manual")?;
    gsettings_set("org.gnome.system.proxy.socks", "host", host)?;
    gsettings_set(
        "org.gnome.system.proxy.socks",
        "port",
        &port.to_string(),
    )?;
    Ok(())
}

#[cfg(target_os = "linux")]
pub fn clear() -> Result<()> {
    gsettings_set("org.gnome.system.proxy", "mode", "none")?;
    Ok(())
}

#[cfg(not(any(windows, target_os = "linux")))]
pub fn set_socks(_host: &str, _port: u16) -> anyhow::Result<()> {
    anyhow::bail!("system proxy not supported on this platform")
}

#[cfg(not(any(windows, target_os = "linux")))]
pub fn clear() -> anyhow::Result<()> {
    anyhow::bail!("system proxy not supported on this platform")
}
