use aerion_core::{set_event_callback, set_log_callback};
use anyhow::{Context, Result, anyhow, bail, ensure};
use once_cell::sync::Lazy;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::{BTreeSet, HashMap};
use std::io::Write;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::sync::Mutex;
use std::time::Duration;
use tokio::io::{self, AsyncBufReadExt, BufReader};
use tokio::task::JoinSet;

mod subscription;
mod system_proxy;

static HTTP_CLIENT: Lazy<reqwest::Client> = Lazy::new(|| {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(30))
        .timeout(Duration::from_secs(30))
        .build()
        .expect("build reqwest client")
});

static OUTPUT_LOCK: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));

fn emit_line(value: &Value) {
    let guard = OUTPUT_LOCK.lock().expect("lock stdout writer");
    let mut out = std::io::stdout().lock();
    writeln!(out, "{}", value).expect("write RPC line to stdout");
    out.flush().expect("flush RPC stdout");
    drop(guard);
}

#[derive(Deserialize)]
struct RpcRequest {
    id: u64,
    method: String,
    params: Value,
}

#[derive(Serialize)]
struct RpcResponseOk {
    id: u64,
    ok: bool,
    result: Value,
}

#[derive(Serialize)]
pub struct RuntimeCapabilities {
    pub platform: &'static str,
    pub system_proxy: bool,
    pub oauth_callback: bool,
    pub autostart: bool,
    pub tray: bool,
    pub local_socks: bool,
    pub vpn: bool,
    pub payment: bool,
}

#[derive(Serialize)]
pub struct RuntimeConfig {
    pub app_name: String,
    pub panel_type: String,
    pub default_api_url: String,
    pub user_agent: String,
    pub oauth_callback_scheme: String,
}

#[derive(Serialize)]
struct HttpResponse {
    ok: bool,
    status: u16,
    body: Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

#[derive(Deserialize)]
struct HttpRequest {
    method: String,
    url: String,
    headers: Option<HashMap<String, String>>,
    body: Option<Value>,
}

#[derive(Deserialize)]
struct ResolveNodeHostRequest {
    #[serde(rename = "dnsUrl")]
    dns_url: String,
    host: String,
    #[serde(rename = "userAgent")]
    user_agent: Option<String>,
}

#[derive(Deserialize)]
struct ResolveFluxNodeRequest {
    #[serde(rename = "dnsUrl")]
    dns_url: String,
    node: Value,
    #[serde(rename = "userAgent")]
    user_agent: Option<String>,
}

#[derive(Deserialize)]
struct RpcParamsForHttpRequest {
    request: HttpRequest,
}

fn platform_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "android") {
        "android"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else if cfg!(target_os = "linux") {
        "linux"
    } else if cfg!(target_os = "ios") {
        "ios"
    } else {
        "unknown"
    }
}

fn required_env(name: &str) -> Result<String> {
    let value =
        std::env::var(name).with_context(|| format!("{name} is required in build config"))?;
    let value = value.trim();
    if value.is_empty() {
        bail!("{name} is required in build config")
    }
    Ok(value.to_string())
}

fn runtime_capabilities() -> RuntimeCapabilities {
    // Electron 桌面端：Windows / Linux only. macOS may be added later.
    let desktop = cfg!(any(target_os = "windows", target_os = "linux"));
    let vpn = desktop;
    RuntimeCapabilities {
        platform: platform_name(),
        system_proxy: desktop,
        oauth_callback: desktop,
        autostart: desktop,
        tray: desktop,
        local_socks: true,
        vpn,
        payment: true,
    }
}

fn runtime_config() -> Result<RuntimeConfig> {
    Ok(RuntimeConfig {
        app_name: required_env("XBCLIENT_APP_NAME")?,
        panel_type: required_env("XBCLIENT_PANEL_TYPE")?,
        default_api_url: required_env("XBCLIENT_DEFAULT_API_URL")?,
        user_agent: required_env("XBCLIENT_USER_AGENT")?,
        oauth_callback_scheme: required_env("XBCLIENT_OAUTH_CALLBACK_SCHEME")?,
    })
}

