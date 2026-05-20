use crate::aerion_config_compat::node_to_proxy_config;
use crate::aerion_protocol::spawn_aerion_listener;
use anyhow::{Context, Result, bail, ensure};
use once_cell::sync::Lazy;
use serde::Deserialize;
use serde_json::{Value, json};
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Mutex as StdMutex;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;
use tokio::time::{Duration, timeout};

type Callback = Box<dyn Fn(String, String) + Send + Sync>;
static LOG_CALLBACK: Lazy<StdMutex<Option<Callback>>> = Lazy::new(|| StdMutex::new(None));
static EVENT_CALLBACK: Lazy<StdMutex<Option<Callback>>> = Lazy::new(|| StdMutex::new(None));

pub fn set_log_callback<F>(f: F)
where
    F: Fn(String, String) + Send + Sync + 'static,
{
    *LOG_CALLBACK.lock().unwrap() = Some(Box::new(f));
}

pub fn set_event_callback<F>(f: F)
where
    F: Fn(String, String) + Send + Sync + 'static,
{
    *EVENT_CALLBACK.lock().unwrap() = Some(Box::new(f));
}

pub(crate) fn on_log(level: &str, message: &str) {
    #[cfg(target_os = "android")]
    {
        let _ = crate::android::on_log(level, message);
    }

    if let Some(cb) = LOG_CALLBACK.lock().unwrap().as_ref() {
        cb(level.to_string(), message.to_string());
    }
}

pub(crate) fn on_event(event_json: &str) {
    #[cfg(target_os = "android")]
    {
        let _ = crate::android::on_event(event_json);
    }

    if let Some(cb) = EVENT_CALLBACK.lock().unwrap().as_ref() {
        cb("event".to_string(), event_json.to_string());
    }
}

fn core_event_json(event: &aerion::CoreEvent, wrapper_session_id: Option<u64>) -> String {
    let mut value = match event {
        aerion::CoreEvent::UsersReplaced { user_ids } => json!({
            "type": "users_replaced",
            "user_ids": user_ids,
        }),
        aerion::CoreEvent::SessionOpened {
            user_id,
            session_id,
            source_ip,
        } => json!({
            "type": "session_opened",
            "user_id": user_id,
            "session_id": session_id,
            "source_ip": source_ip,
        }),
        aerion::CoreEvent::SessionClosed {
            user_id,
            session_id,
            source_ip,
        } => json!({
            "type": "session_closed",
            "user_id": user_id,
            "session_id": session_id,
            "source_ip": source_ip,
        }),
        aerion::CoreEvent::SessionCancelled {
            user_id,
            session_id,
            source_ip,
        } => json!({
            "type": "session_cancelled",
            "user_id": user_id,
            "session_id": session_id,
            "source_ip": source_ip,
        }),
        aerion::CoreEvent::TrafficRecorded {
            user_id,
            session_id,
            direction,
            bytes,
            upload_bytes,
            download_bytes,
        } => json!({
            "type": "traffic_recorded",
            "user_id": user_id,
            "session_id": session_id,
            "direction": traffic_direction_name(*direction),
            "bytes": bytes,
            "upload_bytes": upload_bytes,
            "download_bytes": download_bytes,
        }),
    };
    if let (Some(id), Value::Object(object)) = (wrapper_session_id, &mut value) {
        object.insert("wrapper_session_id".to_string(), json!(id));
    }
    value.to_string()
}

fn traffic_direction_name(direction: aerion::TrafficDirection) -> &'static str {
    match direction {
        aerion::TrafficDirection::Upload => "upload",
        aerion::TrafficDirection::Download => "download",
    }
}

#[derive(Deserialize)]
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
struct StartVpnRequest {
    node: Value,
    tun_fd: i32,
    mtu: Option<u16>,
    dns: Option<String>,
    dns_addr: String,
    virtual_dns_pool: Option<String>,
    bypass: Option<Vec<String>>,
    ipv6: Option<bool>,
    tcp_timeout_secs: Option<u64>,
    udp_timeout_secs: Option<u64>,
    max_sessions: Option<usize>,
    exit_on_fatal_error: Option<bool>,
}

#[derive(Deserialize)]
struct TestNodeRequest {
    node: Value,
    target_host: Option<String>,
    target_port: Option<u16>,
    target_tls: Option<bool>,
    timeout_ms: Option<u64>,
}

