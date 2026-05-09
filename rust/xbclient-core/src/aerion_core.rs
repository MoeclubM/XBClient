use aerion::padding::PaddingScheme;
use aerion::vless_transport::VlessTransportConfig;
use aerion::{
    ClientConfig, Hysteria2ClientConfig, MieruClientConfig, MieruTransport, RealityClientConfig,
    TrojanClientConfig, UtlsFingerprint, VlessClientConfig, VmessClientConfig, run_client_listener,
    run_hysteria2_client_listener, run_mieru_client_listener, run_trojan_client_listener,
    run_vless_client_listener, run_vmess_client_listener,
};
use anyhow::{Context, Result, bail, ensure};
use serde::Deserialize;
use serde_json::{Map, Value, json};
use std::collections::BTreeMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
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

enum AerionProxyConfig {
    AnyTls(ClientConfig),
    Hysteria2(Hysteria2ClientConfig),
    Trojan(TrojanClientConfig),
    Vless(VlessClientConfig),
    Vmess(VmessClientConfig),
    Mieru(MieruClientConfig),
}

async fn start_aerion_socks(node: Value) -> Result<(SocketAddr, JoinHandle<()>)> {
    let listen = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0);
    let listener = TcpListener::bind(listen)
        .await
        .context("bind Aerion local SOCKS listener")?;
    let local_addr = listener.local_addr().context("read Aerion SOCKS address")?;
    let config = node_to_proxy_config(&node, local_addr)?;
    let task = tokio::spawn(async move {
        if let Err(error) = run_aerion_listener(listener, config).await {
            log::error!("Aerion SOCKS listener exited with error: {error:?}");
        }
    });
    Ok((local_addr, task))
}

async fn run_aerion_listener(listener: TcpListener, config: AerionProxyConfig) -> Result<()> {
    match config {
        AerionProxyConfig::AnyTls(config) => run_client_listener(listener, config).await,
        AerionProxyConfig::Hysteria2(config) => {
            run_hysteria2_client_listener(listener, config).await
        }
        AerionProxyConfig::Trojan(config) => run_trojan_client_listener(listener, config).await,
        AerionProxyConfig::Vless(config) => run_vless_client_listener(listener, config).await,
        AerionProxyConfig::Vmess(config) => run_vmess_client_listener(listener, config).await,
        AerionProxyConfig::Mieru(config) => run_mieru_client_listener(listener, config).await,
    }
}

fn node_to_proxy_config(node: &Value, listen: SocketAddr) -> Result<AerionProxyConfig> {
    let protocol = node_protocol(node)?;
    match protocol.as_str() {
        "anytls" => anytls_config(node, listen).map(AerionProxyConfig::AnyTls),
        "hysteria2" => hysteria2_config(node, listen).map(AerionProxyConfig::Hysteria2),
        "trojan" => trojan_config(node, listen).map(AerionProxyConfig::Trojan),
        "vless" => vless_config(node, listen).map(AerionProxyConfig::Vless),
        "vmess" => vmess_config(node, listen).map(AerionProxyConfig::Vmess),
        "mieru" => mieru_config(node, listen).map(AerionProxyConfig::Mieru),
        other => bail!("unsupported Aerion node protocol: {other}"),
    }
}

fn anytls_config(node: &Value, listen: SocketAddr) -> Result<ClientConfig> {
    let server_host = node_string(node, &["host", "server", "address"])?;
    Ok(ClientConfig {
        listen,
        server_port: node_port(node, &["port", "server_port", "server-port"])?,
        password: node_string(node, &["password", "passwd"])?,
        sni: node_optional_string(node, &["sni", "servername", "server-name", "peer"])
            .unwrap_or_else(|| server_host.clone()),
        server_host,
        insecure: node_bool(
            node,
            &["insecure", "skip-cert-verify", "allowInsecure"],
            false,
        ),
        padding_scheme: node_string_list(node, &["padding_scheme", "padding-scheme"])
            .filter(|lines| !lines.is_empty())
            .unwrap_or_else(PaddingScheme::default_lines),
        heartbeat_interval_secs: node_u64(
            node,
            &[
                "heartbeat_interval_secs",
                "heartbeat-interval-secs",
                "heartbeat",
            ],
            30,
        )?,
    })
}

