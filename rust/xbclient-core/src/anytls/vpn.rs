use super::config::AnyTlsConfig;
use anyhow::{Context, Result, bail};
use serde::Deserialize;

#[derive(Deserialize)]
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
struct StartVpnRequest {
    node: AnyTlsConfig,
    tun_fd: i32,
    mtu: Option<u16>,
    dns: Option<String>,
    dns_addr: String,
    ipv6: Option<bool>,
}

#[cfg(target_os = "android")]
mod platform {
    use super::*;
    use crate::anytls::socks;
    use once_cell::sync::Lazy;
    use serde_json::json;
    use std::collections::HashMap;
    use std::net::IpAddr;
    use std::sync::Mutex as StdMutex;
    use std::sync::atomic::{AtomicU64, Ordering};
    use tun2proxy::{ArgDns, ArgProxy, ArgVerbosity, Args, CancellationToken};

    static NEXT_VPN_SESSION_ID: AtomicU64 = AtomicU64::new(1);
    static VPN_SESSIONS: Lazy<StdMutex<HashMap<u64, VpnSession>>> =
        Lazy::new(|| StdMutex::new(HashMap::new()));

    struct VpnSession {
        socks_session_id: u64,
        shutdown: CancellationToken,
    }

    pub async fn start(input: &str) -> Result<String> {
        let request: StartVpnRequest =
            serde_json::from_str(input).context("parse start VPN request")?;
        let mtu = request.mtu.unwrap_or(1500);
        let dns = match request.dns.as_deref().unwrap_or("over_tcp") {
            "virtual" => ArgDns::Virtual,
            "direct" => ArgDns::Direct,
            "over_tcp" => ArgDns::OverTcp,
            other => bail!("unsupported VPN DNS strategy: {other}"),
        };
        let dns_addr: IpAddr = request.dns_addr.parse().context("parse VPN DNS address")?;
        let (socks_session_id, socks_addr) =
            socks::start_socks(request.node, "127.0.0.1:0".to_string()).await?;
        let proxy_url = format!("socks5://{socks_addr}");
        let proxy = ArgProxy::try_from(proxy_url.as_str())
            .map_err(|error| anyhow::anyhow!("parse tun2proxy proxy URL: {error}"))?;
        let shutdown = CancellationToken::new();
        let mut args = Args::default();
        args.proxy(proxy)
            .tun_fd(Some(request.tun_fd))
            .close_fd_on_drop(false)
            .dns(dns)
            .dns_addr(dns_addr)
            .ipv6_enabled(request.ipv6.unwrap_or(false))
            .verbosity(ArgVerbosity::Info);

        let session_id = NEXT_VPN_SESSION_ID.fetch_add(1, Ordering::SeqCst);
        VPN_SESSIONS
            .lock()
            .expect("VPN session map lock poisoned")
            .insert(
                session_id,
                VpnSession {
                    socks_session_id,
                    shutdown: shutdown.clone(),
                },
            );
        tokio::spawn(async move {
            let result = tun2proxy::general_run_async(args, mtu, false, shutdown).await;
            if let Err(error) = result {
                log::error!("tun2proxy exited with error: {error:?}");
            }
            let _ = socks::stop_socks(socks_session_id).await;
            VPN_SESSIONS
                .lock()
                .expect("VPN session map lock poisoned")
                .remove(&session_id);
        });

        Ok(json!({
            "ok": true,
            "session_id": session_id,
            "mtu": mtu,
        })
        .to_string())
    }

    pub async fn stop(session_id: u64) -> Result<String> {
        let session = VPN_SESSIONS
            .lock()
            .expect("VPN session map lock poisoned")
            .remove(&session_id)
            .with_context(|| format!("VPN session not found: {session_id}"))?;
        session.shutdown.cancel();
        let _ = socks::stop_socks(session.socks_session_id).await;
        Ok(json!({"ok": true, "session_id": session_id}).to_string())
    }
}

#[cfg(not(target_os = "android"))]
mod platform {
    use super::*;

    pub async fn start(input: &str) -> Result<String> {
        let _request: StartVpnRequest =
            serde_json::from_str(input).context("parse start VPN request")?;
        bail!("Android VPN is only available on Android target builds")
    }

    pub async fn stop(session_id: u64) -> Result<String> {
        let _ = session_id;
        bail!("Android VPN is only available on Android target builds")
    }
}

pub async fn start_vpn_from_json(input: &str) -> Result<String> {
    platform::start(input).await
}

pub async fn stop_vpn(session_id: u64) -> Result<String> {
    platform::stop(session_id).await
}
