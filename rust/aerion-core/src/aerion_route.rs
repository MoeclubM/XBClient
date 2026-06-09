use crate::aerion_config_compat::node_to_proxy_config;
use crate::aerion_mihomo_sanitize::sanitize_mihomo_route_yaml;
use crate::aerion_protocol::{AerionProxyConfig, spawn_aerion_listener};
use aerion::{
    MihomoClientConfig, MihomoConfig, RouteDecision, RouteProxyConfig, RouteProxyState, RouteTable,
    run_route_proxy_with_state,
};
use anyhow::{Context, Result, bail, ensure};
use once_cell::sync::Lazy;
use serde::Deserialize;
use serde_json::json;
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::env;
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
    #[serde(default)]
    selected_proxy: Option<String>,
    #[serde(default)]
    selected_node: Option<serde_json::Value>,
}

struct RouteSession {
    router_task: JoinHandle<Result<()>>,
    outbound_tasks: Vec<JoinHandle<Result<()>>>,
}

static NEXT_ROUTE_SESSION_ID: AtomicU64 = AtomicU64::new(1);
static ROUTE_SESSIONS: Lazy<StdMutex<HashMap<u64, RouteSession>>> =
    Lazy::new(|| StdMutex::new(HashMap::new()));
const ROUTE_ASSETS_DIR_ENV: &str = "XBCLIENT_ROUTE_ASSETS_DIR";

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
        MihomoClientConfig::NodeExpand(config) => AerionProxyConfig::NodeExpand(config),
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
    selected_proxy: Option<&str>,
    selected_node: Option<&serde_json::Value>,
) -> Result<(BTreeMap<String, SocketAddr>, Vec<JoinHandle<Result<()>>>)> {
    let mut upstreams = BTreeMap::new();
    let mut tasks = Vec::new();
    for tag in route_proxy_tags(routes) {
        let listener =
            bind_listener(&format!("route outbound {tag}"), ephemeral_loopback()).await?;
        let upstream = listener.local_addr()?;
        let proxy = if selected_proxy == Some(tag.as_str()) {
            let node = selected_node
                .with_context(|| format!("selected route outbound {tag} missing selected_node"))?;
            node_to_proxy_config(node, upstream)
                .with_context(|| format!("resolve selected route outbound {tag}"))?
        } else {
            let client = config
                .resolved_proxy_config(&tag, upstream)
                .with_context(|| format!("resolve mihomo route outbound {tag}"))?;
            mihomo_client_config(client, upstream)
        };
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

fn route_action_index(parts: &[&str]) -> Option<usize> {
    if parts.len() < 2 {
        return None;
    }
    if parts
        .last()
        .is_some_and(|value| value.trim().eq_ignore_ascii_case("no-resolve"))
    {
        return (parts.len() >= 3).then_some(parts.len() - 2);
    }
    Some(parts.len() - 1)
}

fn apply_selected_proxy(config: &mut MihomoConfig, proxy: &str) -> Result<()> {
    for rule in &mut config.rules {
        let parts: Vec<&str> = rule.split(',').collect();
        let Some(action_index) = route_action_index(&parts) else {
            bail!("mihomo route rule has no action: {rule}");
        };
        let action = parts[action_index].trim();
        if action.eq_ignore_ascii_case("DIRECT")
            || action.eq_ignore_ascii_case("REJECT")
            || action.eq_ignore_ascii_case("REJECT-DROP")
            || action.eq_ignore_ascii_case("PASS")
        {
            continue;
        }
        let mut updated: Vec<String> = parts.iter().map(|value| value.trim().to_string()).collect();
        updated[action_index] = proxy.to_string();
        *rule = updated.join(",");
    }
    Ok(())
}

fn ensure_geoip_baseline(config: &mut MihomoConfig) {
    if config.rules.iter().any(|rule| {
        rule.trim_start()
            .to_ascii_uppercase()
            .starts_with("GEOIP,CN,")
    }) {
        return;
    }
    let insert_at = config
        .rules
        .iter()
        .position(|rule| rule.trim_start().to_ascii_uppercase().starts_with("MATCH,"))
        .unwrap_or(config.rules.len());
    config
        .rules
        .insert(insert_at, "GEOIP,CN,DIRECT,no-resolve".to_string());
}

fn route_assets_dir(geoip_dir: Option<&str>) -> Option<PathBuf> {
    geoip_dir
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .or_else(|| {
            env::var(ROUTE_ASSETS_DIR_ENV)
                .ok()
                .map(|value| value.trim().to_string())
                .filter(|value| !value.is_empty())
                .map(PathBuf::from)
        })
}

pub async fn start_route_from_json(input: &str) -> Result<String> {
    let request: StartRouteRequest =
        serde_json::from_str(input).context("parse start route request")?;
    ensure!(
        !request.config_yaml.trim().is_empty(),
        "route config_yaml is empty"
    );
    let mut config = parse_mihomo_config(&request.config_yaml)?;
    let global_proxy = if let Some(proxy) = request
        .global_proxy
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        apply_global_proxy(&mut config, proxy);
        true
    } else if let Some(proxy) = request
        .selected_proxy
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        apply_selected_proxy(&mut config, proxy)?;
        false
    } else {
        false
    };
    let assets_dir = route_assets_dir(request.geoip_dir.as_deref());
    if assets_dir.is_some() && !global_proxy {
        ensure_geoip_baseline(&mut config);
    }
    ensure!(!config.rules.is_empty(), "mihomo route config has no rules");
    let routes = config
        .route_table_with_assets(assets_dir.as_deref())
        .context("compile mihomo route table")?;
    let outbound_tags = route_proxy_tags(&routes);
    let listen = ephemeral_loopback();
    let router_listener = bind_listener("route proxy", listen).await?;
    let router_addr = router_listener.local_addr()?;
    let selected_proxy = request
        .selected_proxy
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty());
    let (upstreams, outbound_tasks) = spawn_route_outbounds(
        &config,
        &routes,
        selected_proxy,
        request.selected_node.as_ref(),
    )
    .await?;
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compiles_geoip_cn_with_bundled_route_assets() -> Result<()> {
        let config = parse_mihomo_config(
            r#"
proxies: []
rules:
  - GEOIP,CN,DIRECT,no-resolve
  - MATCH,DIRECT
"#,
        )?;
        let assets = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("assets/route");
        config.route_table_with_assets(Some(&assets))?;
        Ok(())
    }

    #[test]
    fn global_proxy_does_not_add_cn_baseline() -> Result<()> {
        let mut config = parse_mihomo_config(
            r#"
proxies: []
rules:
  - MATCH,DIRECT
"#,
        )?;
        apply_global_proxy(&mut config, "proxy");
        assert_eq!(config.rules, vec!["MATCH,proxy"]);
        Ok(())
    }

    #[test]
    fn compiles_inline_rule_providers() -> Result<()> {
        let config = parse_mihomo_config(
            r#"
rule-providers:
  local:
    type: inline
    behavior: domain
    payload:
      - example.com
rules:
  - RULE-SET,local,DIRECT
  - MATCH,DIRECT
"#,
        )?;
        config.route_table()?;
        Ok(())
    }

    #[test]
    fn rejects_file_rule_providers() {
        let error = match parse_mihomo_config(
            r#"
rule-providers:
  local:
    type: file
    behavior: domain
    path: /proc/self/cmdline
rules:
  - RULE-SET,local,DIRECT
"#,
        ) {
            Ok(_) => panic!("file rule-providers must be rejected"),
            Err(error) => error,
        };
        assert!(
            error
                .to_string()
                .contains("mihomo route rule-provider local type file is not supported")
        );
    }
}