fn hysteria2_config(node: &Value, listen: SocketAddr) -> Result<Hysteria2ClientConfig> {
    ensure!(
        field(node, &["ports"]).is_none(),
        "Hysteria2 port hopping is not supported by this Aerion core binding"
    );
    let server_host = node_string(node, &["server", "host", "address"])?;
    Ok(Hysteria2ClientConfig {
        listen,
        server_port: node_port(node, &["port", "server_port", "server-port"])?,
        password: node_string(node, &["password", "auth"])?,
        sni: node_optional_string(node, &["sni", "servername", "server-name", "peer"])
            .unwrap_or_else(|| server_host.clone()),
        server_host,
        insecure: node_bool(
            node,
            &["insecure", "skip-cert-verify", "allowInsecure"],
            false,
        ),
        obfs: node_optional_string(node, &["obfs"])
            .or_else(|| object_field(node, &["obfs"]).and_then(|opts| map_string(opts, &["type"]))),
        obfs_password: node_optional_string(
            node,
            &["obfs-password", "obfs_password", "obfsPassword"],
        )
        .or_else(|| obfs_nested_password(node)),
        download_bandwidth: node_optional_u64(
            node,
            &["down", "download", "down_mbps", "down-mbps"],
        )?,
        udp: node_bool(node, &["udp"], true),
        congestion_control: node_optional_string(
            node,
            &["congestion-control", "congestion_control"],
        )
        .unwrap_or_else(|| "bbr".to_string()),
    })
}

fn trojan_config(node: &Value, listen: SocketAddr) -> Result<TrojanClientConfig> {
    ensure_tcp_network(node)?;
    ensure!(
        node_bool(node, &["tls"], true),
        "Aerion Trojan client requires TLS"
    );
    let server_host = node_string(node, &["server", "host", "address"])?;
    Ok(TrojanClientConfig {
        listen,
        server_port: node_port(node, &["port", "server_port", "server-port"])?,
        password: node_string(node, &["password", "passwd"])?,
        sni: node_optional_string(node, &["sni", "servername", "server-name", "peer"])
            .unwrap_or_else(|| server_host.clone()),
        server_host,
        insecure: node_bool(
            node,
            &["insecure", "skip-cert-verify", "allowInsecure"],
            false,
        ),
        udp: node_bool(node, &["udp"], true),
        client_fingerprint: client_fingerprint(node)?,
    })
}

fn vless_config(node: &Value, listen: SocketAddr) -> Result<VlessClientConfig> {
    let server_host = node_string(node, &["server", "host", "address"])?;
    let reality = reality_config(node)?;
    ensure!(
        reality.is_some() || node_bool(node, &["tls"], true),
        "Aerion VLESS client requires TLS or REALITY"
    );
    Ok(VlessClientConfig {
        listen,
        server_port: node_port(node, &["port", "server_port", "server-port"])?,
        user_id: node_string(node, &["uuid", "id", "user_id"])?,
        sni: node_optional_string(node, &["sni", "servername", "server-name", "peer"])
            .unwrap_or_else(|| server_host.clone()),
        server_host,
        insecure: node_bool(
            node,
            &["insecure", "skip-cert-verify", "allowInsecure"],
            false,
        ),
        flow: node_optional_string(node, &["flow"]).unwrap_or_default(),
        packet_encoding: node_optional_string(node, &["packet-encoding", "packet_encoding"])
            .unwrap_or_default(),
        mux: mux_enabled(node),
        udp: node_bool(node, &["udp"], true),
        client_fingerprint: client_fingerprint(node)?,
        reality,
        transport: vless_transport(node)?,
    })
}

fn vmess_config(node: &Value, listen: SocketAddr) -> Result<VmessClientConfig> {
    ensure_tcp_network(node)?;
    ensure!(
        node_optional_u64(node, &["alterId", "alter_id"])?.unwrap_or(0) == 0,
        "legacy VMess alterId is not supported by Aerion"
    );
    let server_host = node_string(node, &["server", "host", "address"])?;
    let security = node_optional_string(node, &["security"]);
    let tls = node_bool(node, &["tls"], false)
        || security
            .as_deref()
            .map(|value| value.eq_ignore_ascii_case("tls"))
            .unwrap_or(false);
    let cipher = node_optional_string(node, &["cipher"])
        .or_else(|| {
            security.filter(|value| {
                !value.eq_ignore_ascii_case("tls") && !value.eq_ignore_ascii_case("reality")
            })
        })
        .unwrap_or_else(|| "auto".to_string());
    Ok(VmessClientConfig {
        listen,
        server_port: node_port(node, &["port", "server_port", "server-port"])?,
        user_id: node_string(node, &["uuid", "id", "user_id"])?,
        security: cipher,
        udp: node_bool(node, &["udp"], false),
        tls,
        sni: node_optional_string(node, &["sni", "servername", "server-name", "peer"])
            .unwrap_or_else(|| server_host.clone()),
        server_host,
        insecure: node_bool(
            node,
            &["insecure", "skip-cert-verify", "allowInsecure"],
            false,
        ),
        client_fingerprint: client_fingerprint(node)?,
    })
}