async fn resolve_node_host(params: ResolveNodeHostRequest) -> Result<String> {
    let host = params.host.trim();
    if host.parse::<IpAddr>().is_ok() {
        return Ok(host.to_string());
    }
    let resolver = params.dns_url.trim();
    if !resolver.starts_with("http://") && !resolver.starts_with("https://") {
        return Err(anyhow!("节点 DNS 必须是 DoH 地址。"));
    }

    for record_type in ["A", "AAAA"] {
        let mut url = reqwest::Url::parse(resolver).with_context(|| "parse dns resolver URL")?;
        url.query_pairs_mut()
            .append_pair("name", host)
            .append_pair("type", record_type);

        let mut request = HTTP_CLIENT
            .get(url)
            .header("Accept", "application/dns-json, application/json");

        if let Some(value) = params
            .user_agent
            .as_deref()
            .map(|v| v.trim())
            .filter(|v| !v.is_empty())
        {
            request = request.header("User-Agent", value);
        }

        let response = request
            .send()
            .await
            .map_err(|error| anyhow!("节点 DNS 请求失败：{error}"))?;

        let status = response.status();
        if !status.is_success() {
            bail!("节点 DNS 请求失败：HTTP {}", status.as_u16());
        }

        #[derive(Deserialize)]
        struct DohResponse {
            #[serde(rename = "Answer")]
            answer: Option<Vec<DohAnswer>>,
        }
        #[derive(Deserialize)]
        struct DohAnswer {
            data: String,
        }

        let body = response
            .json::<DohResponse>()
            .await
            .map_err(|error| anyhow!("节点 DNS 响应不是 JSON：{error}"))?;

        if let Some(answer) = body.answer {
            for item in answer {
                if item.data.parse::<IpAddr>().is_ok() {
                    return Ok(item.data);
                }
            }
        }
    }

    Err(anyhow!("节点 DNS 无可用 A/AAAA 记录。"))
}

fn normalize_flux_host(raw: &str) -> String {
    let trimmed = raw.trim();
    if let Some(inner) = trimmed
        .strip_prefix('[')
        .and_then(|s| s.strip_suffix(']'))
        && inner.contains(':')
    {
        return inner.to_string();
    }
    trimmed.to_string()
}

fn channel_effective_host(ch: & serde_json::Map<String, Value>, main_host: &str) -> String {
    for key in ["server", "connect_host", "connect-host", "connectHost", "host"] {
        if let Some(val) = ch.get(key).and_then(Value::as_str) {
            let norm = normalize_flux_host(val);
            if !norm.is_empty() {
                return norm;
            }
        }
    }
    main_host.to_string()
}

async fn query_doh_family(
    resolver: &str,
    host: &str,
    record_type: &str,
    user_agent: Option<&str>,
) -> Result<Vec<String>> {
    let mut url = reqwest::Url::parse(resolver).with_context(|| "parse dns resolver URL")?;
    url.query_pairs_mut()
        .append_pair("name", host)
        .append_pair("type", record_type);
    let mut request = HTTP_CLIENT
        .get(url)
        .header("Accept", "application/dns-json, application/json");
    if let Some(ua) = user_agent.map(str::trim).filter(|v| !v.is_empty()) {
        request = request.header("User-Agent", ua);
    }
    let response = request
        .send()
        .await
        .map_err(|error| anyhow!("节点 DNS 请求失败：{error}"))?;
    let status = response.status();
    if !status.is_success() {
        bail!("节点 DNS 请求失败：HTTP {}", status.as_u16());
    }
    #[derive(Deserialize)]
    struct DohResponse {
        #[serde(rename = "Answer")]
        answer: Option<Vec<DohAnswer>>,
    }
    #[derive(Deserialize)]
    struct DohAnswer {
        data: String,
    }
    let body = response
        .json::<DohResponse>()
        .await
        .map_err(|error| anyhow!("节点 DNS 响应不是 JSON：{error}"))?;
    let mut out = Vec::new();
    if let Some(answers) = body.answer {
        for item in answers {
            let text = item.data.trim();
            let valid = if record_type == "A" {
                text.parse::<Ipv4Addr>().is_ok()
            } else {
                text.parse::<Ipv6Addr>().is_ok()
            };
            if valid && !out.iter().any(|existing| existing == text) {
                out.push(text.to_string());
            }
        }
    }
    Ok(out)
}

