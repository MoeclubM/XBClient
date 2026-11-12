use crate::aerion_protocol::AerionProxyConfig;
use aerion::padding::PaddingScheme;
use aerion::vless_transport::VlessTransportConfig;
use aerion::{
    ClientConfig, FluxChannelWireConfig, FluxClientConfig, FluxOptions, FluxSecurityMode,
    HttpProxyClientConfig, Hysteria2ClientConfig, MieruClientConfig, MieruTrafficPattern,
    MieruTransport, NaiveClientConfig, RealityClientConfig, RouteClientConfig, RouteDecision,
    ShadowsocksClientConfig, SocksProxyClientConfig, TrojanClientConfig, TuicClientConfig,
    UtlsFingerprint, VlessClientConfig, VmessClientConfig, ensure_vmess_packet_encoding,
};
use anyhow::{Context, Result, bail, ensure};
use serde_json::{Map, Value};
use std::collections::BTreeMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::path::PathBuf;

const INSECURE_KEYS: &[&str] = &[
    "insecure",
    "skip-cert-verify",
    "skip_cert_verify",
    "allow_insecure",
];

pub fn node_to_proxy_config(node: &Value, listen: SocketAddr) -> Result<AerionProxyConfig> {
    let protocol = node_protocol(node)?;
    match protocol.as_str() {
        "anytls" => anytls_config(node, listen).map(AerionProxyConfig::AnyTls),
        "flux" => flux_config(node, listen).map(AerionProxyConfig::Flux),
        "direct" => Ok(AerionProxyConfig::Route(RouteClientConfig {
            listen,
            default: RouteDecision::Direct,
        })),
        "block" => Ok(AerionProxyConfig::Route(RouteClientConfig {
            listen,
            default: RouteDecision::Block,
        })),
        "http" | "https" | "http-proxy" | "https-proxy" | "http+tls" => {
            http_proxy_config(node, listen).map(AerionProxyConfig::HttpProxy)
        }
        "hysteria2" | "hysteria" => {
            hysteria2_config(node, listen).map(AerionProxyConfig::Hysteria2)
        }
        "trojan" => trojan_config(node, listen).map(AerionProxyConfig::Trojan),
        "vless" => vless_config(node, listen).map(AerionProxyConfig::Vless),
        "vmess" => vmess_config(node, listen).map(AerionProxyConfig::Vmess),
        "sudoku" => {
            let options: aerion::SudokuOptions = serde_json::from_value(node.clone())?;
            options.validate()?;
            Ok(AerionProxyConfig::Sudoku(aerion::SudokuClientConfig {
                listen,
                server_host: node_string(node, &["host", "server"])?,
                server_port: node_port(node, &["port"])?,
                key: node_string(node, &["key"])?,
                options,
            }))
        }
        "mieru" => mieru_config(node, listen).map(AerionProxyConfig::Mieru),
        "naive" => naive_config(node, listen).map(AerionProxyConfig::Naive),
        "tuic" => tuic_config(node, listen).map(AerionProxyConfig::Tuic),
        "ss" | "shadowsocks" => {
            shadowsocks_config(node, listen).map(AerionProxyConfig::Shadowsocks)
        }
        "socks" | "socks5" | "socks5h" => {
            socks_proxy_config(node, listen).map(AerionProxyConfig::SocksProxy)
        }
        other => bail!("unsupported Aerion node protocol: {other}"),
    }
}

#[cfg(test)]
mod sudoku_tests {
    use super::*;
    #[test]
    fn plugin_sudoku_node_preserves_credentials_and_appearance() -> Result<()> {
        let node = serde_json::json!({
            "type":"sudoku", "host":"node.example.com", "port":443, "key":"user-uuid",
            "aead-method":"aes-128-gcm", "table-type":"up_ascii_down_entropy",
            "padding-min":0, "padding-max":10, "enable-pure-downlink":false,
            "custom-tables":["xpxvvpvv"], "http-mask":true, "http-mask-mode":"ws",
            "path-root":"edge", "http-mask-multiplex":"on", "udp":true, "client_supported":true
        });
        let AerionProxyConfig::Sudoku(config) =
            node_to_proxy_config(&node, "127.0.0.1:1080".parse()?)?
        else {
            panic!("expected Sudoku")
        };
        assert_eq!(config.key, "user-uuid");
        assert_eq!(config.options.aead, "aes-128-gcm");
        assert_eq!(config.options.custom_tables, ["xpxvvpvv"]);
        assert_eq!(config.options.padding_min, 0);
        assert_eq!(config.options.http_mask_mode, "ws");
        assert_eq!(config.options.path_root, "edge");
        assert_eq!(config.options.multiplex, "on");
        Ok(())
    }
    #[test]
    fn sudoku_rejects_missing_psk_and_unimplemented_httpmask() {
        for node in [
            serde_json::json!({"type":"sudoku","host":"example.com","port":443}),
            serde_json::json!({"type":"sudoku","host":"example.com","port":443,"key":"test","http-mask":true,"http-mask-mode":"poll"}),
        ] {
            assert!(node_to_proxy_config(&node, "127.0.0.1:1080".parse().unwrap()).is_err());
        }
    }
}

fn anytls_config(node: &Value, listen: SocketAddr) -> Result<ClientConfig> {
    let server_host = node_string(node, &["host", "server"])?;
    let tls = tls_object_field(node)?;
    ensure!(
        tls_bool(node, tls, &["enabled"], true)?,
        "Aerion AnyTLS client requires TLS"
    );
    Ok(ClientConfig {
        listen,
        server_port: node_port(node, &["port"])?,
        password: node_string(node, &["password"])?,
        sni: node_sni(node, tls, &server_host),
        server_host: server_host.clone(),
        insecure: tls_bool(node, tls, INSECURE_KEYS, false)?,
        ca_cert_paths: tls_ca_cert_paths(tls)?,
        ca_certificates: tls_ca_certificates(tls)?,
        disable_system_roots: tls_disable_system_roots(tls)?,
        pinned_cert_sha256: tls_pinned_cert_sha256(tls)?,
        client_fingerprint: client_fingerprint(node, tls)?,
        padding_scheme: node_string_list(node, &["padding_scheme", "padding-scheme"])?
            .filter(|lines| !lines.is_empty())
            .unwrap_or_else(PaddingScheme::default_lines),
        heartbeat_interval_secs: node_duration_secs(
            node,
            &[
                "heartbeat_interval_secs",
                "idle-session-check-interval",
                "idle_session_check_interval",
            ],
            30,
        )?,
    })
}

fn flux_config(node: &Value, listen: SocketAddr) -> Result<FluxClientConfig> {
    let merged = merged_flux_node(node)?;
    let server_host = node_string(&merged, &["host", "server"])?;
    let raw_port = node_optional_u64(&merged, &["port"])?.unwrap_or(0);
    ensure!(raw_port <= u16::MAX as u64, "Flux port is out of range");
    let server_port = raw_port as u16;
    let key = node_string(&merged, &["key", "uuid", "password"])?;
    let udp = node_bool(&merged, &["udp"], true)?;

    let mut options_map = Map::new();
    for (dst, keys) in [
        (
            "ip_strategy",
            &["ip_strategy", "ip-strategy", "ipStrategy"][..],
        ),
        (
            "standby_channels",
            &["standby_channels", "standby-channels", "standbyChannels"][..],
        ),
        (
            "rotation_interval_sec",
            &[
                "rotation_interval_sec",
                "rotation-interval-sec",
                "rotation-interval",
                "rotationIntervalSec",
            ][..],
        ),
        (
            "rotation_bytes_mb",
            &[
                "rotation_bytes_mb",
                "rotation-bytes-mb",
                "rotation-bytes",
                "rotationBytesMb",
            ][..],
        ),
    ] {
        if let Some(val) = field(&merged, keys)
            && !val.is_null()
        {
            options_map.insert(dst.to_string(), val.clone());
        }
    }
    let options: FluxOptions =
        serde_json::from_value(Value::Object(options_map)).context("parse Flux options")?;
    options.validate()?;

    let tls = tls_object_field(&merged)?;
    let _ = tls_bool(&merged, tls, &["enabled"], true)?;
    let default_reality = reality_config(&merged, tls)?;
    let default_obfs_key = node_optional_string(
        &merged,
        &[
            "obfs_key",
            "obfs-key",
            "obfs_password",
            "obfs-password",
            "obfsKey",
        ],
    )
    .filter(|v| !v.is_empty());
    let default_security = match node_optional_string(&merged, &["security"])
        .filter(|v| !v.is_empty())
        .as_deref()
    {
        Some(mode) => match mode.to_ascii_lowercase().as_str() {
            "reality" => FluxSecurityMode::Reality,
            "tls" => FluxSecurityMode::Tls,
            "obfs" | "salamander" => FluxSecurityMode::Obfs,
            other => bail!("unsupported Flux security mode: {other}"),
        },
        None => {
            if default_reality.is_some() {
                FluxSecurityMode::Reality
            } else if default_obfs_key.is_some() {
                FluxSecurityMode::Obfs
            } else {
                FluxSecurityMode::Tls
            }
        }
    };
    let default_sni = node_sni(&merged, tls, &server_host);
    let default_fingerprint = client_fingerprint(&merged, tls)?;
    let default_insecure = tls_bool(&merged, tls, INSECURE_KEYS, false)?;
    let default_alpn = node_alpn_list(&merged, tls)?.unwrap_or_default();
    let ca_cert_paths = tls_ca_cert_paths(tls)?;
    let ca_certificates = tls_ca_certificates(tls)?;
    let pinned_cert_sha256 = tls_pinned_cert_sha256(tls)?;
    let resolved_v4 = parse_ipv4_list(&merged, &["resolved_v4", "resolved-v4", "resolvedV4"])?;
    let resolved_v6 = parse_ipv6_list(&merged, &["resolved_v6", "resolved-v6", "resolvedV6"])?;

    let wire_channels: Vec<FluxChannelWireConfig> = match field(&merged, &["channels"]) {
        Some(val) if !val.is_null() => {
            serde_json::from_value(val.clone()).context("parse Flux channels")?
        }
        _ => Vec::new(),
    };

    let proxy = aerion::config_compat::mihomo::MihomoFluxProxy {
        name: node_optional_string(&merged, &["name"]).unwrap_or_else(|| "Flux".to_string()),
        server: server_host,
        port: server_port,
        key,
        udp,
        security: Some(default_security),
        servername: Some(default_sni),
        client_fingerprint: default_fingerprint,
        skip_cert_verify: default_insecure,
        alpn: if default_alpn.is_empty() {
            None
        } else {
            Some(serde_json::from_value(serde_json::json!(default_alpn))?)
        },
        reality_opts: None,
        public_key: default_reality.as_ref().map(|r| {
            use base64::Engine;
            base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(r.public_key)
        }),
        short_id: default_reality.as_ref().map(|r| {
            r.short_id
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect::<String>()
        }),
        obfs_key: default_obfs_key,
        resolved_v4,
        resolved_v6,
        channels: wire_channels,
        options,
        fields: BTreeMap::new(),
    };
    let mut config = proxy.to_client_config(listen)?;
    for channel in &mut config.channels {
        if channel.security == FluxSecurityMode::Tls {
            channel.ca_cert_paths = ca_cert_paths.clone();
            channel.ca_certificates = ca_certificates.clone();
            channel.pinned_cert_sha256 = pinned_cert_sha256.clone();
        }
    }
    Ok(config)
}

