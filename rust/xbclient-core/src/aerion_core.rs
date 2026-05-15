use crate::aerion_config_compat::node_to_proxy_config;
use crate::aerion_protocol::spawn_aerion_listener;
use anyhow::{Context, Result, bail, ensure};
use serde::Deserialize;
use serde_json::{Value, json};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
#[cfg(target_os = "android")]
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;
use tokio::time::{Duration, timeout};

#[derive(Deserialize)]
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
struct StartVpnRequest {
    node: Value,
    tun_fd: i32,
    mtu: Option<u16>,
    dns: Option<String>,
    dns_addr: String,
    ipv6: Option<bool>,
}

#[derive(Deserialize)]
struct TestNodeRequest {
    node: Value,
    target_host: Option<String>,
    target_port: Option<u16>,
    target_tls: Option<bool>,
    timeout_ms: Option<u64>,
}

async fn start_aerion_socks(node: Value) -> Result<(SocketAddr, JoinHandle<()>)> {
    let listen = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0);
    let listener = TcpListener::bind(listen)
        .await
        .context("bind Aerion local SOCKS listener")?;
    let local_addr = listener.local_addr().context("read Aerion SOCKS address")?;
    let config = node_to_proxy_config(&node, local_addr)?;
    let task = spawn_aerion_listener(listener, config);
    Ok((local_addr, task))
}

pub async fn test_node_from_json(input: &str) -> Result<String> {
    let request: TestNodeRequest =
        serde_json::from_str(input).context("parse node test request")?;
    let target_host = request
        .target_host
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "cp.cloudflare.com".to_string());
    let target_port = request.target_port.unwrap_or(80);
    let target_tls = request.target_tls.unwrap_or(target_port == 443);
    let timeout_duration = Duration::from_millis(request.timeout_ms.unwrap_or(8000));
    let (socks_addr, task) = start_aerion_socks(request.node).await?;
    let result = timeout(timeout_duration, async {
        let first_latency =
            probe_via_socks(socks_addr, &target_host, target_port, target_tls).await?;
        let second_latency =
            probe_via_socks(socks_addr, &target_host, target_port, target_tls).await?;
        Ok::<_, anyhow::Error>((first_latency, second_latency))
    })
    .await;
    task.abort();
    let (first_latency, second_latency) = result.context("Aerion node test timed out")??;
    Ok(json!({
        "ok": true,
        "latency_ms": second_latency,
        "first_latency_ms": first_latency,
        "target_host": target_host,
        "target_port": target_port,
        "target_tls": target_tls,
    })
    .to_string())
}

async fn probe_via_socks(
    socks_addr: SocketAddr,
    target_host: &str,
    target_port: u16,
    target_tls: bool,
) -> Result<u64> {
    let mut stream = TcpStream::connect(socks_addr)
        .await
        .with_context(|| format!("connect local Aerion SOCKS listener {socks_addr}"))?;
    socks_connect(&mut stream, target_host, target_port).await?;
    let host_header = if (target_tls && target_port == 443) || (!target_tls && target_port == 80) {
        target_host.to_string()
    } else {
        format!("{target_host}:{target_port}")
    };
    if target_tls {
        let mut roots = rustls::RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let config = rustls::ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth();
        let server_name = rustls::pki_types::ServerName::try_from(target_host.to_string())
            .with_context(|| format!("invalid TLS test target: {target_host}"))?;
        let mut tls = tokio_rustls::TlsConnector::from(std::sync::Arc::new(config))
            .connect(server_name, stream)
            .await
            .context("connect TLS test target through Aerion")?;
        return send_http_probe(&mut tls, &host_header).await;
    }
    send_http_probe(&mut stream, &host_header).await
}