async fn resolve_flux_node(params: ResolveFluxNodeRequest) -> Result<Value> {
    let mut node = params.node;
    let obj = node
        .as_object_mut()
        .context("Flux node must be a JSON object")?;
    let main_host = obj
        .get("host")
        .or_else(|| obj.get("server"))
        .and_then(Value::as_str)
        .map(normalize_flux_host)
        .unwrap_or_default();

    let mut domains = BTreeSet::new();
    if !main_host.is_empty() && main_host.parse::<IpAddr>().is_err() {
        domains.insert(main_host.clone());
    }
    for list in [
        obj.get("channels").and_then(Value::as_array),
        obj.get("flux")
            .and_then(Value::as_object)
            .and_then(|f| f.get("channels"))
            .and_then(Value::as_array),
    ]
    .into_iter()
    .flatten()
    {
        for item in list {
            let Some(ch) = item.as_object() else {
                continue;
            };
            if ch.get("enabled").and_then(Value::as_bool) == Some(false) {
                continue;
            }
            let ch_host = channel_effective_host(ch, &main_host);
            if !ch_host.is_empty() && ch_host.parse::<IpAddr>().is_err() {
                domains.insert(ch_host);
            }
        }
    }

    if domains.is_empty() {
        return Ok(node);
    }

    let resolver = params.dns_url.trim().to_string();
    if !resolver.starts_with("http://") && !resolver.starts_with("https://") {
        return Err(anyhow!("节点 DNS 必须是 DoH 地址。"));
    }
    let user_agent = params.user_agent;
    let mut join_set = JoinSet::new();
    for domain in domains {
        let resolver_cl = resolver.clone();
        let ua_cl = user_agent.clone();
        join_set.spawn(async move {
            let (v4, v6) = tokio::try_join!(
                query_doh_family(&resolver_cl, &domain, "A", ua_cl.as_deref()),
                query_doh_family(&resolver_cl, &domain, "AAAA", ua_cl.as_deref()),
            )?;
            if v4.is_empty() && v6.is_empty() {
                bail!("节点 DNS 无可用 A/AAAA 记录：{domain}");
            }
            Ok::<(String, Vec<String>, Vec<String>), anyhow::Error>((domain, v4, v6))
        });
    }

    let mut resolved_map: HashMap<String, (Vec<String>, Vec<String>)> = HashMap::new();
    while let Some(joined) = join_set.join_next().await {
        let (domain, v4, v6) = joined.context("Flux DoH task join failed")??;
        resolved_map.insert(domain, (v4, v6));
    }

    if let Some((v4, v6)) = resolved_map.get(&main_host) {
        obj.insert("resolved_v4".to_string(), json!(v4));
        obj.insert("resolved_v6".to_string(), json!(v6));
    }
    if let Some(channels) = obj.get_mut("channels").and_then(Value::as_array_mut) {
        for item in channels {
            if let Some(ch) = item.as_object_mut() {
                if ch.get("enabled").and_then(Value::as_bool) == Some(false) {
                    continue;
                }
                let ch_host = channel_effective_host(ch, &main_host);
                if let Some((v4, v6)) = resolved_map.get(&ch_host) {
                    ch.insert("resolved_v4".to_string(), json!(v4));
                    ch.insert("resolved_v6".to_string(), json!(v6));
                }
            }
        }
    }
    if let Some(channels) = obj
        .get_mut("flux")
        .and_then(Value::as_object_mut)
        .and_then(|f| f.get_mut("channels"))
        .and_then(Value::as_array_mut)
    {
        for item in channels {
            if let Some(ch) = item.as_object_mut() {
                if ch.get("enabled").and_then(Value::as_bool) == Some(false) {
                    continue;
                }
                let ch_host = channel_effective_host(ch, &main_host);
                if let Some((v4, v6)) = resolved_map.get(&ch_host) {
                    ch.insert("resolved_v4".to_string(), json!(v4));
                    ch.insert("resolved_v6".to_string(), json!(v6));
                }
            }
        }
    }
    Ok(node)
}