#[derive(Deserialize)]
struct StartSocksRequest {
    node: Value,
}

struct SocksSession {
    _task: JoinHandle<()>,
    _log_task: Option<JoinHandle<()>>,
    _event_task: Option<JoinHandle<()>>,
    _core: Option<aerion::ProxyCore>,
    _stop_token: Option<aerion::ListenerStopToken>,
}

static NEXT_SOCKS_SESSION_ID: AtomicU64 = AtomicU64::new(1);
static SOCKS_SESSIONS: Lazy<StdMutex<HashMap<u64, SocksSession>>> =
    Lazy::new(|| StdMutex::new(HashMap::new()));

async fn start_aerion_socks(
    node: Value,
    core: Option<aerion::ProxyCore>,
) -> Result<(SocketAddr, JoinHandle<()>)> {
    let listen = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0);
    let listener = TcpListener::bind(listen)
        .await
        .context("bind Aerion local SOCKS listener")?;
    let local_addr = listener.local_addr().context("read Aerion SOCKS address")?;
    let config = node_to_proxy_config(&node, local_addr)?;
    let task = spawn_aerion_listener(listener, config, core);
    Ok((local_addr, task))
}

pub async fn start_socks_from_json(input: &str) -> Result<String> {
    let request: StartSocksRequest =
        serde_json::from_str(input).context("parse start SOCKS request")?;

    let core = aerion::ProxyCore::empty();
    let log_bridge = aerion::LogBridge::new();
    let stop_token = aerion::ListenerStopToken::new();
    let session_id = NEXT_SOCKS_SESSION_ID.fetch_add(1, Ordering::SeqCst);

    let (socks_addr, task) = start_aerion_socks(request.node, Some(core.clone())).await?;

    let log_task = {
        let mut rx = log_bridge.subscribe();
        tokio::spawn(async move {
            while let Some(entry) = rx.recv().await {
                on_log(&entry.level.to_string(), &entry.message);
                #[cfg(not(target_os = "android"))]
                log::info!("[Aerion] [{}] {}", entry.level, entry.message);
            }
        })
    };

    let event_task = {
        let mut rx = core.subscribe_events();
        tokio::spawn(async move {
            while let Some(event) = rx.recv().await {
                let json = core_event_json(&event, Some(session_id));
                on_event(&json);
                #[cfg(not(target_os = "android"))]
                log::debug!("[Aerion Event] {}", json);
            }
        })
    };

    SOCKS_SESSIONS
        .lock()
        .expect("SOCKS session map lock poisoned")
        .insert(
            session_id,
            SocksSession {
                _task: task,
                _log_task: Some(log_task),
                _event_task: Some(event_task),
                _core: Some(core),
                _stop_token: Some(stop_token),
            },
        );

    Ok(json!({
        "ok": true,
        "session_id": session_id,
        "socks_addr": socks_addr.to_string(),
    })
    .to_string())
}

