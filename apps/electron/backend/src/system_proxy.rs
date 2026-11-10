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
    gsettings_set("org.gnome.system.proxy.socks", "port", &port.to_string())?;
    Ok(())
}

#[cfg(target_os = "linux")]
pub fn clear() -> Result<()> {
    gsettings_set("org.gnome.system.proxy", "mode", "none")?;
    Ok(())
}

#[cfg(not(target_os = "linux"))]
pub fn set_socks(_host: &str, _port: u16) -> anyhow::Result<()> {
    anyhow::bail!("system proxy is managed by the Electron main process on this platform")
}

#[cfg(not(target_os = "linux"))]
pub fn clear() -> anyhow::Result<()> {
    anyhow::bail!("system proxy is managed by the Electron main process on this platform")
}