async fn http_request(params: RpcParamsForHttpRequest) -> Result<HttpResponse> {
    let method = params
        .request
        .method
        .parse::<reqwest::Method>()
        .map_err(|error| anyhow!("invalid HTTP method: {error}"))?;

    let mut builder = HTTP_CLIENT
        .request(method, &params.request.url)
        .header("Accept", "application/json");

    if let Some(headers) = params.request.headers {
        for (key, value) in headers {
            builder = builder.header(key, value);
        }
    }

    if let Some(body) = params.request.body {
        let bytes = serde_json::to_vec(&body).context("encode HTTP request body")?;
        builder = builder
            .body(bytes)
            .header("Content-Type", "application/json; charset=utf-8");
    }

    let response = builder.send().await.context("HTTP request")?;
    let status = response.status().as_u16();
    let text = response.text().await.context("read HTTP response")?;

    ensure!(!text.is_empty(), "HTTP JSON response body is empty");
    let parsed = serde_json::from_str(&text).context("parse HTTP JSON response")?;

    let ok = (200..300).contains(&status);
    Ok(HttpResponse {
        ok,
        status,
        body: parsed,
        error: (!ok).then(|| format!("HTTP {status}")),
    })
}

async fn subscription_fetch(params: &Value) -> Result<Value> {
    #[derive(Deserialize)]
    struct SubscriptionReq {
        url: String,
        flag: String,
        authorization: Option<String>,
    }
    let input: SubscriptionReq =
        serde_json::from_value(params.clone()).context("parse subscription_fetch args")?;
    let v = subscription::fetch(&HTTP_CLIENT, &input.url, &input.flag, input.authorization.as_deref()).await?;
    Ok(v)
}

async fn aerion_test_node(params: &Value) -> Result<Value> {
    #[derive(Deserialize)]
    struct TestNodeParams {
        request: Value,
    }
    let input: TestNodeParams =
        serde_json::from_value(params.clone()).context("parse aerion_test_node args")?;
    let json_str = serde_json::to_string(&input.request)?;
    let output = aerion_core::test_node_from_json(&json_str)
        .await
        .context("test Aerion node")?;
    serde_json::from_str(&output).context("parse aerion_test_node response")
}

async fn spawn_aerion_value(
    fut: impl std::future::Future<Output = Result<Value>> + Send + 'static,
) -> Result<Value> {
    match tokio::spawn(fut).await {
        Ok(result) => result,
        Err(error) => Err(anyhow!("Aerion internal task failed: {error}")),
    }
}

async fn aerion_start_socks(params: &Value) -> Result<Value> {
    #[derive(Deserialize)]
    struct StartSocksParams {
        node: Value,
    }
    let input: StartSocksParams =
        serde_json::from_value(params.clone()).context("parse aerion_start_socks args")?;
    let wrapped = serde_json::json!({ "node": input.node });
    let input_str = serde_json::to_string(&wrapped)?;
    let output = aerion_core::start_socks_from_json(&input_str)
        .await
        .context("start Aerion SOCKS")?;
    serde_json::from_str(&output).context("parse aerion_start_socks response")
}

async fn aerion_start_vpn(params: &Value) -> Result<Value> {
    let input = serde_json::to_string(params)?;
    let output = aerion_core::start_vpn_from_json(&input)
        .await
        .context("start Aerion VPN")?;
    serde_json::from_str(&output).context("parse aerion_start_vpn response")
}