fn merged_flux_node(node: &Value) -> Result<Value> {
    let Value::Object(root) = node else {
        bail!("Flux node must be an object");
    };
    let mut merged = Map::new();
    if let Some(nested) = root.get("flux").and_then(Value::as_object) {
        for (k, v) in nested {
            merged.insert(k.clone(), v.clone());
        }
    }
    for (k, v) in root {
        if k != "flux" {
            merged.insert(k.clone(), v.clone());
        }
    }
    Ok(Value::Object(merged))
}

fn parse_ipv4_list(node: &Value, keys: &[&str]) -> Result<Vec<Ipv4Addr>> {
    let Some(list) = node_string_list(node, keys)? else {
        return Ok(Vec::new());
    };
    list.into_iter()
        .map(|item| {
            item.parse::<Ipv4Addr>()
                .with_context(|| format!("invalid Flux resolved IPv4 address: {item}"))
        })
        .collect()
}

fn parse_ipv6_list(node: &Value, keys: &[&str]) -> Result<Vec<Ipv6Addr>> {
    let Some(list) = node_string_list(node, keys)? else {
        return Ok(Vec::new());
    };
    list.into_iter()
        .map(|item| {
            item.trim_matches(['[', ']'])
                .parse::<Ipv6Addr>()
                .with_context(|| format!("invalid Flux resolved IPv6 address: {item}"))
        })
        .collect()
}

fn hysteria2_config(node: &Value, listen: SocketAddr) -> Result<Hysteria2ClientConfig> {
    ensure!(
        !has_non_empty_field(node, &["ports", "port-range", "port_range"]),
        "Hysteria2 port hopping is not supported by this Aerion core binding"
    );
    let server_host = node_string(node, &["host", "server"])?;
    let tls = tls_object_field(node)?;
    ensure!(
        tls_bool(node, tls, &["enabled"], true)?,
        "Aerion Hysteria2 client requires TLS"
    );
    let obfs = match field(node, &["obfs"]) {
        Some(Value::Object(map)) => Some(map),
        Some(Value::String(_) | Value::Null) | None => None,
        Some(_) => bail!("node field obfs must be a string or object"),
    };
    Ok(Hysteria2ClientConfig {
        listen,
        server_port: node_port(node, &["port"])?,
        password: node_string(node, &["password"])?,
        sni: node_sni(node, tls, &server_host),
        server_host: server_host.clone(),
        insecure: tls_bool(node, tls, INSECURE_KEYS, false)?,
        certificate_fingerprint: tls
            .and_then(|opts| map_string(opts, &["certificate_fingerprint"])),
        ca_cert_paths: tls_ca_cert_paths(tls)?,
        ca_certificates: tls_ca_certificates(tls)?,
        disable_system_roots: tls_disable_system_roots(tls)?,
        pinned_cert_sha256: tls_pinned_cert_sha256(tls)?,
        obfs: node_optional_string(node, &["obfs"])
            .or_else(|| obfs.and_then(|opts| map_string(opts, &["type"])))
            .filter(|v| !v.is_empty()),
        obfs_password: node_optional_string(node, &["obfs_password", "obfs-password"])
            .or_else(|| obfs.and_then(|opts| map_string(opts, &["password"])))
            .filter(|v| !v.is_empty()),
        upload_bandwidth: node_optional_bandwidth_u64(node, &["up", "up_mbps", "up-mbps"])?,
        download_bandwidth: node_optional_bandwidth_u64(node, &["down", "down_mbps", "down-mbps"])?,
        udp: node_bool(node, &["udp"], true)?,
        congestion_control: node_optional_string(
            node,
            &["congestion_control", "congestion-controller"],
        )
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| "bbr".to_string()),
    })
}

fn trojan_config(node: &Value, listen: SocketAddr) -> Result<TrojanClientConfig> {
    let transport = vless_transport(node)?;
    let tls = tls_object_field(node)?;
    ensure!(
        reality_config(node, tls)?.is_none(),
        "Aerion Trojan client does not support REALITY"
    );
    ensure!(
        tls_bool(node, tls, &["enabled"], true)?,
        "Aerion Trojan client requires TLS"
    );
    let server_host = node_string(node, &["host", "server"])?;
    Ok(TrojanClientConfig {
        listen,
        server_port: node_port(node, &["port"])?,
        password: node_string(node, &["password"])?,
        sni: node_sni(node, tls, &server_host),
        server_host,
        insecure: tls_bool(node, tls, INSECURE_KEYS, false)?,
        ca_cert_paths: tls_ca_cert_paths(tls)?,
        ca_certificates: tls_ca_certificates(tls)?,
        disable_system_roots: tls_disable_system_roots(tls)?,
        pinned_cert_sha256: tls_pinned_cert_sha256(tls)?,
        udp: node_bool(node, &["udp"], true)?,
        client_fingerprint: client_fingerprint(node, tls)?,
        transport,
    })
}

fn vless_config(node: &Value, listen: SocketAddr) -> Result<VlessClientConfig> {
    let server_host = node_string(node, &["host", "server"])?;
    let tls = tls_object_field(node)?;
    let reality = reality_config(node, tls)?;
    let tls_enabled = reality.is_none() && tls_bool(node, tls, &["enabled"], true)?;
    let client_fingerprint = client_fingerprint(node, tls)?;
    ensure!(
        tls_enabled || reality.is_some() || client_fingerprint.is_none(),
        "Aerion VLESS client cannot use client fingerprint without TLS or REALITY"
    );
    Ok(VlessClientConfig {
        listen,
        server_port: node_port(node, &["port"])?,
        user_id: node_string(node, &["uuid"])?,
        tls: tls_enabled,
        sni: node_sni(node, tls, &server_host),
        server_host,
        insecure: if tls_enabled {
            tls_bool(node, tls, INSECURE_KEYS, false)?
        } else {
            false
        },
        ca_cert_paths: tls_ca_cert_paths(tls)?,
        ca_certificates: tls_ca_certificates(tls)?,
        disable_system_roots: tls_disable_system_roots(tls)?,
        pinned_cert_sha256: tls_pinned_cert_sha256(tls)?,
        flow: node_optional_string(node, &["flow"]).unwrap_or_default(),
        packet_encoding: node_optional_string(node, &["packet_encoding", "packet-encoding"]).unwrap_or_default(),
        mux: mux_enabled(node)?,
        udp: node_bool(node, &["udp"], true)?,
        client_fingerprint,
        reality,
        transport: vless_transport(node)?,
    })
}

fn vmess_config(node: &Value, listen: SocketAddr) -> Result<VmessClientConfig> {
    let alter_id = node_optional_u64(node, &["alter_id", "alterId", "aid"])?.unwrap_or(0);
    ensure!(alter_id == 0, "VMess alterId is not supported by Aerion");
    let server_host = node_string(node, &["host", "server"])?;
    let transport = vless_transport(node)?;
    let tls_options = tls_object_field(node)?;
    let tls = tls_bool(node, tls_options, &["enabled"], false)?;
    let cipher = node_optional_string(node, &["cipher", "security"])
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| "auto".to_string());
    let client_fingerprint = client_fingerprint(node, tls_options)?;
    ensure!(
        tls || client_fingerprint.is_none(),
        "Aerion VMess client cannot use client fingerprint without TLS"
    );
    let packet_encoding =
        node_optional_string(node, &["packet_encoding", "packet-encoding"]).unwrap_or_default();
    ensure_vmess_packet_encoding(&packet_encoding)?;
    Ok(VmessClientConfig {
        listen,
        server_port: node_port(node, &["port"])?,
        user_id: node_string(node, &["uuid"])?,
        security: cipher,
        packet_encoding,
        udp: node_bool(node, &["udp"], false)?,
        tls,
        sni: node_sni(node, tls_options, &server_host),
        server_host,
        insecure: if tls {
            tls_bool(node, tls_options, INSECURE_KEYS, false)?
        } else {
            false
        },
        ca_cert_paths: tls_ca_cert_paths(tls_options)?,
        ca_certificates: tls_ca_certificates(tls_options)?,
        disable_system_roots: tls_disable_system_roots(tls_options)?,
        pinned_cert_sha256: tls_pinned_cert_sha256(tls_options)?,
        client_fingerprint,
        transport,
    })
}