fn mieru_config(node: &Value, listen: SocketAddr) -> Result<MieruClientConfig> {
    Ok(MieruClientConfig {
        listen,
        server_host: node_string(node, &["server", "host", "address"])?,
        server_port: node_port(node, &["port", "server_port", "server-port"])?,
        username: node_string(node, &["username", "user"])?,
        password: node_string(node, &["password", "passwd"])?,
        hashed_password: None,
        mtu: node_u64(node, &["mtu"], 1500)? as usize,
        transport: MieruTransport::parse(
            node_optional_string(node, &["transport", "underlay"])
                .unwrap_or_else(|| "tcp".to_string())
                .as_str(),
        )?,
    })
}

fn node_protocol(node: &Value) -> Result<String> {
    let protocol = node_optional_string(node, &["type", "protocol"])
        .unwrap_or_else(|| "anytls".to_string())
        .to_ascii_lowercase();
    Ok(if protocol == "hy2" {
        "hysteria2".to_string()
    } else {
        protocol
    })
}

fn vless_transport(node: &Value) -> Result<VlessTransportConfig> {
    let network = node_optional_string(node, &["network"]).unwrap_or_else(|| "tcp".to_string());
    if network.eq_ignore_ascii_case("grpc") {
        let opts = object_field(node, &["grpc-opts", "grpc_opts"]);
        return VlessTransportConfig::from_network(
            &network,
            opts.and_then(|opts| map_string(opts, &["grpc-service-name", "grpc_service_name"])),
            opts.and_then(|opts| map_string(opts, &["authority", "host"])),
            map_headers(opts),
        );
    }
    if network.eq_ignore_ascii_case("xhttp") || network.eq_ignore_ascii_case("splithttp") {
        let opts = object_field(node, &["xhttp-opts", "xhttp_opts", "splithttp-opts"]);
        return VlessTransportConfig::xhttp(
            opts.and_then(|opts| map_string(opts, &["path"])),
            opts.and_then(|opts| map_string(opts, &["host"])),
            map_headers(opts),
            opts.and_then(|opts| map_string(opts, &["mode"])),
        );
    }
    let opts = object_field(node, &["ws-opts", "ws_opts", "http-opts", "http_opts"]);
    VlessTransportConfig::from_network(
        &network,
        opts.and_then(|opts| map_string(opts, &["path"]))
            .or_else(|| node_optional_string(node, &["path"])),
        opts.and_then(|opts| map_string(opts, &["host"])),
        map_headers(opts),
    )
}

fn reality_config(node: &Value) -> Result<Option<RealityClientConfig>> {
    let opts = object_field(node, &["reality-opts", "reality_opts"]);
    let public_key = opts
        .and_then(|opts| map_string(opts, &["public-key", "public_key"]))
        .or_else(|| node_optional_string(node, &["public-key", "public_key", "pbk"]));
    let Some(public_key) = public_key else {
        return Ok(None);
    };
    let short_id = opts
        .and_then(|opts| map_string(opts, &["short-id", "short_id"]))
        .or_else(|| node_optional_string(node, &["short-id", "short_id", "sid"]))
        .unwrap_or_default();
    RealityClientConfig::from_strings(&public_key, &short_id).map(Some)
}

fn client_fingerprint(node: &Value) -> Result<Option<UtlsFingerprint>> {
    let Some(value) = node_optional_string(
        node,
        &[
            "client-fingerprint",
            "client_fingerprint",
            "fingerprint",
            "fp",
        ],
    ) else {
        return Ok(None);
    };
    UtlsFingerprint::from_mihomo_name(&value)
}

fn ensure_tcp_network(node: &Value) -> Result<()> {
    let network = node_optional_string(node, &["network"]).unwrap_or_else(|| "tcp".to_string());
    ensure!(
        network.trim().is_empty()
            || network.eq_ignore_ascii_case("tcp")
            || network.eq_ignore_ascii_case("raw"),
        "Aerion binding currently supports raw TCP transport for this protocol, got network={network}"
    );
    Ok(())
}