async fn socks_connect(stream: &mut TcpStream, target_host: &str, target_port: u16) -> Result<()> {
    ensure!(
        target_host.len() <= u8::MAX as usize,
        "SOCKS test target host is too long"
    );
    stream
        .write_all(&[0x05, 0x01, 0x00])
        .await
        .context("write SOCKS greeting")?;
    let mut greeting = [0u8; 2];
    stream
        .read_exact(&mut greeting)
        .await
        .context("read SOCKS greeting response")?;
    ensure!(
        greeting == [0x05, 0x00],
        "SOCKS greeting rejected: {:02x?}",
        greeting
    );
    let mut request = Vec::with_capacity(7 + target_host.len());
    request.extend_from_slice(&[0x05, 0x01, 0x00, 0x03, target_host.len() as u8]);
    request.extend_from_slice(target_host.as_bytes());
    request.extend_from_slice(&target_port.to_be_bytes());
    stream
        .write_all(&request)
        .await
        .context("write SOCKS connect request")?;
    let mut header = [0u8; 4];
    stream
        .read_exact(&mut header)
        .await
        .context("read SOCKS connect response")?;
    if header[0] != 0x05 || header[1] != 0x00 {
        bail!(
            "SOCKS connect failed: {}: {:02x?}",
            socks_reply_name(header[1]),
            header
        );
    }
    match header[3] {
        0x01 => {
            let mut skip = [0u8; 6];
            stream.read_exact(&mut skip).await?;
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).await?;
            let mut skip = vec![0u8; len[0] as usize + 2];
            stream.read_exact(&mut skip).await?;
        }
        0x04 => {
            let mut skip = [0u8; 18];
            stream.read_exact(&mut skip).await?;
        }
        atyp => bail!("unsupported SOCKS bind address type: {atyp}"),
    }
    Ok(())
}

fn socks_reply_name(code: u8) -> &'static str {
    match code {
        0x01 => "general failure",
        0x02 => "connection not allowed",
        0x03 => "network unreachable",
        0x04 => "host unreachable",
        0x05 => "connection refused by target",
        0x06 => "TTL expired",
        0x07 => "command not supported",
        0x08 => "address type not supported",
        _ => "unknown SOCKS reply",
    }
}

async fn send_http_probe<S>(stream: &mut S, host_header: &str) -> Result<u64>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let request = format!(
        "HEAD / HTTP/1.1\r\nHost: {host_header}\r\nUser-Agent: XBClient\r\nConnection: close\r\n\r\n"
    );
    let started = Instant::now();
    stream
        .write_all(request.as_bytes())
        .await
        .context("write HTTP probe request")?;
    let mut response = Vec::new();
    let mut buffer = [0u8; 1024];
    loop {
        let read = stream
            .read(&mut buffer)
            .await
            .context("read HTTP probe response")?;
        ensure!(read > 0, "target closed before HTTP response");
        response.extend_from_slice(&buffer[..read]);
        let prefix_len = response.len().min(5);
        ensure!(
            response[..prefix_len] == b"HTTP/"[..prefix_len],
            "target response is not HTTP"
        );
        if response.windows(4).any(|window| window == b"\r\n\r\n") {
            return Ok(started.elapsed().as_millis() as u64);
        }
        ensure!(
            response.len() < 4096,
            "target HTTP response header is too large"
        );
    }
}

#[cfg(target_os = "android")]
mod platform {
    use super::*;
    use once_cell::sync::Lazy;
    use std::collections::HashMap;
    use std::sync::Mutex as StdMutex;
    use tun2proxy::{ArgDns, ArgProxy, ArgVerbosity, Args, CancellationToken};

    static NEXT_VPN_SESSION_ID: AtomicU64 = AtomicU64::new(1);
    static VPN_SESSIONS: Lazy<StdMutex<HashMap<u64, VpnSession>>> =
        Lazy::new(|| StdMutex::new(HashMap::new()));

    struct VpnSession {
        shutdown: CancellationToken,
        proxy_task: JoinHandle<()>,
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
        let (socks_addr, proxy_task) = start_aerion_socks(request.node).await?;
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
                    shutdown: shutdown.clone(),
                    proxy_task,
                },
            );
        tokio::spawn(async move {
            let result = tun2proxy::general_run_async(args, mtu, false, shutdown).await;
            if let Err(error) = result {
                log::error!("Aerion tun2proxy exited with error: {error:?}");
            }
            if let Some(session) = VPN_SESSIONS
                .lock()
                .expect("VPN session map lock poisoned")
                .remove(&session_id)
            {
                session.proxy_task.abort();
            }
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
        session.proxy_task.abort();
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