fn mieru_config(node: &Value, listen: SocketAddr) -> Result<MieruClientConfig> {
    ensure!(
        !has_non_empty_field(node, &["port-range", "port_range", "ports"]),
        "Mieru port range is not supported by this Aerion core binding"
    );
    let username = node_optional_string(node, &["username"])
        .filter(|value| !value.is_empty())
        .ok_or_else(|| anyhow::anyhow!("node field username is required for Mieru"))?;
    let password = node_optional_string(node, &["password"]);
    let hashed_password = node_optional_string(node, &["hashed_password", "hashed-password"])
        .filter(|value| !value.is_empty())
        .map(|value| parse_mieru_hash(&value))
        .transpose()?;
    ensure!(
        hashed_password.is_some() || password.as_deref().is_some_and(|value| !value.is_empty()),
        "node field password or hashed_password is required for Mieru"
    );
    Ok(MieruClientConfig {
        listen,
        server_host: node_string(node, &["host", "server"])?,
        server_port: node_port(node, &["port"])?,
        username,
        password: password.unwrap_or_default(),
        hashed_password,
        mtu: node_u64(node, &["mtu"], 0)? as usize,
        transport: MieruTransport::parse(
            node_optional_string(node, &["transport"])
                .filter(|v| !v.is_empty())
                .unwrap_or_else(|| "tcp".to_string())
                .as_str(),
        )?,
        traffic_pattern: MieruTrafficPattern::parse_pair(
            node_optional_string(node, &["traffic_pattern", "traffic-pattern"]).as_deref(),
            node_optional_string(node, &["nonce_pattern", "nonce-pattern"]).as_deref(),
        )
        .context("parse Mieru traffic pattern")?,
    })
}

fn naive_config(node: &Value, listen: SocketAddr) -> Result<NaiveClientConfig> {
    let tls = tls_object_field(node)?;
    ensure!(
        tls_bool(node, tls, &["enabled"], true)?,
        "Naive client requires HTTPS/TLS proxy"
    );
    let server_host = node_string(node, &["host", "server"])?;
    let server_port = node_optional_u64(node, &["port"])?.unwrap_or(443);
    ensure!(
        server_port > 0 && server_port <= u16::MAX as u64,
        "node port is out of range"
    );
    Ok(NaiveClientConfig {
        listen,
        server_host: server_host.clone(),
        server_port: server_port as u16,
        username: node_optional_string(node, &["username"]).unwrap_or_default(),
        password: node_optional_string(node, &["password"]).unwrap_or_default(),
        sni: node_sni(node, tls, &server_host),
        insecure: tls_bool(node, tls, INSECURE_KEYS, false)?,
        ca_cert_paths: tls_ca_cert_paths(tls)?,
        ca_certificates: tls_ca_certificates(tls)?,
        disable_system_roots: tls_disable_system_roots(tls)?,
        pinned_cert_sha256: tls_pinned_cert_sha256(tls)?,
        extra_headers: naive_extra_headers(node)?,
        udp_over_tcp: udp_over_tcp_enabled(node)?,
        quic: node_optional_string(node, &["type"])
            .map(|protocol| protocol.eq_ignore_ascii_case("naive+quic"))
            .unwrap_or(false)
            || node_bool(node, &["quic"], false)?
            || node_optional_string(node, &["network"])
                .map(|network| {
                    matches!(
                        network.to_ascii_lowercase().as_str(),
                        "quic" | "h3" | "http3"
                    )
                })
                .unwrap_or(false),
        quic_congestion_control: node_optional_string(node, &["quic_congestion_control", "quic-congestion-control"]).filter(|v| !v.is_empty())
            .unwrap_or_else(aerion::naive::default_naive_quic_congestion_control),
    })
}

fn tuic_config(node: &Value, listen: SocketAddr) -> Result<TuicClientConfig> {
    let tls = tls_object_field(node)?;
    ensure!(
        tls_bool(node, tls, &["enabled"], true)?,
        "TUIC client requires TLS"
    );
    let server_host = node_string(node, &["host", "server"])?;
    Ok(TuicClientConfig {
        listen,
        server_port: node_port(node, &["port"])?,
        uuid: node_string(node, &["uuid"])?,
        password: node_string(node, &["password"])?,
        sni: node_sni(node, tls, &server_host),
        server_host,
        insecure: tls_bool(node, tls, INSECURE_KEYS, false)?,
        ca_cert_paths: tls_ca_cert_paths(tls)?,
        ca_certificates: tls_ca_certificates(tls)?,
        disable_system_roots: tls_disable_system_roots(tls)?,
        pinned_cert_sha256: tls_pinned_cert_sha256(tls)?,
        udp: node_bool(node, &["udp"], true)?,
        udp_relay_mode: node_optional_string(node, &["udp_relay_mode", "udp-relay-mode"])
            .filter(|v| !v.is_empty())
            .unwrap_or_else(|| "native".to_string()),
        congestion_control: node_optional_string(
            node,
            &["congestion_control", "congestion-controller"],
        )
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| "cubic".to_string()),
        alpn_protocols: node_alpn_list(node, tls)?.unwrap_or_else(|| vec!["h3".to_string()]),
        heartbeat_interval_secs: node_duration_secs(
            node,
            &["heartbeat_interval_secs", "heartbeat-interval", "heartbeat"],
            10,
        )?,
    })
}

fn shadowsocks_config(node: &Value, listen: SocketAddr) -> Result<ShadowsocksClientConfig> {
    ensure!(
        !has_non_empty_field(node, &["plugin", "plugin-opts", "plugin_opts"]),
        "Shadowsocks plugin is not supported by Aerion"
    );
    let udp_over_tcp = udp_over_tcp_enabled(node)?;
    Ok(ShadowsocksClientConfig {
        listen,
        server_host: node_string(node, &["host", "server"])?,
        server_port: node_port(node, &["port"])?,
        method: node_string(node, &["cipher", "method"])?,
        password: node_string(node, &["password"])?,
        udp: node_bool(node, &["udp"], true)? || udp_over_tcp,
        udp_over_tcp,
    })
}

fn http_proxy_config(node: &Value, listen: SocketAddr) -> Result<HttpProxyClientConfig> {
    let server_host = node_string(node, &["host", "server"])?;
    let tls = tls_object_field(node)?;
    let protocol = node_string(node, &["type"])?;
    let tls_enabled = tls_bool(
        node,
        tls,
        &["enabled"],
        protocol.eq_ignore_ascii_case("https")
            || protocol.eq_ignore_ascii_case("https-proxy")
            || protocol.eq_ignore_ascii_case("http+tls"),
    )?;
    let client_fingerprint = client_fingerprint(node, tls)?;
    let ca_cert_paths = tls_ca_cert_paths(tls)?;
    let ca_certificates = tls_ca_certificates(tls)?;
    let disable_system_roots = tls_disable_system_roots(tls)?;
    let pinned_cert_sha256 = tls_pinned_cert_sha256(tls)?;
    ensure!(
        tls_enabled
            || (!node_bool(node, INSECURE_KEYS, false)?
                && ca_cert_paths.is_empty()
                && ca_certificates.is_empty()
                && !disable_system_roots
                && pinned_cert_sha256.is_empty()
                && client_fingerprint.is_none()
                && node_alpn_list(node, tls)?.unwrap_or_default().is_empty()),
        "Aerion HTTP proxy node sets TLS-only options while TLS is disabled"
    );
    Ok(HttpProxyClientConfig {
        listen,
        server_host: server_host.clone(),
        server_port: node_port(node, &["port"])?,
        username: node_optional_string(node, &["username"]).unwrap_or_default(),
        password: node_optional_string(node, &["password"]).unwrap_or_default(),
        tls: tls_enabled,
        sni: node_sni(node, tls, &server_host),
        insecure: if tls_enabled {
            tls_bool(node, tls, INSECURE_KEYS, false)?
        } else {
            false
        },
        ca_cert_paths: if tls_enabled {
            ca_cert_paths
        } else {
            Vec::new()
        },
        ca_certificates: if tls_enabled {
            ca_certificates
        } else {
            Vec::new()
        },
        disable_system_roots: tls_enabled && disable_system_roots,
        pinned_cert_sha256: if tls_enabled {
            pinned_cert_sha256
        } else {
            Vec::new()
        },
        client_fingerprint: if tls_enabled {
            client_fingerprint
        } else {
            None
        },
        extra_headers: naive_extra_headers(node)?,
    })
}

fn socks_proxy_config(node: &Value, listen: SocketAddr) -> Result<SocksProxyClientConfig> {
    let tls = tls_object_field(node)?;
    ensure!(
        !tls_bool(node, tls, &["enabled"], false)?
            && !node_bool(node, INSECURE_KEYS, false)?
            && tls_ca_cert_paths(tls)?.is_empty()
            && tls_ca_certificates(tls)?.is_empty()
            && !tls_disable_system_roots(tls)?
            && tls_pinned_cert_sha256(tls)?.is_empty()
            && client_fingerprint(node, tls)?.is_none()
            && node_alpn_list(node, tls)?.unwrap_or_default().is_empty(),
        "Aerion SOCKS proxy node sets TLS-only options"
    );
    ensure!(
        !has_non_empty_field(node, &["extra_headers", "extra-headers"]),
        "Aerion SOCKS proxy node sets HTTP headers; SOCKS does not use headers"
    );
    Ok(SocksProxyClientConfig {
        listen,
        server_host: node_string(node, &["host", "server"])?,
        server_port: node_port(node, &["port"])?,
        username: node_optional_string(node, &["username"]).unwrap_or_default(),
        password: node_optional_string(node, &["password"]).unwrap_or_default(),
        udp: node_bool(node, &["udp"], true)?,
    })
}