fn mux_enabled(node: &Value) -> bool {
    match field(node, &["mux"]) {
        Some(Value::Bool(value)) => *value,
        Some(Value::Object(map)) => map_bool(map, &["enabled"], false),
        _ => false,
    }
}

fn field<'a>(node: &'a Value, keys: &[&str]) -> Option<&'a Value> {
    keys.iter().find_map(|key| node.get(*key))
}

fn object_field<'a>(node: &'a Value, keys: &[&str]) -> Option<&'a Map<String, Value>> {
    field(node, keys).and_then(Value::as_object)
}

fn node_string(node: &Value, keys: &[&str]) -> Result<String> {
    node_optional_string(node, keys)
        .filter(|value| !value.trim().is_empty())
        .with_context(|| format!("node field {} is required", keys.join("/")))
}

fn node_optional_string(node: &Value, keys: &[&str]) -> Option<String> {
    field(node, keys).and_then(value_to_string)
}

fn map_string(map: &Map<String, Value>, keys: &[&str]) -> Option<String> {
    keys.iter()
        .find_map(|key| map.get(*key))
        .and_then(value_to_string)
}

fn value_to_string(value: &Value) -> Option<String> {
    match value {
        Value::String(text) => Some(text.trim().to_string()),
        Value::Number(number) => Some(number.to_string()),
        _ => None,
    }
}

fn node_port(node: &Value, keys: &[&str]) -> Result<u16> {
    let port = node_u64(node, keys, 0)?;
    ensure!(
        port > 0 && port <= u16::MAX as u64,
        "node port is out of range"
    );
    Ok(port as u16)
}

fn node_u64(node: &Value, keys: &[&str], default: u64) -> Result<u64> {
    Ok(node_optional_u64(node, keys)?.unwrap_or(default))
}

fn node_optional_u64(node: &Value, keys: &[&str]) -> Result<Option<u64>> {
    let Some(value) = field(node, keys) else {
        return Ok(None);
    };
    match value {
        Value::Number(number) => number
            .as_u64()
            .map(Some)
            .context("number field is out of range"),
        Value::String(text) => text
            .trim()
            .parse::<u64>()
            .map(Some)
            .with_context(|| format!("parse numeric node field {}", keys.join("/"))),
        _ => bail!("node field {} must be a number or string", keys.join("/")),
    }
}

fn node_bool(node: &Value, keys: &[&str], default: bool) -> bool {
    match field(node, keys) {
        Some(Value::Bool(value)) => *value,
        Some(Value::Number(number)) => number.as_u64().unwrap_or(0) != 0,
        Some(Value::String(text)) => matches!(
            text.trim().to_ascii_lowercase().as_str(),
            "1" | "true" | "yes" | "on" | "tls" | "enabled"
        ),
        _ => default,
    }
}

fn map_bool(map: &Map<String, Value>, keys: &[&str], default: bool) -> bool {
    match keys.iter().find_map(|key| map.get(*key)) {
        Some(Value::Bool(value)) => *value,
        Some(Value::Number(number)) => number.as_u64().unwrap_or(0) != 0,
        Some(Value::String(text)) => matches!(
            text.trim().to_ascii_lowercase().as_str(),
            "1" | "true" | "yes" | "on" | "tls" | "enabled"
        ),
        _ => default,
    }
}

fn node_string_list(node: &Value, keys: &[&str]) -> Option<Vec<String>> {
    match field(node, keys)? {
        Value::Array(values) => Some(
            values
                .iter()
                .filter_map(value_to_string)
                .filter(|value| !value.is_empty())
                .collect(),
        ),
        Value::String(text) => Some(
            text.lines()
                .map(str::trim)
                .filter(|value| !value.is_empty())
                .map(str::to_string)
                .collect(),
        ),
        _ => None,
    }
}

fn obfs_nested_password(node: &Value) -> Option<String> {
    object_field(node, &["obfs"]).and_then(|opts| map_string(opts, &["password"]))
}

fn map_headers(opts: Option<&Map<String, Value>>) -> Vec<(String, String)> {
    let Some(headers) = opts
        .and_then(|opts| opts.get("headers"))
        .and_then(Value::as_object)
    else {
        return Vec::new();
    };
    headers
        .iter()
        .filter_map(|(key, value)| value_to_string(value).map(|value| (key.clone(), value)))
        .collect::<BTreeMap<_, _>>()
        .into_iter()
        .collect()
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
    ensure!(
        header[0] == 0x05 && header[1] == 0x00,
        "SOCKS connect failed: {:02x?}",
        header
    );
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