pub async fn stop_socks(session_id: u64) -> Result<String> {
    let session = SOCKS_SESSIONS
        .lock()
        .expect("SOCKS session map lock poisoned")
        .remove(&session_id)
        .with_context(|| format!("SOCKS session not found: {session_id}"))?;
    session._task.abort();
    if let Some(token) = session._stop_token {
        token.stop();
    }
    if let Some(task) = session._log_task {
        task.abort();
    }
    if let Some(task) = session._event_task {
        task.abort();
    }
    Ok(json!({"ok": true, "session_id": session_id}).to_string())
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
    let (socks_addr, task) = start_aerion_socks(request.node, None).await?;
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
            "SOCKS connect failed: {} (reply 0x{:02x})",
            socks_reply_name(header[1]),
            header[1]
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
    use aerion::{
        TunCancellationToken, TunConfig, TunDnsStrategy, TunVerbosity, socks_proxy_url, spawn_tun,
    };

    static NEXT_VPN_SESSION_ID: AtomicU64 = AtomicU64::new(1);
    static VPN_SESSIONS: Lazy<StdMutex<HashMap<u64, VpnSession>>> =
        Lazy::new(|| StdMutex::new(HashMap::new()));

    struct VpnSession {
        shutdown: TunCancellationToken,
        proxy_task: JoinHandle<()>,
        _core: aerion::ProxyCore,
        _stop_token: aerion::StopToken,
    }

    pub async fn start(input: &str) -> Result<String> {
        let request: StartVpnRequest =
            serde_json::from_str(input).context("parse start VPN request")?;
        let mtu = request.mtu.unwrap_or(1500);
        let dns = match request.dns.as_deref().unwrap_or("over_tcp") {
            "virtual" => TunDnsStrategy::Virtual,
            "direct" => TunDnsStrategy::Direct,
            "over_tcp" => TunDnsStrategy::OverTcp,
            other => bail!("unsupported VPN DNS strategy: {other}"),
        };
        let dns_addr: IpAddr = request.dns_addr.parse().context("parse VPN DNS address")?;

        let core = aerion::ProxyCore::empty();
        let (socks_addr, proxy_task) = start_aerion_socks(request.node, Some(core.clone())).await?;

        let mut tun_config = TunConfig::new(socks_proxy_url(socks_addr));
        tun_config.tun_fd = Some(request.tun_fd);
        tun_config.close_fd_on_drop = false;
        tun_config.setup = false;
        tun_config.mtu = mtu;
        tun_config.packet_information = false;
        tun_config.dns = dns;
        tun_config.dns_addr = dns_addr;
        if let Some(virtual_dns_pool) = request.virtual_dns_pool {
            tun_config.virtual_dns_pool = virtual_dns_pool;
        }
        if let Some(bypass) = request.bypass {
            tun_config.bypass = bypass;
        }
        tun_config.ipv6 = request.ipv6.unwrap_or(false);
        if let Some(tcp_timeout_secs) = request.tcp_timeout_secs {
            tun_config.tcp_timeout_secs = tcp_timeout_secs;
        }
        if let Some(udp_timeout_secs) = request.udp_timeout_secs {
            tun_config.udp_timeout_secs = udp_timeout_secs;
        }
        if let Some(max_sessions) = request.max_sessions {
            tun_config.max_sessions = max_sessions;
        }
        if let Some(exit_on_fatal_error) = request.exit_on_fatal_error {
            tun_config.exit_on_fatal_error = exit_on_fatal_error;
        }
        let virtual_dns_pool = tun_config.virtual_dns_pool.clone();
        tun_config.verbosity = TunVerbosity::Info;

        let log_bridge = aerion::LogBridge::new();
        let mut event_rx = core.subscribe_events();
        let stop_token = aerion::StopToken::new();

        let runtime = spawn_tun(tun_config).context("spawn Aerion TUN runtime")?;
        let shutdown = runtime.shutdown_token();
        let session_id = NEXT_VPN_SESSION_ID.fetch_add(1, Ordering::SeqCst);
        VPN_SESSIONS
            .lock()
            .expect("VPN session map lock poisoned")
            .insert(
                session_id,
                VpnSession {
                    shutdown: shutdown.clone(),
                    proxy_task,
                    _core: core.clone(),
                    _stop_token: stop_token.clone(),
                },
            );

        let log_task_inner = {
            let mut rx = log_bridge.subscribe();
            tokio::spawn(async move {
                while let Some(entry) = rx.recv().await {
                    on_log(&entry.level.to_string(), &entry.message);
                }
            })
        };

        let event_task_inner = {
            tokio::spawn(async move {
                while let Some(event) = event_rx.recv().await {
                    on_event(&core_event_json(&event, Some(session_id)));
                }
            })
        };

        tokio::spawn(async move {
            if let Err(error) = runtime.wait().await {
                log::error!("Aerion TUN runtime exited with error: {error:?}");
            }
            if let Some(session) = VPN_SESSIONS
                .lock()
                .expect("VPN session map lock poisoned")
                .remove(&session_id)
            {
                session.proxy_task.abort();
                log_task_inner.abort();
                event_task_inner.abort();
                session._stop_token.stop();
            }
        });

        Ok(json!({
            "ok": true,
            "session_id": session_id,
            "mtu": mtu,
            "dns": request.dns.unwrap_or_else(|| "over_tcp".to_string()),
            "dns_addr": dns_addr.to_string(),
            "virtual_dns_pool": virtual_dns_pool,
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
        session._stop_token.stop();
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