fn node_protocol(node: &Value) -> Result<String> {
    Ok(node_string(node, &["type"])?.to_ascii_lowercase())
}

fn vless_transport(node: &Value) -> Result<VlessTransportConfig> {
    let network = node_optional_string(node, &["network"])
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| "tcp".to_string());
    if let Some(opts) = object_field(node, &["transport"])? {
        let kind = map_string(opts, &["type"])
            .filter(|v| !v.is_empty())
            .unwrap_or_else(|| network.clone());
        let host = match map_first_string(opts, &["host"])? {
            Some(value) => Some(value),
            None => header_value(opts, "host")?,
        };
        let path = if kind.eq_ignore_ascii_case("grpc") {
            map_string(
                opts,
                &[
                    "service_name",
                    "grpc_service_name",
                    "grpc-service-name",
                    "serviceName",
                ],
            )
        } else {
            map_first_string(opts, &["path"])?
        };
        if kind.eq_ignore_ascii_case("xhttp") || kind.eq_ignore_ascii_case("splithttp") {
            return VlessTransportConfig::xhttp(
                path,
                host,
                map_headers(Some(opts))?,
                map_string(opts, &["mode"]),
            );
        }
        return VlessTransportConfig::from_network(&kind, path, host, map_headers(Some(opts))?);
    }
    if network.eq_ignore_ascii_case("grpc") {
        let opts = object_field(node, &["grpc_opts", "grpc-opts"])?;
        let host = match opts
            .and_then(|o| map_string(o, &["authority", "host"]).filter(|v| !v.is_empty()))
        {
            Some(value) => Some(value),
            None => match opts {
                Some(o) => header_value(o, "host")?,
                None => None,
            },
        };
        return VlessTransportConfig::from_network(
            &network,
            opts.and_then(|o| {
                map_string(
                    o,
                    &[
                        "grpc_service_name",
                        "grpc-service-name",
                        "service_name",
                        "serviceName",
                    ],
                )
            }),
            host,
            map_headers(opts)?,
        );
    }
    if network.eq_ignore_ascii_case("xhttp") || network.eq_ignore_ascii_case("splithttp") {
        let opts = object_field(
            node,
            &["xhttp_opts", "xhttp-opts", "splithttp_opts", "splithttp-opts"],
        )?;
        let path = match opts {
            Some(o) => map_first_string(o, &["path"])?,
            None => None,
        };
        let host = match opts {
            Some(o) => match map_first_string(o, &["host"])? {
                Some(value) => Some(value),
                None => header_value(o, "host")?,
            },
            None => None,
        };
        return VlessTransportConfig::xhttp(
            path,
            host,
            map_headers(opts)?,
            opts.and_then(|o| map_string(o, &["mode"])),
        );
    }
    if matches!(
        network.to_ascii_lowercase().as_str(),
        "h2" | "http" | "http2"
    ) {
        let opts = object_field(node, &["h2_opts", "h2-opts", "http_opts", "http-opts"])?;
        let path = match opts {
            Some(o) => map_first_string(o, &["path"])?,
            None => None,
        };
        let host = match opts {
            Some(o) => match map_first_string(o, &["host"])? {
                Some(value) => Some(value),
                None => header_value(o, "host")?,
            },
            None => None,
        };
        return VlessTransportConfig::from_network(&network, path, host, map_headers(opts)?);
    }
    let opts = object_field(
        node,
        &["ws_opts", "ws-opts", "httpupgrade_opts", "httpupgrade-opts"],
    )?;
    let effective_network = if (network.eq_ignore_ascii_case("ws")
        || network.eq_ignore_ascii_case("websocket"))
        && opts
            .map(|o| map_bool(o, &["v2ray-http-upgrade", "v2ray_http_upgrade"], false))
            .transpose()?
            .unwrap_or(false)
    {
        "httpupgrade"
    } else {
        network.as_str()
    };
    let path = match opts {
        Some(o) => map_first_string(o, &["path"])?,
        None => None,
    };
    let host = match opts {
        Some(o) => match map_first_string(o, &["host"])? {
            Some(value) => Some(value),
            None => header_value(o, "host")?,
        },
        None => None,
    };
    VlessTransportConfig::from_network(effective_network, path, host, map_headers(opts)?)
}

fn reality_config(
    node: &Value,
    tls: Option<&Map<String, Value>>,
) -> Result<Option<RealityClientConfig>> {
    let opts = object_field(node, &["reality_opts", "reality-opts"])?;
    let tls_reality = match tls.and_then(|t| t.get("reality")) {
        Some(Value::Object(map)) if map_bool(map, &["enabled"], true)? => Some(map),
        Some(Value::Object(_) | Value::Null) | None => None,
        Some(_) => bail!("node field tls.reality must be an object"),
    };
    let public_key = opts
        .and_then(|o| map_string(o, &["public_key", "public-key", "publicKey"]))
        .or_else(|| {
            tls_reality.and_then(|o| map_string(o, &["public_key", "public-key", "publicKey"]))
        })
        .or_else(|| node_optional_string(node, &["public_key", "public-key", "publicKey"]))
        .filter(|v| !v.is_empty());
    let Some(public_key) = public_key else {
        return Ok(None);
    };
    let short_id = opts
        .and_then(|o| map_string(o, &["short_id", "short-id", "shortId"]))
        .or_else(|| tls_reality.and_then(|o| map_string(o, &["short_id", "short-id", "shortId"])))
        .or_else(|| node_optional_string(node, &["short_id", "short-id", "shortId"]))
        .unwrap_or_default();
    RealityClientConfig::from_strings(&public_key, &short_id).map(Some)
}

fn client_fingerprint(
    node: &Value,
    tls: Option<&Map<String, Value>>,
) -> Result<Option<UtlsFingerprint>> {
    let utls_fp = match tls.and_then(|t| t.get("utls")) {
        Some(Value::Object(map)) if map_bool(map, &["enabled"], true)? => {
            map_string(map, &["fingerprint"]).filter(|v| !v.is_empty())
        }
        Some(Value::Object(_) | Value::Null) | None => None,
        Some(_) => bail!("node field tls.utls must be an object"),
    };
    let Some(value) = node_optional_string(
        node,
        &["client_fingerprint", "client-fingerprint", "fingerprint"],
    )
    .filter(|v| !v.is_empty())
    .or(utls_fp) else {
        return Ok(None);
    };
    UtlsFingerprint::from_mihomo_name(&value)
}

fn mux_enabled(node: &Value) -> Result<bool> {
    match field(node, &["mux", "smux", "multiplex"]) {
        Some(Value::Bool(value)) => Ok(*value),
        Some(Value::Object(map)) => map_bool(map, &["enabled"], false),
        Some(Value::Null) | None => Ok(false),
        Some(_) => bail!("node field mux must be a boolean or object"),
    }
}

fn field<'a>(node: &'a Value, keys: &[&str]) -> Option<&'a Value> {
    keys.iter().find_map(|key| node.get(*key))
}

fn has_non_empty_field(node: &Value, keys: &[&str]) -> bool {
    field(node, keys)
        .is_some_and(|v| !v.is_null() && value_to_string(v).as_deref() != Some(""))
}

fn tls_object_field(node: &Value) -> Result<Option<&Map<String, Value>>> {
    match node.get("tls") {
        Some(Value::Object(map)) => Ok(Some(map)),
        Some(Value::Bool(_) | Value::Number(_) | Value::String(_) | Value::Null) | None => Ok(None),
        Some(_) => bail!("node field tls must be an object or boolean"),
    }
}

fn object_field<'a>(node: &'a Value, keys: &[&str]) -> Result<Option<&'a Map<String, Value>>> {
    match field(node, keys) {
        Some(Value::Object(map)) => Ok(Some(map)),
        Some(Value::Null) | None => Ok(None),
        Some(_) => bail!("node field {} must be an object", keys.join("/")),
    }
}

fn node_string(node: &Value, keys: &[&str]) -> Result<String> {
    node_optional_string(node, keys)
        .filter(|value| !value.trim().is_empty())
        .with_context(|| format!("node field {} is required", keys.join("/")))
}

fn node_optional_string(node: &Value, keys: &[&str]) -> Option<String> {
    field(node, keys).and_then(value_to_string)
}

fn node_sni(node: &Value, tls: Option<&Map<String, Value>>, server_host: &str) -> String {
    let values = [
        node_optional_string(node, &["sni"]),
        node_optional_string(node, &["server_name"]),
        node_optional_string(node, &["servername"]),
        node_optional_string(node, &["server-name"]),
        tls.and_then(|opts| map_string(opts, &["server_name"])),
        tls.and_then(|opts| map_string(opts, &["servername"])),
        tls.and_then(|opts| map_string(opts, &["server-name"])),
    ];
    values
        .into_iter()
        .flatten()
        .find(|value| !value.is_empty() && !is_ip_literal(value))
        .unwrap_or_else(|| server_host.to_string())
}

fn is_ip_literal(value: &str) -> bool {
    let value = value.trim();
    let host = value
        .strip_prefix('[')
        .and_then(|inner| inner.strip_suffix(']'))
        .unwrap_or(value);
    host.parse::<IpAddr>().is_ok()
}

