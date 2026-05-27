use crate::aerion_mihomo_sanitize::sanitize_mihomo_route_yaml;
use crate::aerion_protocol::{AerionProxyConfig, spawn_aerion_listener};
use aerion::{
    MihomoClientConfig, MihomoConfig, RouteDecision, RouteProxyConfig, RouteProxyState, RouteTable,
    run_route_proxy_with_state,
};
use anyhow::{Context, Result, ensure};
use once_cell::sync::Lazy;
use serde::Deserialize;
use serde_json::json;
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::Mutex as StdMutex;
use std::sync::atomic::{AtomicU64, Ordering};
use tokio::net::TcpListener;
use tokio::task::JoinHandle;

#[derive(Deserialize)]
struct StartRouteRequest {
    config_yaml: String,
    #[serde(default)]
    geoip_dir: Option<String>,
    #[serde(default)]
    global_proxy: Option<String>,
}

struct RouteSession {
    router_task: JoinHandle<Result<()>>,
    outbound_tasks: Vec<JoinHandle<Result<()>>>,
}

static NEXT_ROUTE_SESSION_ID: AtomicU64 = AtomicU64::new(1);
static ROUTE_SESSIONS: Lazy<StdMutex<HashMap<u64, RouteSession>>> =
    Lazy::new(|| StdMutex::new(HashMap::new()));

fn ephemeral_loopback() -> SocketAddr {
    SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0)
}

fn parse_mihomo_config(text: &str) -> Result<MihomoConfig> {
    let sanitized = sanitize_mihomo_route_yaml(text)?;
    let mut config: MihomoConfig =
        serde_yaml::from_str(&sanitized).context("parse sanitized mihomo route config")?;
    config.source_dir = None;
    Ok(config)
}

fn route_proxy_tags(routes: &RouteTable) -> Vec<String> {
    let mut tags = BTreeSet::new();
    for rule in &routes.rules {
        if let RouteDecision::Proxy(tag) = &rule.action {
            tags.insert(tag.clone());
        }
    }
    if let RouteDecision::Proxy(tag) = &routes.default {
        tags.insert(tag.clone());
    }
    tags.into_iter().collect()
}

fn mihomo_client_config(config: MihomoClientConfig, _listen: SocketAddr) -> AerionProxyConfig {
    match config {
        MihomoClientConfig::AnyTls(config) => AerionProxyConfig::AnyTls(config),
        MihomoClientConfig::HttpProxy(config) => AerionProxyConfig::HttpProxy(config),
        MihomoClientConfig::Hysteria2(config) => AerionProxyConfig::Hysteria2(config),
        MihomoClientConfig::Trojan(config) => AerionProxyConfig::Trojan(config),
        MihomoClientConfig::Vless(config) => AerionProxyConfig::Vless(config),
        MihomoClientConfig::Vmess(config) => AerionProxyConfig::Vmess(config),
        MihomoClientConfig::Mieru(config) => AerionProxyConfig::Mieru(config),
        MihomoClientConfig::Naive(config) => AerionProxyConfig::Naive(config),
        MihomoClientConfig::Route(config) => AerionProxyConfig::Route(config),
        MihomoClientConfig::Shadowsocks(config) => AerionProxyConfig::Shadowsocks(config),
        MihomoClientConfig::SocksProxy(config) => AerionProxyConfig::SocksProxy(config),
        MihomoClientConfig::Tuic(config) => AerionProxyConfig::Tuic(config),
    }
}

async fn bind_listener(label: &str, listen: SocketAddr) -> Result<TcpListener> {
    TcpListener::bind(listen)
        .await
        .with_context(|| format!("bind {label} on {listen}"))
}

async fn spawn_route_outbounds(
    config: &MihomoConfig,
    routes: &RouteTable,
) -> Result<(BTreeMap<String, SocketAddr>, Vec<JoinHandle<Result<()>>>)> {
    let mut upstreams = BTreeMap::new();
    let mut tasks = Vec::new();
    for tag in route_proxy_tags(routes) {
        let listener =
            bind_listener(&format!("route outbound {tag}"), ephemeral_loopback()).await?;
        let upstream = listener.local_addr()?;
        let client = config
            .resolved_proxy_config(&tag, upstream)
            .with_context(|| format!("resolve mihomo route outbound {tag}"))?;
        let proxy = mihomo_client_config(client, upstream);
        tasks.push(spawn_aerion_listener(listener, proxy, None));
        upstreams.insert(tag, upstream);
    }
    Ok((upstreams, tasks))
}

fn apply_global_proxy(config: &mut MihomoConfig, proxy: &str) {
    config.rules = vec![format!("MATCH,{proxy}")];
    config.proxy_groups.clear();
    config.rule_providers.clear();
}

pub async fn start_route_from_json(input: &str) -> Result<String> {
    let request: StartRouteRequest =
        serde_json::from_str(input).context("parse start route request")?;
    ensure!(
        !request.config_yaml.trim().is_empty(),
        "route config_yaml is empty"
    );
    let mut config = parse_mihomo_config(&request.config_yaml)?;
    if let Some(proxy) = request
        .global_proxy
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        apply_global_proxy(&mut config, proxy);
    }
    ensure!(!config.rules.is_empty(), "mihomo route config has no rules");
    let assets_dir = request
        .geoip_dir
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from);
    let routes = config
        .route_table_with_assets(assets_dir.as_deref())
        .context("compile mihomo route table")?;
    let outbound_tags = route_proxy_tags(&routes);
    let listen = ephemeral_loopback();
    let router_listener = bind_listener("route proxy", listen).await?;
    let router_addr = router_listener.local_addr()?;
    let (upstreams, outbound_tasks) = spawn_route_outbounds(&config, &routes).await?;
    let route_config = RouteProxyConfig { routes, upstreams };
    let state = RouteProxyState::from_config(route_config);
    let router_task = tokio::spawn(async move {
        run_route_proxy_with_state(router_listener, state)
            .await
            .context("run mihomo route proxy")
    });
    let session_id = NEXT_ROUTE_SESSION_ID.fetch_add(1, Ordering::SeqCst);
    ROUTE_SESSIONS
        .lock()
        .expect("route session map lock poisoned")
        .insert(
            session_id,
            RouteSession {
                router_task,
                outbound_tasks,
            },
        );
    Ok(json!({
        "ok": true,
        "session_id": session_id,
        "socks_addr": router_addr.to_string(),
        "rule_count": config.rules.len(),
        "outbound_tags": outbound_tags,
    })
    .to_string())
}

pub async fn stop_route(session_id: u64) -> Result<String> {
    let session = ROUTE_SESSIONS
        .lock()
        .expect("route session map lock poisoned")
        .remove(&session_id)
        .with_context(|| format!("route session not found: {session_id}"))?;
    session.router_task.abort();
    for task in session.outbound_tasks {
        task.abort();
    }
    Ok(json!({"ok": true, "session_id": session_id}).to_string())
}

pub fn inspect_route_config_yaml(text: &str) -> Result<String> {
    let config = parse_mihomo_config(text)?;
    let preview: Vec<&str> = config.rules.iter().take(20).map(String::as_str).collect();
    Ok(json!({
        "ok": true,
        "rule_count": config.rules.len(),
        "proxy_group_count": config.proxy_groups.len(),
        "rule_provider_count": config.rule_providers.len(),
        "rules_preview": preview,
        "has_rules": !config.rules.is_empty(),
    })
    .to_string())
}