async fn aerion_stop_vpn(params: &Value) -> Result<Value> {
    let session_id = params
        .get("sessionId")
        .and_then(|v| v.as_u64())
        .ok_or_else(|| anyhow!("aerion_stop_vpn missing sessionId"))?;
    let output = aerion_core::stop_vpn(session_id)
        .await
        .context("stop Aerion VPN")?;
    serde_json::from_str(&output).context("parse aerion_stop_vpn response")
}

async fn aerion_start_route(params: &Value) -> Result<Value> {
    let input = serde_json::to_string(params)?;
    let output = aerion_core::start_route_from_json(&input)
        .await
        .context("start Aerion route")?;
    serde_json::from_str(&output).context("parse aerion_start_route response")
}

async fn aerion_stop_route(params: &Value) -> Result<Value> {
    let session_id = params
        .get("sessionId")
        .and_then(|v| v.as_u64())
        .ok_or_else(|| anyhow!("aerion_stop_route missing sessionId"))?;
    let output = aerion_core::stop_route(session_id)
        .await
        .context("stop Aerion route")?;
    serde_json::from_str(&output).context("parse aerion_stop_route response")
}

async fn aerion_stop(params: &Value) -> Result<Value> {
    let session_id = params
        .get("sessionId")
        .and_then(|v| v.as_u64())
        .ok_or_else(|| anyhow!("aerion_stop missing sessionId"))?;
    let output = aerion_core::stop_socks(session_id).await?;
    serde_json::from_str(&output).context("parse aerion_stop response")
}

async fn system_proxy_set(params: &Value) -> Result<()> {
    #[derive(Deserialize)]
    struct SystemProxySetParams {
        host: String,
        port: u16,
    }
    let input: SystemProxySetParams =
        serde_json::from_value(params.clone()).context("parse system_proxy_set args")?;
    system_proxy::set_socks(&input.host, input.port).context("system proxy set")?;
    Ok(())
}

async fn system_proxy_clear() -> Result<()> {
    system_proxy::clear().context("system proxy clear")?;
    Ok(())
}