fn map_string(map: &Map<String, Value>, keys: &[&str]) -> Option<String> {
    keys.iter()
        .find_map(|key| map.get(*key))
        .and_then(value_to_string)
}

fn map_first_string(map: &Map<String, Value>, keys: &[&str]) -> Result<Option<String>> {
    let Some(value) = keys.iter().find_map(|key| map.get(*key)) else {
        return Ok(None);
    };
    match value {
        Value::Null => Ok(None),
        Value::String(text) => Ok(Some(text.trim().to_string()).filter(|v| !v.is_empty())),
        Value::Array(items) => {
            for item in items {
                let text = value_to_string(item).with_context(|| {
                    format!("map field {} array item must be a string", keys.join("/"))
                })?;
                if !text.is_empty() {
                    return Ok(Some(text));
                }
            }
            Ok(None)
        }
        _ => bail!("map field {} must be a string or array", keys.join("/")),
    }
}

fn value_to_string(value: &Value) -> Option<String> {
    match value {
        Value::String(text) => Some(text.trim().to_string()),
        _ => None,
    }
}

fn node_port(node: &Value, keys: &[&str]) -> Result<u16> {
    let port = node_required_u64(node, keys)?;
    ensure!(
        port > 0 && port <= u16::MAX as u64,
        "node port is out of range"
    );
    Ok(port as u16)
}

fn node_required_u64(node: &Value, keys: &[&str]) -> Result<u64> {
    node_optional_u64(node, keys)?
        .with_context(|| format!("node field {} is required", keys.join("/")))
}

fn node_u64(node: &Value, keys: &[&str], default: u64) -> Result<u64> {
    Ok(node_optional_u64(node, keys)?.unwrap_or(default))
}

fn node_optional_u64(node: &Value, keys: &[&str]) -> Result<Option<u64>> {
    let Some(value) = field(node, keys) else {
        return Ok(None);
    };
    match value {
        Value::Null => Ok(None),
        Value::Number(number) => number
            .as_u64()
            .map(Some)
            .context("number field is out of range"),
        Value::String(text) if text.trim().is_empty() => Ok(None),
        Value::String(text) => text
            .trim()
            .parse::<u64>()
            .map(Some)
            .with_context(|| format!("parse numeric node field {}", keys.join("/"))),
        _ => bail!("node field {} must be a number or string", keys.join("/")),
    }
}

fn node_optional_bandwidth_u64(node: &Value, keys: &[&str]) -> Result<Option<u64>> {
    let Some(value) = field(node, keys) else {
        return Ok(None);
    };
    match value {
        Value::Null => Ok(None),
        Value::Bool(false) => Ok(None),
        Value::Number(number) => number
            .as_u64()
            .map(Some)
            .context("bandwidth field is out of range"),
        Value::String(text) => {
            let text = text.trim();
            if text.is_empty() {
                return Ok(None);
            }
            let digits = text
                .chars()
                .take_while(|ch| ch.is_ascii_digit())
                .collect::<String>();
            ensure!(
                !digits.is_empty(),
                "parse bandwidth node field {}",
                keys.join("/")
            );
            let suffix = text[digits.len()..]
                .trim()
                .trim_start_matches(['/', '_'])
                .trim()
                .to_ascii_lowercase();
            ensure!(
                suffix.is_empty()
                    || matches!(suffix.as_str(), "m" | "mb" | "mbps" | "mib" | "mibps"),
                "unsupported bandwidth unit {suffix} in node field {}",
                keys.join("/")
            );
            digits
                .parse::<u64>()
                .map(Some)
                .with_context(|| format!("parse bandwidth node field {}", keys.join("/")))
        }
        _ => bail!("node field {} must be a number or string", keys.join("/")),
    }
}

fn node_bool(node: &Value, keys: &[&str], default: bool) -> Result<bool> {
    match field(node, keys) {
        Some(Value::Bool(value)) => Ok(*value),
        Some(Value::Number(number)) => number
            .as_u64()
            .map(|value| value != 0)
            .context("boolean node field is out of range"),
        Some(Value::String(text)) => match text.trim().to_ascii_lowercase().as_str() {
            "1" | "true" | "yes" | "on" | "tls" | "enabled" => Ok(true),
            "" | "0" | "false" | "no" | "off" | "none" | "disabled" => Ok(false),
            other => bail!("boolean node field has invalid value: {other}"),
        },
        Some(Value::Null) | None => Ok(default),
        Some(_) => bail!("node field {} must be a boolean", keys.join("/")),
    }
}

fn map_bool(map: &Map<String, Value>, keys: &[&str], default: bool) -> Result<bool> {
    match keys.iter().find_map(|key| map.get(*key)) {
        Some(Value::Bool(value)) => Ok(*value),
        Some(Value::Number(number)) => number
            .as_u64()
            .map(|value| value != 0)
            .context("boolean map field is out of range"),
        Some(Value::String(text)) => match text.trim().to_ascii_lowercase().as_str() {
            "1" | "true" | "yes" | "on" | "tls" | "enabled" => Ok(true),
            "" | "0" | "false" | "no" | "off" | "none" | "disabled" => Ok(false),
            other => bail!("boolean map field has invalid value: {other}"),
        },
        Some(Value::Null) | None => Ok(default),
        Some(_) => bail!("map field {} must be a boolean", keys.join("/")),
    }
}

fn tls_bool(
    node: &Value,
    tls: Option<&Map<String, Value>>,
    keys: &[&str],
    default: bool,
) -> Result<bool> {
    if let Some(opts) = tls {
        if keys.iter().any(|key| opts.contains_key(*key)) {
            map_bool(opts, keys, default)
        } else if keys == ["enabled"] {
            Ok(default)
        } else {
            node_bool(node, keys, default)
        }
    } else {
        let node_keys = if keys == ["enabled"] {
            &["tls"][..]
        } else {
            keys
        };
        node_bool(node, node_keys, default)
    }
}

fn node_string_list(node: &Value, keys: &[&str]) -> Result<Option<Vec<String>>> {
    match field(node, keys) {
        Some(Value::Array(values)) => {
            let mut output = Vec::new();
            for value in values {
                let value = value_to_string(value).with_context(|| {
                    format!("node field {} array item must be a string", keys.join("/"))
                })?;
                if !value.is_empty() {
                    output.push(value);
                }
            }
            Ok(Some(output))
        }
        Some(Value::String(text)) => Ok(Some(
            text.lines()
                .map(str::trim)
                .filter(|value| !value.is_empty())
                .map(str::to_string)
                .collect(),
        )),
        Some(_) => bail!("node field {} must be a string or array", keys.join("/")),
        None => Ok(None),
    }
}

fn value_to_path_list(value: &Value) -> Result<Vec<PathBuf>> {
    match value {
        Value::Array(values) => {
            let mut output = Vec::new();
            for value in values {
                let value = value_to_string(value).context("path list item must be a string")?;
                if !value.is_empty() {
                    output.push(PathBuf::from(value));
                }
            }
            Ok(output)
        }
        Value::String(text) => Ok(text
            .lines()
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(PathBuf::from)
            .collect()),
        _ => bail!("path list must be a string or array"),
    }
}

fn value_to_certificate_list(value: &Value) -> Result<Vec<String>> {
    match value {
        Value::Array(values) => {
            let mut output = Vec::new();
            for value in values {
                let value =
                    value_to_string(value).context("certificate list item must be a string")?;
                if !value.is_empty() {
                    output.push(value);
                }
            }
            Ok(output)
        }
        Value::String(text) => {
            let text = text.trim();
            Ok(if text.is_empty() {
                Vec::new()
            } else {
                vec![text.to_string()]
            })
        }
        _ => bail!("certificate list must be a string or array"),
    }
}

fn tls_ca_cert_paths(tls: Option<&Map<String, Value>>) -> Result<Vec<PathBuf>> {
    match tls.and_then(|opts| opts.get("certificate_path")) {
        Some(value) => value_to_path_list(value),
        None => Ok(Vec::new()),
    }
}

fn tls_ca_certificates(tls: Option<&Map<String, Value>>) -> Result<Vec<String>> {
    match tls.and_then(|opts| opts.get("certificate")) {
        Some(value) => value_to_certificate_list(value),
        None => Ok(Vec::new()),
    }
}

fn tls_disable_system_roots(tls: Option<&Map<String, Value>>) -> Result<bool> {
    tls.map(|opts| map_bool(opts, &["disable_system_roots"], false))
        .unwrap_or(Ok(false))
}

fn tls_pinned_cert_sha256(tls: Option<&Map<String, Value>>) -> Result<Vec<String>> {
    match tls.and_then(|opts| opts.get("pinned_cert_sha256")) {
        Some(value) => value_to_certificate_list(value),
        None => Ok(Vec::new()),
    }
}

fn node_alpn_list(node: &Value, tls: Option<&Map<String, Value>>) -> Result<Option<Vec<String>>> {
    let Some(value) = field(node, &["alpn"]).or_else(|| tls.and_then(|opts| opts.get("alpn")))
    else {
        return Ok(None);
    };
    Ok(Some(value_to_alpn_list(value)?).filter(|values| !values.is_empty()))
}

fn value_to_alpn_list(value: &Value) -> Result<Vec<String>> {
    match value {
        Value::Array(values) => {
            let mut output = Vec::new();
            for value in values {
                let value = value_to_string(value).context("ALPN list item must be a string")?;
                if !value.is_empty() {
                    output.push(value);
                }
            }
            Ok(output)
        }
        Value::String(text) => Ok(text
            .split(',')
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(str::to_string)
            .collect()),
        _ => bail!("ALPN must be a string or array"),
    }
}

