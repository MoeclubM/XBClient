use crate::aerion_protocol::AerionProxyConfig;
use aerion::padding::PaddingScheme;
use aerion::vless_transport::VlessTransportConfig;
use aerion::{
    ClientConfig, Hysteria2ClientConfig, MieruClientConfig, MieruTrafficPattern, MieruTransport,
    NaiveClientConfig, RealityClientConfig, ShadowsocksClientConfig, TrojanClientConfig,
    TuicClientConfig, UtlsFingerprint, VlessClientConfig, VmessClientConfig,
    ensure_vmess_packet_encoding,
};
use anyhow::{Context, Result, bail, ensure};
use serde_json::{Map, Value};
use std::collections::BTreeMap;
use std::net::SocketAddr;

pub fn node_to_proxy_config(node: &Value, listen: SocketAddr) -> Result<AerionProxyConfig> {
    let protocol = node_protocol(node)?;
    match protocol.as_str() {
        "anytls" => anytls_config(node, listen).map(AerionProxyConfig::AnyTls),
        "hysteria2" => hysteria2_config(node, listen).map(AerionProxyConfig::Hysteria2),
        "trojan" => trojan_config(node, listen).map(AerionProxyConfig::Trojan),
        "vless" => vless_config(node, listen).map(AerionProxyConfig::Vless),
        "vmess" => vmess_config(node, listen).map(AerionProxyConfig::Vmess),
        "mieru" => mieru_config(node, listen).map(AerionProxyConfig::Mieru),
        "naive" => naive_config(node, listen).map(AerionProxyConfig::Naive),
        "tuic" => tuic_config(node, listen).map(AerionProxyConfig::Tuic),
        "ss" => shadowsocks_config(node, listen).map(AerionProxyConfig::Shadowsocks),
        other => bail!("unsupported Aerion node protocol: {other}"),
    }
}