fn emit_rpc_response(id: u64, out: Result<RpcResponseOk, anyhow::Error>) {
    match out {
        Ok(ok) => {
            if let Ok(value) = serde_json::to_value(ok) {
                emit_line(&value);
            } else {
                emit_line(&json!({ "id": id, "ok": false, "error": "serialize response failed" }));
            }
        }
        Err(error) => {
            emit_line(&json!({ "id": id, "ok": false, "error": format!("{error:#}") }));
        }
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    rustls::crypto::ring::default_provider()
        .install_default()
        .map_err(|_| anyhow!("rustls crypto provider is already installed"))?;
    let mut stdin = BufReader::new(io::stdin());
    let mut lines = String::new();

    set_log_callback(|level, message| {
        emit_line(&json!({ "type": "log", "level": level, "message": message }));
    });
    set_event_callback(|_, event_json| {
        emit_line(&json!({ "type": "event", "payload": event_json }));
    });

    loop {
        lines.clear();
        let read = match stdin.read_line(&mut lines).await {
            Ok(n) => n,
            Err(error) => {
                emit_line(&json!({
                    "type": "log",
                    "level": "error",
                    "message": format!("stdin read error: {error}")
                }));
                break;
            }
        };
        if read == 0 {
            break;
        }
        let text = lines.trim();
        if text.is_empty() {
            continue;
        }

        let req: RpcRequest = match serde_json::from_str(text) {
            Ok(v) => v,
            Err(error) => {
                emit_line(&json!({
                    "type": "log",
                    "level": "error",
                    "message": format!("invalid RPC request JSON: {error}")
                }));
                continue;
            }
        };

        let out: Result<RpcResponseOk, anyhow::Error> = (async {
            match req.method.as_str() {
                "runtime_capabilities" => {
                    let caps = runtime_capabilities();
                    Ok(RpcResponseOk {
                        id: req.id,
                        ok: true,
                        result: serde_json::to_value(caps)?,
                    })
                }
                "runtime_config" => {
                    let cfg = runtime_config()?;
                    Ok(RpcResponseOk {
                        id: req.id,
                        ok: true,
                        result: serde_json::to_value(cfg)?,
                    })
                }
                "resolve_node_host" => {
                    let params: ResolveNodeHostRequest = serde_json::from_value(req.params)?;
                    let resolved = resolve_node_host(params).await?;
                    Ok(RpcResponseOk {
                        id: req.id,
                        ok: true,
                        result: serde_json::to_value(resolved)?,
                    })
                }
                "resolve_flux_node" => {
                    let params: ResolveFluxNodeRequest = serde_json::from_value(req.params)?;
                    let resolved = resolve_flux_node(params).await?;
                    Ok(RpcResponseOk {
                        id: req.id,
                        ok: true,
                        result: resolved,
                    })
                }
                "http_request" => {
                    let params: RpcParamsForHttpRequest = serde_json::from_value(req.params)?;
                    let resp = http_request(params).await?;
                    Ok(RpcResponseOk {
                        id: req.id,
                        ok: true,
                        result: serde_json::to_value(resp)?,
                    })
                }
                "subscription_fetch" => {
                    let resp = subscription_fetch(&req.params).await?;
                    Ok(RpcResponseOk {
                        id: req.id,
                        ok: true,
                        result: resp,
                    })
                }
                "aerion_test_node" => {
                    let params = req.params.clone();
                    let resp =
                        spawn_aerion_value(async move { aerion_test_node(&params).await }).await?;
                    Ok(RpcResponseOk {
                        id: req.id,
                        ok: true,
                        result: resp,
                    })
                }
                "aerion_start_socks" => {
                    let params = req.params.clone();
                    let resp = spawn_aerion_value(async move { aerion_start_socks(&params).await })
                        .await?;
                    Ok(RpcResponseOk {
                        id: req.id,
                        ok: true,
                        result: resp,
                    })
                }
                "aerion_start_route" => {
                    let params = req.params.clone();
                    let resp = spawn_aerion_value(async move { aerion_start_route(&params).await })
                        .await?;
                    Ok(RpcResponseOk {
                        id: req.id,
                        ok: true,
                        result: resp,
                    })
                }
                "aerion_start_vpn" => {
                    let params = req.params.clone();
                    let resp =
                        spawn_aerion_value(async move { aerion_start_vpn(&params).await }).await?;
                    Ok(RpcResponseOk {
                        id: req.id,
                        ok: true,
                        result: resp,
                    })
                }
                "aerion_stop_vpn" => {
                    let params = req.params.clone();
                    let resp =
                        spawn_aerion_value(async move { aerion_stop_vpn(&params).await }).await?;
                    Ok(RpcResponseOk {
                        id: req.id,
                        ok: true,
                        result: resp,
                    })
                }
                "aerion_stop" => {
                    let params = req.params.clone();
                    let resp =
                        spawn_aerion_value(async move { aerion_stop(&params).await }).await?;
                    Ok(RpcResponseOk {
                        id: req.id,
                        ok: true,
                        result: resp,
                    })
                }
                "aerion_stop_route" => {
                    let params = req.params.clone();
                    let resp =
                        spawn_aerion_value(async move { aerion_stop_route(&params).await }).await?;
                    Ok(RpcResponseOk {
                        id: req.id,
                        ok: true,
                        result: resp,
                    })
                }
                "system_proxy_set" => {
                    system_proxy_set(&req.params).await?;
                    Ok(RpcResponseOk {
                        id: req.id,
                        ok: true,
                        result: json!(null),
                    })
                }
                "system_proxy_clear" => {
                    system_proxy_clear().await?;
                    Ok(RpcResponseOk {
                        id: req.id,
                        ok: true,
                        result: json!(null),
                    })
                }
                other => bail!("unsupported method: {other}"),
            }
        })
        .await;

        emit_rpc_response(req.id, out);
    }

    Ok(())
}