fn node_duration_secs(node: &Value, keys: &[&str], default: u64) -> Result<u64> {
    let Some(value) = field(node, keys) else {
        return Ok(default);
    };
    match value {
        Value::Null => Ok(default),
        Value::Number(number) => number.as_u64().context("duration field is out of range"),
        Value::String(text) if text.trim().is_empty() => Ok(default),
        Value::String(text) => text
            .trim()
            .trim_end_matches('s')
            .parse::<u64>()
            .with_context(|| format!("parse duration node field {}", keys.join("/"))),
        _ => bail!("node field {} must be a number or string", keys.join("/")),
    }
}

fn map_headers(opts: Option<&Map<String, Value>>) -> Result<Vec<(String, String)>> {
    let Some(value) = opts.and_then(|opts| opts.get("headers")) else {
        return Ok(Vec::new());
    };
    if value.is_null() {
        return Ok(Vec::new());
    }
    let Value::Object(headers) = value else {
        bail!("transport headers must be an object");
    };
    let mut values = BTreeMap::new();
    for (key, value) in headers {
        let text = match value {
            Value::String(s) => s.trim().to_string(),
            Value::Array(items) => {
                let mut parts = Vec::with_capacity(items.len());
                for item in items {
                    let s = value_to_string(item)
                        .context("transport header array value must be a string")?;
                    if !s.is_empty() {
                        parts.push(s);
                    }
                }
                parts.join(", ")
            }
            _ => bail!("transport header value must be a string"),
        };
        values.insert(key.clone(), text);
    }
    Ok(values.into_iter().collect())
}

fn header_value(map: &Map<String, Value>, name: &str) -> Result<Option<String>> {
    Ok(map_headers(Some(map))?
        .into_iter()
        .find(|(key, _)| key.eq_ignore_ascii_case(name))
        .map(|(_, value)| value))
}

fn udp_over_tcp_enabled(node: &Value) -> Result<bool> {
    match field(node, &["udp_over_tcp", "udp-over-tcp"]) {
        Some(Value::Bool(value)) => Ok(*value),
        Some(Value::Object(map)) => map_bool(map, &["enabled"], false),
        Some(Value::Null) | None => Ok(false),
        Some(_) => bail!("node field udp_over_tcp must be a boolean or object"),
    }
}

fn naive_extra_headers(node: &Value) -> Result<Vec<(String, String)>> {
    let Some(headers) = object_field(node, &["extra_headers", "extra-headers"])? else {
        return Ok(Vec::new());
    };
    let mut values = BTreeMap::new();
    for (key, value) in headers {
        ensure!(
            !key.contains('\r') && !key.contains('\n'),
            "Naive extra header name contains newline"
        );
        let value = value_to_string(value).context("Naive extra header value must be a string")?;
        ensure!(
            !value.contains('\r') && !value.contains('\n'),
            "Naive extra header value contains newline"
        );
        values.insert(key.clone(), value);
    }
    Ok(values.into_iter().collect())
}

