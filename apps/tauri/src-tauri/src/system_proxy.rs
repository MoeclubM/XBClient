#[cfg(windows)]
use anyhow::Context;
use anyhow::Result;

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

#[cfg(not(windows))]
pub fn set_socks(_host: &str, _port: u16) -> Result<()> {
    anyhow::bail!("system proxy not implemented on this platform")
}

#[cfg(not(windows))]
pub fn clear() -> Result<()> {
    anyhow::bail!("system proxy not implemented on this platform")
}