fn anytls_config(node: &Value, listen: SocketAddr) -> Result<ClientConfig> {
    let server_host = node_string(node, &["host", "server", "address"])?;
    let tls = object_field(node, &["tls"]);
    Ok(ClientConfig {
        listen,
        server_port: node_port(node, &["port", "server_port", "server-port"])?,
        password: node_string(node, &["password", "passwd"])?,
        sni: tls
            .and_then(|opts| map_string(opts, &["server_name", "server-name", "serverName"]))
            .or_else(|| node_optional_string(node, &["sni", "servername", "server-name", "peer"]))
            .unwrap_or_else(|| server_host.clone()),
        server_host,
        insecure: tls
            .map(|opts| {
                map_bool(
                    opts,
                    &["insecure", "skip-cert-verify", "skip_cert_verify"],
                    false,
                )
            })
            .unwrap_or_else(|| {
                node_bool(
                    node,
                    &["insecure", "skip-cert-verify", "allowInsecure"],
                    false,
                )
            }),
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
    let tls = object_field(node, &["tls"]);
    Ok(Hysteria2ClientConfig {
        listen,
        server_port: node_port(node, &["port", "server_port", "server-port"])?,
        password: node_string(node, &["password", "auth"])?,
        sni: tls
            .and_then(|opts| map_string(opts, &["server_name", "server-name", "serverName"]))
            .or_else(|| node_optional_string(node, &["sni", "servername", "server-name", "peer"]))
            .unwrap_or_else(|| server_host.clone()),
        server_host,
        insecure: tls
            .map(|opts| {
                map_bool(
                    opts,
                    &["insecure", "skip-cert-verify", "skip_cert_verify"],
                    false,
                )
            })
            .unwrap_or_else(|| {
                node_bool(
                    node,
                    &["insecure", "skip-cert-verify", "allowInsecure"],
                    false,
                )
            }),
        certificate_fingerprint: tls
            .and_then(|opts| {
                map_string(
                    opts,
                    &[
                        "fingerprint",
                        "certificate_fingerprint",
                        "certificate-fingerprint",
                    ],
                )
            })
            .or_else(|| {
                node_optional_string(
                    node,
                    &[
                        "fingerprint",
                        "certificate_fingerprint",
                        "certificate-fingerprint",
                    ],
                )
            }),
        obfs: node_optional_string(node, &["obfs"])
            .or_else(|| object_field(node, &["obfs"]).and_then(|opts| map_string(opts, &["type"]))),
        obfs_password: node_optional_string(
            node,
            &["obfs-password", "obfs_password", "obfsPassword"],
        )
        .or_else(|| obfs_nested_password(node)),
        download_bandwidth: node_optional_bandwidth_u64(
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
    let transport = vless_transport(node)?;
    let tls = object_field(node, &["tls"]);
    ensure!(
        tls.map(|opts| map_bool(opts, &["enabled"], true))
            .unwrap_or_else(|| node_bool(node, &["tls"], true)),
        "Aerion Trojan client requires TLS"
    );
    let server_host = node_string(node, &["server", "host", "address"])?;
    Ok(TrojanClientConfig {
        listen,
        server_port: node_port(node, &["port", "server_port", "server-port"])?,
        password: node_string(node, &["password", "passwd"])?,
        sni: tls
            .and_then(|opts| map_string(opts, &["server_name", "server-name", "serverName"]))
            .or_else(|| node_optional_string(node, &["sni", "servername", "server-name", "peer"]))
            .unwrap_or_else(|| server_host.clone()),
        server_host,
        insecure: tls
            .map(|opts| {
                map_bool(
                    opts,
                    &["insecure", "skip-cert-verify", "skip_cert_verify"],
                    false,
                )
            })
            .unwrap_or_else(|| {
                node_bool(
                    node,
                    &["insecure", "skip-cert-verify", "allowInsecure"],
                    false,
                )
            }),
        udp: node_bool(node, &["udp"], true),
        client_fingerprint: client_fingerprint(node)?,
        transport,
    })
}

fn vless_config(node: &Value, listen: SocketAddr) -> Result<VlessClientConfig> {
    let server_host = node_string(node, &["server", "host", "address"])?;
    let tls = object_field(node, &["tls"]);
    let reality = reality_config(node)?;
    let tls_enabled = reality.is_none()
        && tls
            .map(|opts| map_bool(opts, &["enabled"], true))
            .unwrap_or_else(|| node_bool(node, &["tls"], true));
    let client_fingerprint = client_fingerprint(node)?;
    ensure!(
        tls_enabled || reality.is_some() || client_fingerprint.is_none(),
        "Aerion VLESS client cannot use client fingerprint without TLS or REALITY"
    );
    Ok(VlessClientConfig {
        listen,
        server_port: node_port(node, &["port", "server_port", "server-port"])?,
        user_id: node_string(node, &["uuid", "id", "user_id"])?,
        tls: tls_enabled,
        sni: tls
            .and_then(|opts| map_string(opts, &["server_name", "server-name", "serverName"]))
            .or_else(|| node_optional_string(node, &["sni", "servername", "server-name", "peer"]))
            .unwrap_or_else(|| server_host.clone()),
        server_host,
        insecure: if tls_enabled {
            tls.map(|opts| {
                map_bool(
                    opts,
                    &["insecure", "skip-cert-verify", "skip_cert_verify"],
                    false,
                )
            })
            .unwrap_or_else(|| {
                node_bool(
                    node,
                    &["insecure", "skip-cert-verify", "allowInsecure"],
                    false,
                )
            })
        } else {
            false
        },
        flow: node_optional_string(node, &["flow"]).unwrap_or_default(),
        packet_encoding: node_optional_string(node, &["packet-encoding", "packet_encoding"])
            .unwrap_or_default(),
        mux: mux_enabled(node),
        udp: node_bool(node, &["udp"], true),
        client_fingerprint,
        reality,
        transport: vless_transport(node)?,
    })
}

fn vmess_config(node: &Value, listen: SocketAddr) -> Result<VmessClientConfig> {
    ensure!(
        node_optional_u64(node, &["alterId", "alter_id"])?.unwrap_or(0) == 0,
        "legacy VMess alterId is not supported by Aerion"
    );
    let server_host = node_string(node, &["server", "host", "address"])?;
    let transport = vless_transport(node)?;
    let tls_options = object_field(node, &["tls"]);
    let security = node_optional_string(node, &["security"]);
    let tls = tls_options
        .map(|opts| map_bool(opts, &["enabled"], true))
        .unwrap_or_else(|| node_bool(node, &["tls"], false))
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
    let client_fingerprint = client_fingerprint(node)?;
    ensure!(
        tls || client_fingerprint.is_none(),
        "Aerion VMess client cannot use client fingerprint without TLS"
    );
    let packet_encoding = node_optional_string(
        node,
        &["packet-encoding", "packet_encoding", "packetEncoding"],
    )
    .unwrap_or_default();
    ensure_vmess_packet_encoding(&packet_encoding)?;
    Ok(VmessClientConfig {
        listen,
        server_port: node_port(node, &["port", "server_port", "server-port"])?,
        user_id: node_string(node, &["uuid", "id", "user_id"])?,
        security: cipher,
        packet_encoding,
        udp: node_bool(node, &["udp"], false),
        tls,
        sni: tls_options
            .and_then(|opts| map_string(opts, &["server_name", "server-name", "serverName"]))
            .or_else(|| node_optional_string(node, &["sni", "servername", "server-name", "peer"]))
            .unwrap_or_else(|| server_host.clone()),
        server_host,
        insecure: if tls {
            tls_options
                .map(|opts| {
                    map_bool(
                        opts,
                        &["insecure", "skip-cert-verify", "skip_cert_verify"],
                        false,
                    )
                })
                .unwrap_or_else(|| {
                    node_bool(
                        node,
                        &["insecure", "skip-cert-verify", "allowInsecure"],
                        false,
                    )
                })
        } else {
            false
        },
        client_fingerprint,
        transport,
    })
}

fn mieru_config(node: &Value, listen: SocketAddr) -> Result<MieruClientConfig> {
    let username = node_optional_string(node, &["username", "user", "uuid", "id"]);
    let password = node_optional_string(node, &["password", "passwd"])
        .or_else(|| node_optional_string(node, &["uuid", "id", "username", "user"]));
    let username = username
        .or_else(|| password.clone())
        .unwrap_or_else(|| "default".to_string());
    let hashed_password = node_optional_string(
        node,
        &[
            "hashed_password",
            "hashed-password",
            "password_hash",
            "password-hash",
            "passwordHash",
        ],
    )
    .map(|value| parse_mieru_hash(&value))
    .transpose()?;
    ensure!(
        hashed_password.is_some()
            || password
                .as_deref()
                .map(|value| !value.trim().is_empty())
                .unwrap_or(false),
        "node field password/passwd/uuid/id is required for Mieru"
    );
    Ok(MieruClientConfig {
        listen,
        server_host: node_string(node, &["server", "host", "address"])?,
        server_port: node_port(node, &["port", "server_port", "server-port"])?,
        username,
        password: password.unwrap_or_default(),
        hashed_password,
        mtu: node_u64(node, &["mtu"], 1500)? as usize,
        transport: MieruTransport::parse(
            node_optional_string(node, &["transport", "underlay"])
                .or_else(|| {
                    object_field(node, &["transport"])
                        .and_then(|opts| map_string(opts, &["type", "protocol"]))
                })
                .unwrap_or_else(|| "tcp".to_string())
                .as_str(),
        )?,
        traffic_pattern: MieruTrafficPattern::parse_pair(
            node_optional_string(
                node,
                &["traffic-pattern", "traffic_pattern", "trafficPattern"],
            )
            .as_deref(),
            node_optional_string(node, &["nonce-pattern", "nonce_pattern", "noncePattern"])
                .as_deref(),
        )
        .context("parse Mieru traffic pattern")?,
    })
}

fn naive_config(node: &Value, listen: SocketAddr) -> Result<NaiveClientConfig> {
    let tls = object_field(node, &["tls"]);
    ensure!(
        tls.map(|opts| map_bool(opts, &["enabled"], true))
            .unwrap_or_else(|| node_bool(node, &["tls"], true)),
        "Naive client requires HTTPS/TLS proxy"
    );
    let server_host = node_string(node, &["server", "host", "address"])?;
    let server_port =
        node_optional_u64(node, &["port", "server_port", "server-port"])?.unwrap_or(443);
    ensure!(
        server_port > 0 && server_port <= u16::MAX as u64,
        "node port is out of range"
    );
    Ok(NaiveClientConfig {
        listen,
        server_host: server_host.clone(),
        server_port: server_port as u16,
        username: node_optional_string(node, &["username", "user"]).unwrap_or_default(),
        password: node_optional_string(node, &["password", "passwd", "pass"]).unwrap_or_default(),
        sni: tls
            .and_then(|opts| map_string(opts, &["server_name", "server-name", "serverName"]))
            .or_else(|| node_optional_string(node, &["sni", "servername", "server-name", "peer"]))
            .unwrap_or(server_host),
        insecure: tls
            .map(|opts| {
                map_bool(
                    opts,
                    &["insecure", "skip-cert-verify", "skip_cert_verify"],
                    false,
                )
            })
            .unwrap_or_else(|| {
                node_bool(
                    node,
                    &["insecure", "skip-cert-verify", "allowInsecure"],
                    false,
                )
            }),
        extra_headers: naive_extra_headers(node)?,
        udp_over_tcp: udp_over_tcp_enabled(node),
        quic: node_optional_string(node, &["type", "protocol"])
            .map(|protocol| protocol.eq_ignore_ascii_case("naive+quic"))
            .unwrap_or(false)
            || node_bool(node, &["quic", "http3", "h3"], false)
            || node_optional_string(node, &["network"])
                .map(|network| {
                    matches!(
                        network.to_ascii_lowercase().as_str(),
                        "quic" | "h3" | "http3"
                    )
                })
                .unwrap_or(false),
        quic_congestion_control: node_optional_string(
            node,
            &[
                "quic-congestion-control",
                "quic_congestion_control",
                "quicCongestionControl",
                "congestion-control",
                "congestion_control",
            ],
        )
        .unwrap_or_else(aerion::naive::default_naive_quic_congestion_control),
    })
}

fn tuic_config(node: &Value, listen: SocketAddr) -> Result<TuicClientConfig> {
    let tls = object_field(node, &["tls"]);
    ensure!(
        tls.map(|opts| map_bool(opts, &["enabled"], true))
            .unwrap_or_else(|| node_bool(node, &["tls"], true)),
        "TUIC client requires TLS"
    );
    let server_host = node_string(node, &["server", "host", "address"])?;
    Ok(TuicClientConfig {
        listen,
        server_port: node_port(node, &["port", "server_port", "server-port"])?,
        uuid: node_string(node, &["uuid", "id", "user_id", "username"])?,
        password: node_string(node, &["password", "passwd", "pass"])?,
        sni: tls
            .and_then(|opts| map_string(opts, &["server_name", "server-name", "serverName"]))
            .or_else(|| node_optional_string(node, &["sni", "servername", "server-name", "peer"]))
            .unwrap_or_else(|| server_host.clone()),
        server_host,
        insecure: tls
            .map(|opts| {
                map_bool(
                    opts,
                    &["insecure", "skip-cert-verify", "skip_cert_verify"],
                    false,
                )
            })
            .unwrap_or_else(|| {
                node_bool(
                    node,
                    &["insecure", "skip-cert-verify", "allowInsecure"],
                    false,
                )
            }),
        udp: node_bool(node, &["udp"], true),
        udp_relay_mode: node_optional_string(node, &["udp-relay-mode", "udp_relay_mode"])
            .unwrap_or_else(|| "native".to_string()),
        congestion_control: node_optional_string(
            node,
            &[
                "congestion-control",
                "congestion_control",
                "congestion-controller",
                "congestion_controller",
            ],
        )
        .unwrap_or_else(|| "cubic".to_string()),
        alpn_protocols: node_alpn_list(node, tls).unwrap_or_else(|| vec!["h3".to_string()]),
        heartbeat_interval_secs: node_duration_secs(
            node,
            &[
                "heartbeat_interval_secs",
                "heartbeat-interval-secs",
                "heartbeat",
            ],
            10,
        )?,
    })
}

fn shadowsocks_config(node: &Value, listen: SocketAddr) -> Result<ShadowsocksClientConfig> {
    ensure!(
        field(node, &["plugin", "plugin-opts", "plugin_opts"]).is_none(),
        "Shadowsocks plugin is not supported by Aerion"
    );
    let udp_over_tcp = udp_over_tcp_enabled(node);
    Ok(ShadowsocksClientConfig {
        listen,
        server_host: node_string(node, &["server", "host", "address"])?,
        server_port: node_port(node, &["port", "server_port", "server-port"])?,
        method: node_string(node, &["cipher", "method", "security"])?,
        password: node_string(node, &["password", "passwd"])?,
        udp: node_bool(node, &["udp"], true) || udp_over_tcp,
        udp_over_tcp,
    })
}

fn node_protocol(node: &Value) -> Result<String> {
    let protocol = node_optional_string(node, &["type", "protocol"])
        .unwrap_or_else(|| "anytls".to_string())
        .to_ascii_lowercase();
    Ok(match protocol.as_str() {
        "hy2" => "hysteria2".to_string(),
        "mierus" => "mieru".to_string(),
        "naive+https" | "naive+quic" => "naive".to_string(),
        "shadowsocks" => "ss".to_string(),
        _ => protocol,
    })
}

fn vless_transport(node: &Value) -> Result<VlessTransportConfig> {
    let network = node_optional_string(node, &["network"]).unwrap_or_else(|| "tcp".to_string());
    if let Some(opts) = object_field(node, &["transport"]) {
        let kind = map_string(opts, &["type"]).unwrap_or_else(|| network.clone());
        let host = map_string(opts, &["host"]).or_else(|| header_value(opts, "host"));
        let path = if kind.eq_ignore_ascii_case("grpc") {
            map_string(opts, &["service_name", "serviceName", "path"])
        } else {
            map_string(opts, &["path"])
        };
        if kind.eq_ignore_ascii_case("xhttp") || kind.eq_ignore_ascii_case("splithttp") {
            return VlessTransportConfig::xhttp(
                path,
                host,
                map_headers(Some(opts)),
                map_string(opts, &["mode"]),
            );
        }
        return VlessTransportConfig::from_network(&kind, path, host, map_headers(Some(opts)));
    }
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
    let tls_reality = object_field(node, &["tls"])
        .and_then(|tls| tls.get("reality"))
        .and_then(Value::as_object);
    let public_key = opts
        .and_then(|opts| map_string(opts, &["public-key", "public_key"]))
        .or_else(|| tls_reality.and_then(|opts| map_string(opts, &["public_key", "public-key"])))
        .or_else(|| node_optional_string(node, &["public-key", "public_key", "pbk"]));
    let Some(public_key) = public_key else {
        return Ok(None);
    };
    let short_id = opts
        .and_then(|opts| map_string(opts, &["short-id", "short_id"]))
        .or_else(|| tls_reality.and_then(|opts| map_string(opts, &["short_id", "short-id"])))
        .or_else(|| node_optional_string(node, &["short-id", "short_id", "sid"]))
        .unwrap_or_default();
    RealityClientConfig::from_strings(&public_key, &short_id).map(Some)
}

fn client_fingerprint(node: &Value) -> Result<Option<UtlsFingerprint>> {
    let tls = object_field(node, &["tls"]);
    let tls_utls = tls
        .and_then(|tls| tls.get("utls"))
        .and_then(Value::as_object);
    let Some(value) = node_optional_string(
        node,
        &[
            "client-fingerprint",
            "client_fingerprint",
            "fingerprint",
            "fp",
        ],
    )
    .or_else(|| {
        tls.and_then(|opts| {
            map_string(
                opts,
                &[
                    "client-fingerprint",
                    "client_fingerprint",
                    "fingerprint",
                    "fp",
                ],
            )
        })
    })
    .or_else(|| {
        tls_utls.and_then(|opts| {
            map_string(
                opts,
                &[
                    "client-fingerprint",
                    "client_fingerprint",
                    "fingerprint",
                    "fp",
                ],
            )
        })
    }) else {
        return Ok(None);
    };
    UtlsFingerprint::from_mihomo_name(&value)
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
                .trim_start_matches(|ch: char| ch == '/' || ch == '_')
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

fn node_alpn_list(node: &Value, tls: Option<&Map<String, Value>>) -> Option<Vec<String>> {
    field(node, &["alpn"])
        .or_else(|| tls.and_then(|opts| opts.get("alpn")))
        .and_then(value_to_alpn_list)
        .filter(|values| !values.is_empty())
}

fn value_to_alpn_list(value: &Value) -> Option<Vec<String>> {
    match value {
        Value::Array(values) => Some(
            values
                .iter()
                .filter_map(value_to_string)
                .filter(|value| !value.is_empty())
                .collect(),
        ),
        Value::String(text) => Some(
            text.split(',')
                .map(str::trim)
                .filter(|value| !value.is_empty())
                .map(str::to_string)
                .collect(),
        ),
        _ => None,
    }
}

fn node_duration_secs(node: &Value, keys: &[&str], default: u64) -> Result<u64> {
    let Some(value) = field(node, keys) else {
        return Ok(default);
    };
    match value {
        Value::Number(number) => number.as_u64().context("duration field is out of range"),
        Value::String(text) => text
            .trim()
            .trim_end_matches('s')
            .parse::<u64>()
            .with_context(|| format!("parse duration node field {}", keys.join("/"))),
        _ => bail!("node field {} must be a number or string", keys.join("/")),
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

fn header_value(map: &Map<String, Value>, name: &str) -> Option<String> {
    map_headers(Some(map))
        .into_iter()
        .find(|(key, _)| key.eq_ignore_ascii_case(name))
        .map(|(_, value)| value)
}

fn udp_over_tcp_enabled(node: &Value) -> bool {
    object_field(node, &["udp_over_tcp", "udp-over-tcp"])
        .map(|opts| map_bool(opts, &["enabled"], false))
        .unwrap_or(false)
        || node_bool(node, &["uot", "udp-over-tcp", "udp_over_tcp"], false)
}

fn naive_extra_headers(node: &Value) -> Result<Vec<(String, String)>> {
    let Some(headers) = object_field(node, &["extra_headers", "extra-headers", "headers"]) else {
        return Ok(Vec::new());
    };
    let mut values = BTreeMap::new();
    for (key, value) in headers {
        ensure!(
            !key.contains('\r') && !key.contains('\n'),
            "Naive extra header name contains newline"
        );
        let Some(value) = value_to_string(value) else {
            continue;
        };
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
    use aerion::vless_transport::VlessTransportKind;

    #[test]
    fn parses_sing_box_vless_reality_transport() -> Result<()> {
        let node = serde_json::json!({
            "type": "vless",
            "server": "example.com",
            "server_port": 443,
            "uuid": "a3482e88-686a-4a58-8126-99c9df64b7bf",
            "flow": "xtls-rprx-vision",
            "packet_encoding": "xudp",
            "tls": {
                "enabled": true,
                "server_name": "front.example.com",
                "insecure": true,
                "utls": { "enabled": true, "fingerprint": "chrome" },
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
            "server": "example.com",
            "server_port": 80,
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
            "server": "example.com",
            "server_port": 8388,
            "method": "aes-128-gcm",
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
    fn parses_vmess_websocket() -> Result<()> {
        let node = serde_json::json!({
            "type": "vmess",
            "server": "example.com",
            "server_port": 80,
            "uuid": "a3482e88-686a-4a58-8126-99c9df64b7bf",
            "alter_id": 0,
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
            "server": "example.com",
            "server_port": 80,
            "uuid": "a3482e88-686a-4a58-8126-99c9df64b7bf",
            "alter_id": 0,
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
            "server": "example.com",
            "server_port": 443,
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
            "server": "hy2.example.com",
            "server_port": 443,
            "password": "secret",
            "tls": {
                "enabled": true,
                "server_name": "front.example.com",
                "insecure": true
            }
        });
        let AerionProxyConfig::Hysteria2(config) =
            node_to_proxy_config(&node, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected Hysteria2 config")
        };
        assert_eq!(config.sni, "front.example.com");
        assert!(config.insecure);
        Ok(())
    }

    #[test]
    fn parses_naive_quic_congestion_control() -> Result<()> {
        let node = serde_json::json!({
            "type": "naive",
            "server": "naive.example.com",
            "server_port": 443,
            "username": "user",
            "password": "secret",
            "quic": true,
            "quic_congestion_control": "reno",
            "tls": { "enabled": true }
        });
        let AerionProxyConfig::Naive(config) =
            node_to_proxy_config(&node, "127.0.0.1:1080".parse()?)?
        else {
            bail!("expected Naive config")
        };
        assert!(config.quic);
        assert_eq!(config.quic_congestion_control, "reno");
        Ok(())
    }
}