fn parse_mieru_hash(value: &str) -> Result<[u8; 32]> {
    let value = value.trim().trim_start_matches("0x");
    ensure!(
        value.len() == 64,
        "Mieru hashed password must be 32 bytes hex"
    );
    let mut output = [0u8; 32];
    for index in 0..32 {
        output[index] = u8::from_str_radix(&value[index * 2..index * 2 + 2], 16)
            .context("parse Mieru hashed password hex")?;
    }
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    use aerion::FluxTransportKind;
    use aerion::vless_transport::VlessTransportKind;

    #[test]
    fn parses_sing_box_vless_reality_transport() -> Result<()> {
        let node = serde_json::json!({
            "type": "vless",
            "host": "example.com",
            "port": 443,
            "uuid": "a3482e88-686a-4a58-8126-99c9df64b7bf",
            "flow": "xtls-rprx-vision",
            "packet_encoding": "xudp",
            "sni": "front.example.com",
            "client_fingerprint": "chrome",
            "tls": {
                "enabled": true,
                "server_name": "front.example.com",
                "insecure": true,
                "reality": {
                    "enabled": true,
                    "public_key": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                    "short_id": "a1b2"
                }
            },
            "transport": {
                "type": "grpc",
                "service_name": "TunService",
                "headers": { "Host": "edge.example.com" }
            }
        });
        let AerionProxyConfig::Vless(config) =
            node_to_proxy_config(&node, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected VLESS config")
        };
        assert_eq!(config.sni, "front.example.com");
        assert!(!config.tls);
        assert!(config.reality.is_some());
        assert_eq!(config.client_fingerprint, Some(UtlsFingerprint::Chrome));
        assert_eq!(config.transport.kind, VlessTransportKind::Grpc);
        assert_eq!(config.transport.path, "/TunService/Tun");
        assert_eq!(
            config.transport.request_host("example.com"),
            "edge.example.com"
        );
        Ok(())
    }

    #[test]
    fn parses_raw_vless() -> Result<()> {
        let node = serde_json::json!({
            "type": "vless",
            "host": "example.com",
            "port": 80,
            "uuid": "a3482e88-686a-4a58-8126-99c9df64b7bf",
            "tls": { "enabled": false }
        });
        let AerionProxyConfig::Vless(config) =
            node_to_proxy_config(&node, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected VLESS config")
        };
        assert!(!config.tls);
        assert!(config.reality.is_none());
        assert_eq!(config.server_port, 80);
        Ok(())
    }

    #[test]
    fn parses_shadowsocks_udp_over_tcp() -> Result<()> {
        let node = serde_json::json!({
            "type": "ss",
            "host": "example.com",
            "port": 8388,
            "cipher": "aes-128-gcm",
            "password": "secret",
            "network": "tcp",
            "udp_over_tcp": { "enabled": true }
        });
        let AerionProxyConfig::Shadowsocks(config) =
            node_to_proxy_config(&node, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected Shadowsocks config")
        };
        assert!(config.udp);
        assert!(config.udp_over_tcp);
        Ok(())
    }

    #[test]
    fn parses_http_proxy() -> Result<()> {
        let node = serde_json::json!({
            "type": "http",
            "host": "proxy.example.com",
            "port": 8080,
            "username": "user",
            "password": "secret",
            "extra_headers": {
                "X-Aerion": "example"
            }
        });
        let AerionProxyConfig::HttpProxy(config) =
            node_to_proxy_config(&node, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected HTTP proxy config")
        };
        assert_eq!(config.server_host, "proxy.example.com");
        assert_eq!(config.server_port, 8080);
        assert_eq!(config.username, "user");
        assert_eq!(config.password, "secret");
        assert!(!config.tls);
        assert_eq!(
            config.extra_headers,
            vec![("X-Aerion".to_string(), "example".to_string())]
        );
        Ok(())
    }

    #[test]
    fn parses_socks_proxy() -> Result<()> {
        let node = serde_json::json!({
            "type": "socks5",
            "host": "proxy.example.com",
            "port": 1080,
            "username": "user",
            "password": "secret",
            "udp": true
        });
        let AerionProxyConfig::SocksProxy(config) =
            node_to_proxy_config(&node, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected SOCKS proxy config")
        };
        assert_eq!(config.server_host, "proxy.example.com");
        assert_eq!(config.server_port, 1080);
        assert_eq!(config.username, "user");
        assert_eq!(config.password, "secret");
        assert!(config.udp);
        Ok(())
    }

    #[test]
    fn parses_vmess_websocket() -> Result<()> {
        let node = serde_json::json!({
            "type": "vmess",
            "host": "example.com",
            "port": 80,
            "uuid": "a3482e88-686a-4a58-8126-99c9df64b7bf",
            "packet_encoding": "packetaddr",
            "transport": {
                "type": "ws",
                "path": "/vmess",
                "headers": { "Host": "edge.example.com" }
            }
        });
        let AerionProxyConfig::Vmess(config) =
            node_to_proxy_config(&node, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected VMess config")
        };
        assert!(!config.tls);
        assert_eq!(config.packet_encoding, "packetaddr");
        assert_eq!(config.transport.kind, VlessTransportKind::WebSocket);
        assert_eq!(config.transport.path, "/vmess");
        assert_eq!(
            config.transport.request_host("example.com"),
            "edge.example.com"
        );
        Ok(())
    }

    #[test]
    fn parses_vmess_xudp() -> Result<()> {
        let node = serde_json::json!({
            "type": "vmess",
            "host": "example.com",
            "port": 80,
            "uuid": "a3482e88-686a-4a58-8126-99c9df64b7bf",
            "packet_encoding": "xudp"
        });
        let AerionProxyConfig::Vmess(config) =
            node_to_proxy_config(&node, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected VMess config")
        };
        assert!(!config.tls);
        assert_eq!(config.packet_encoding, "xudp");
        Ok(())
    }

    #[test]
    fn parses_trojan_websocket() -> Result<()> {
        let node = serde_json::json!({
            "type": "trojan",
            "host": "example.com",
            "port": 443,
            "password": "secret",
            "transport": {
                "type": "ws",
                "path": "/trojan",
                "headers": { "Host": "edge.example.com" }
            }
        });
        let AerionProxyConfig::Trojan(config) =
            node_to_proxy_config(&node, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected Trojan config")
        };
        assert_eq!(config.transport.kind, VlessTransportKind::WebSocket);
        assert_eq!(config.transport.path, "/trojan");
        assert_eq!(
            config.transport.request_host("example.com"),
            "edge.example.com"
        );
        Ok(())
    }

    #[test]
    fn parses_sing_box_hysteria2_tls() -> Result<()> {
        let node = serde_json::json!({
            "type": "hysteria2",
            "host": "hy2.example.com",
            "port": 443,
            "password": "secret",
            "up": "123 mbps",
            "down": 456,
            "sni": "front.example.com",
            "tls": {
                "enabled": true,
                "server_name": "front.example.com",
                "insecure": true,
                "disable_system_roots": true,
                "pinned_cert_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "certificate": "hy2-inline-ca"
            }
        });
        let AerionProxyConfig::Hysteria2(config) =
            node_to_proxy_config(&node, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected Hysteria2 config")
        };
        assert_eq!(config.sni, "front.example.com");
        assert!(config.insecure);
        assert_eq!(config.upload_bandwidth, Some(123));
        assert_eq!(config.download_bandwidth, Some(456));
        assert_eq!(config.ca_certificates, vec!["hy2-inline-ca"]);
        assert!(config.disable_system_roots);
        assert_eq!(
            config.pinned_cert_sha256,
            vec!["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
        );
        Ok(())
    }

    #[test]
    fn parses_client_tls_custom_roots() -> Result<()> {
        let anytls = serde_json::json!({
            "type": "anytls",
            "host": "anytls.example.com",
            "port": 443,
            "password": "secret",
            "client_fingerprint": "chrome",
            "tls": {
                "enabled": true,
                "certificate_path": "anytls-ca.pem",
                "disable_system_roots": true,
                "pinned_cert_sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "certificate": "anytls-inline-ca"
            }
        });
        let AerionProxyConfig::AnyTls(config) =
            node_to_proxy_config(&anytls, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected AnyTLS config")
        };
        assert_eq!(config.ca_cert_paths, vec![PathBuf::from("anytls-ca.pem")]);
        assert_eq!(config.ca_certificates, vec!["anytls-inline-ca"]);
        assert!(config.disable_system_roots);
        assert_eq!(
            config.pinned_cert_sha256,
            vec!["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]
        );
        assert_eq!(config.client_fingerprint, Some(UtlsFingerprint::Chrome));

        let vless = serde_json::json!({
            "type": "vless",
            "host": "vless.example.com",
            "port": 443,
            "uuid": "a3482e88-686a-4a58-8126-99c9df64b7bf",
            "tls": {
                "enabled": true,
                "certificate_path": ["vless-ca.pem"],
                "certificate": ["vless-inline-ca"]
            }
        });
        let AerionProxyConfig::Vless(config) =
            node_to_proxy_config(&vless, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected VLESS config")
        };
        assert_eq!(config.ca_cert_paths, vec![PathBuf::from("vless-ca.pem")]);
        assert_eq!(config.ca_certificates, vec!["vless-inline-ca"]);

        let vmess = serde_json::json!({
            "type": "vmess",
            "host": "vmess.example.com",
            "port": 443,
            "uuid": "a3482e88-686a-4a58-8126-99c9df64b7bf",
            "tls": {
                "enabled": true,
                "certificate_path": "vmess-ca.pem",
                "certificate": "vmess-inline-ca"
            }
        });
        let AerionProxyConfig::Vmess(config) =
            node_to_proxy_config(&vmess, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected VMess config")
        };
        assert_eq!(config.ca_cert_paths, vec![PathBuf::from("vmess-ca.pem")]);
        assert_eq!(config.ca_certificates, vec!["vmess-inline-ca"]);

        let trojan = serde_json::json!({
            "type": "trojan",
            "host": "trojan.example.com",
            "port": 443,
            "password": "secret",
            "tls": {
                "enabled": true,
                "certificate_path": "trojan-ca.pem",
                "certificate": "trojan-inline-ca"
            }
        });
        let AerionProxyConfig::Trojan(config) =
            node_to_proxy_config(&trojan, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected Trojan config")
        };
        assert_eq!(config.ca_cert_paths, vec![PathBuf::from("trojan-ca.pem")]);
        assert_eq!(config.ca_certificates, vec!["trojan-inline-ca"]);

        let tuic = serde_json::json!({
            "type": "tuic",
            "host": "tuic.example.com",
            "port": 443,
            "uuid": "a3482e88-686a-4a58-8126-99c9df64b7bf",
            "password": "secret",
            "tls": {
                "enabled": true,
                "certificate_path": "tuic-ca.pem",
                "certificate": "tuic-inline-ca"
            }
        });
        let AerionProxyConfig::Tuic(config) =
            node_to_proxy_config(&tuic, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected TUIC config")
        };
        assert_eq!(config.ca_cert_paths, vec![PathBuf::from("tuic-ca.pem")]);
        assert_eq!(config.ca_certificates, vec!["tuic-inline-ca"]);
        Ok(())
    }

    #[test]
    fn parses_naive_quic_congestion_control() -> Result<()> {
        let node = serde_json::json!({
            "type": "naive",
            "host": "naive.example.com",
            "port": 443,
            "username": "user",
            "password": "secret",
            "quic": true,
            "quic_congestion_control": "reno",
            "tls": {
                "enabled": true,
                "certificate_path": ["ca.pem", "backup-ca.pem"],
                "certificate": "naive-inline-ca"
            }
        });
        let AerionProxyConfig::Naive(config) =
            node_to_proxy_config(&node, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected Naive config")
        };
        assert!(config.quic);
        assert_eq!(config.quic_congestion_control, "reno");
        assert_eq!(
            config.ca_cert_paths,
            vec![PathBuf::from("ca.pem"), PathBuf::from("backup-ca.pem")]
        );
        assert_eq!(config.ca_certificates, vec!["naive-inline-ca"]);
        Ok(())
    }

    #[test]
    fn ignores_ip_literal_sni_when_server_name_is_available() -> Result<()> {
        let anytls = serde_json::json!({
            "type": "anytls",
            "host": "2001:db8::1",
            "port": 443,
            "password": "secret",
            "sni": "[2001:db8::1]",
            "server-name": "edge.example.com",
            "skip-cert-verify": true
        });
        let AerionProxyConfig::AnyTls(config) =
            node_to_proxy_config(&anytls, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected AnyTLS config")
        };
        assert_eq!(config.server_host, "2001:db8::1");
        assert_eq!(config.sni, "edge.example.com");
        assert!(config.insecure);

        let hysteria2 = serde_json::json!({
            "type": "hysteria2",
            "host": "2001:db8::2",
            "port": 443,
            "password": "secret",
            "sni": "2001:db8::2",
            "skip_cert_verify": true,
            "tls": {
                "server_name": "hy2.example.com"
            }
        });
        let AerionProxyConfig::Hysteria2(config) =
            node_to_proxy_config(&hysteria2, "127.0.0.1:1081".parse()?)?
        else {
            bail!("expected Hysteria2 config")
        };
        assert_eq!(config.server_host, "2001:db8::2");
        assert_eq!(config.sni, "hy2.example.com");
        assert!(config.insecure);
        Ok(())
    }

    #[test]
    fn parses_builtin_route_nodes() -> Result<()> {
        let direct = serde_json::json!({ "type": "direct", "name": "direct-out" });
        let AerionProxyConfig::Route(config) =
            node_to_proxy_config(&direct, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected route config")
        };
        assert_eq!(config.default, RouteDecision::Direct);

        let block = serde_json::json!({ "type": "block", "name": "block-out" });
        let AerionProxyConfig::Route(config) =
            node_to_proxy_config(&block, "127.0.0.1:1081".parse()?)?
        else {
            bail!("expected route config")
        };
        assert_eq!(config.default, RouteDecision::Block);
        Ok(())
    }

    #[test]
    fn parses_mieru_transport_and_hash() -> Result<()> {
        let node = serde_json::json!({
            "type": "mieru",
            "host": "mieru.example.com",
            "port": 8964,
            "username": "alice",
            "hashed_password": "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            "transport": "udp",
            "mtu": 1400
        });
        let AerionProxyConfig::Mieru(config) =
            node_to_proxy_config(&node, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected Mieru config")
        };
        assert_eq!(config.server_host, "mieru.example.com");
        assert_eq!(config.server_port, 8964);
        assert_eq!(config.username, "alice");
        let hashed_password = config.hashed_password.context("hashed password")?;
        assert_eq!(hashed_password[0], 0);
        assert_eq!(hashed_password[31], 31);
        assert_eq!(config.transport, MieruTransport::Udp);
        assert_eq!(config.mtu, 1400);
        assert!(config.traffic_pattern.is_none());
        Ok(())
    }

    #[test]
    fn accepts_shadowsocks_and_hysteria_protocol_aliases() -> Result<()> {
        let ss = serde_json::json!({
            "type": "shadowsocks",
            "host": "ss.example.com",
            "port": 8388,
            "cipher": "aes-128-gcm",
            "password": "secret"
        });
        assert!(matches!(
            node_to_proxy_config(&ss, "127.0.0.1:1080".parse()?)?,
            AerionProxyConfig::Shadowsocks(_)
        ));
        let hy = serde_json::json!({
            "type": "hysteria",
            "host": "hy.example.com",
            "port": 443,
            "password": "secret",
            "sni": "hy.example.com"
        });
        assert!(matches!(
            node_to_proxy_config(&hy, "127.0.0.1:1081".parse()?)?,
            AerionProxyConfig::Hysteria2(_)
        ));
        Ok(())
    }

    #[test]
    fn rejects_mieru_without_username() {
        let node = serde_json::json!({
            "type": "mieru",
            "host": "mieru.example.com",
            "port": 8964,
            "password": "secret"
        });
        let error = node_to_proxy_config(&node, "127.0.0.1:1080".parse().unwrap())
            .err()
            .expect("invalid configuration must fail");
        assert!(error.to_string().contains("username is required"));
    }

    #[test]
    fn parses_flux_default_and_manual_multi_channel_nodes() -> Result<()> {
        let pub_key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
        let auto_node = serde_json::json!({
            "type": "flux",
            "name": "Flux-Auto",
            "host": "edge.example.com",
            "port": 443,
            "uuid": "a3482e88-686a-4a58-8126-99c9df64b7bf",
            "servername": "www.microsoft.com",
            "client-fingerprint": "chrome",
            "reality-opts": {
                "public-key": pub_key,
                "short-id": "01020304"
            },
            "resolved_v4": ["203.0.113.10"],
            "resolved_v6": ["2001:db8::10"]
        });
        let AerionProxyConfig::Flux(auto_cfg) =
            node_to_proxy_config(&auto_node, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected Flux config")
        };
        assert_eq!(auto_cfg.channels.len(), 2);
        assert_eq!(auto_cfg.channels[0].transport, FluxTransportKind::Tcp);
        assert_eq!(auto_cfg.channels[1].transport, FluxTransportKind::Udp);
        assert_eq!(auto_cfg.channels[0].sni, "www.microsoft.com");
        assert_eq!(auto_cfg.all_static_ips().len(), 2);

        let manual_node = serde_json::json!({
            "type": "flux",
            "name": "Flux-Manual",
            "host": "edge.example.com",
            "port": 443,
            "key": "user-uuid",
            "client_supported": true,
            "flux": {
                "ip_strategy": "dual",
                "standby_channels": 3,
                "security": "reality",
                "public_key": pub_key,
                "short_id": "a1b2",
                "channels": [
                    {
                        "id": "direct-reality",
                        "connect_ports": "443",
                        "transport": "tcp",
                        "security": "reality",
                        "sni": "www.microsoft.com",
                        "resolved_v4": ["198.51.100.1"]
                    },
                    {
                        "id": "cdn-ws",
                        "connect_host": "cdn.example.com",
                        "connect_ports": "443",
                        "transport": "http",
                        "http_mode": "ws",
                        "http_path": "/flux-ws",
                        "security": "tls",
                        "resolved_v6": ["2001:db8::2"]
                    },
                    {
                        "id": "udp-obfs",
                        "connect_ports": "21000-21002",
                        "transport": "udp",
                        "security": "obfs",
                        "obfs_key": "flux-obfs-secret",
                        "role": "backup",
                        "weight": 80,
                        "resolved_v4": ["198.51.100.9"]
                    }
                ]
            }
        });
        let AerionProxyConfig::Flux(manual_cfg) =
            node_to_proxy_config(&manual_node, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected Flux manual config")
        };
        assert_eq!(manual_cfg.options.standby_channels, 3);
        assert_eq!(manual_cfg.channels.len(), 3);
        assert_eq!(manual_cfg.channels[1].http_path, "/flux-ws");
        assert_eq!(manual_cfg.channels[2].ports, vec![21000, 21001, 21002]);
        assert_eq!(manual_cfg.all_static_ips().len(), 3);

        let invalid_ports = serde_json::json!({
            "type": "flux",
            "host": "edge.example.com",
            "port": 443,
            "key": "user-uuid",
            "security": "obfs",
            "obfs_key": "flux-obfs-secret",
            "channels": [{ "connect_ports": "10000-10100", "transport": "udp" }]
        });
        assert!(node_to_proxy_config(&invalid_ports, "127.0.0.1:1080".parse()?).is_err());
        Ok(())
    }

    #[test]
    fn parses_clash_boolean_tls_and_kebab_fields_across_protocols() -> Result<()> {
        let listen: SocketAddr = "127.0.0.1:1080".parse()?;
        let pub_key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

        let anytls = serde_json::json!({
            "name": "JP Osaka Oracle",
            "type": "anytls",
            "server": "osa.example.com",
            "host": "osa.example.com",
            "port": 443,
            "password": "secret-uuid",
            "tls": true,
            "sni": null,
            "servername": "sni.example.com",
            "skip-cert-verify": true,
            "client-fingerprint": "chrome",
            "idle-session-check-interval": "45s"
        });
        let AerionProxyConfig::AnyTls(anytls_cfg) = node_to_proxy_config(&anytls, listen)? else {
            bail!("expected AnyTLS")
        };
        assert_eq!(anytls_cfg.server_host, "osa.example.com");
        assert_eq!(anytls_cfg.sni, "sni.example.com");
        assert!(anytls_cfg.insecure);
        assert_eq!(anytls_cfg.client_fingerprint, Some(UtlsFingerprint::Chrome));
        assert_eq!(anytls_cfg.heartbeat_interval_secs, 45);

        let anytls_no_tls = serde_json::json!({
            "type": "anytls",
            "server": "osa.example.com",
            "port": 443,
            "password": "secret-uuid",
            "tls": false
        });
        assert!(node_to_proxy_config(&anytls_no_tls, listen).is_err());

        let vless_reality_grpc = serde_json::json!({
            "type": "vless",
            "server": "edge.example.com",
            "port": 443,
            "uuid": "a3482e88-686a-4a58-8126-99c9df64b7bf",
            "tls": true,
            "servername": "www.microsoft.com",
            "client-fingerprint": "chrome",
            "packet-encoding": "xudp",
            "smux": { "enabled": true },
            "reality-opts": {
                "public-key": pub_key
            },
            "network": "grpc",
            "grpc-opts": {
                "grpc-service-name": "GunService"
            }
        });
        let AerionProxyConfig::Vless(vless_cfg) =
            node_to_proxy_config(&vless_reality_grpc, listen)?
        else {
            bail!("expected VLESS")
        };
        assert!(!vless_cfg.tls);
        assert!(vless_cfg.reality.is_some());
        assert_eq!(vless_cfg.sni, "www.microsoft.com");
        assert_eq!(vless_cfg.client_fingerprint, Some(UtlsFingerprint::Chrome));
        assert_eq!(vless_cfg.packet_encoding, "xudp");
        assert!(vless_cfg.mux);
        assert_eq!(vless_cfg.transport.kind, VlessTransportKind::Grpc);
        assert_eq!(vless_cfg.transport.path, "/GunService/Tun");

        let trojan_httpupgrade = serde_json::json!({
            "type": "trojan",
            "server": "trojan.example.com",
            "port": 443,
            "password": "secret",
            "tls": true,
            "network": "ws",
            "ws-opts": {
                "path": "/up",
                "headers": { "Host": "cdn.example.com" },
                "v2ray-http-upgrade": true
            }
        });
        let AerionProxyConfig::Trojan(trojan_cfg) =
            node_to_proxy_config(&trojan_httpupgrade, listen)?
        else {
            bail!("expected Trojan")
        };
        assert_eq!(trojan_cfg.transport.kind, VlessTransportKind::HttpUpgrade);
        assert_eq!(trojan_cfg.transport.path, "/up");
        assert_eq!(
            trojan_cfg.transport.request_host("trojan.example.com"),
            "cdn.example.com"
        );

        let vmess_h2 = serde_json::json!({
            "type": "vmess",
            "server": "vmess.example.com",
            "port": 443,
            "uuid": "a3482e88-686a-4a58-8126-99c9df64b7bf",
            "alterId": 0,
            "tls": true,
            "network": "h2",
            "h2-opts": {
                "path": ["/h2"],
                "host": ["h2.example.com"]
            }
        });
        let AerionProxyConfig::Vmess(vmess_cfg) = node_to_proxy_config(&vmess_h2, listen)? else {
            bail!("expected VMess")
        };
        assert!(vmess_cfg.tls);
        assert_eq!(vmess_cfg.transport.kind, VlessTransportKind::Http2);
        assert_eq!(vmess_cfg.transport.path, "/h2");
        assert_eq!(
            vmess_cfg.transport.request_host("vmess.example.com"),
            "h2.example.com"
        );

        let hy2 = serde_json::json!({
            "type": "hysteria2",
            "server": "hy2.example.com",
            "port": 443,
            "password": "secret",
            "tls": true,
            "obfs": "salamander",
            "obfs-password": "obfs-secret",
            "up": 100,
            "down": 200
        });
        let AerionProxyConfig::Hysteria2(hy2_cfg) = node_to_proxy_config(&hy2, listen)? else {
            bail!("expected Hysteria2")
        };
        assert_eq!(hy2_cfg.obfs.as_deref(), Some("salamander"));
        assert_eq!(hy2_cfg.obfs_password.as_deref(), Some("obfs-secret"));

        let tuic = serde_json::json!({
            "type": "tuic",
            "server": "tuic.example.com",
            "port": 443,
            "uuid": "a3482e88-686a-4a58-8126-99c9df64b7bf",
            "password": "secret",
            "tls": true,
            "congestion-controller": "bbr",
            "udp-relay-mode": "quic"
        });
        let AerionProxyConfig::Tuic(tuic_cfg) = node_to_proxy_config(&tuic, listen)? else {
            bail!("expected TUIC")
        };
        assert_eq!(tuic_cfg.congestion_control, "bbr");
        assert_eq!(tuic_cfg.udp_relay_mode, "quic");

        let ss = serde_json::json!({
            "type": "ss",
            "server": "ss.example.com",
            "port": 8388,
            "method": "aes-128-gcm",
            "password": "secret",
            "udp-over-tcp": true
        });
        let AerionProxyConfig::Shadowsocks(ss_cfg) = node_to_proxy_config(&ss, listen)? else {
            bail!("expected Shadowsocks")
        };
        assert!(ss_cfg.udp_over_tcp);
        Ok(())
    }
}
