use anyhow::{anyhow, bail, Context, Result};
use aerion_core::{set_event_callback, set_log_callback};
use once_cell::sync::Lazy;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::net::IpAddr;
use std::io::Write;
use std::time::Duration;
use tokio::io::{self, AsyncBufReadExt, BufReader};
use std::sync::Mutex;

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
    let _guard = OUTPUT_LOCK.lock().expect("output lock");
    let mut out = std::io::stdout().lock();
    let _ = writeln!(out, "{}", value);
    let _ = out.flush();
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
struct RpcResponseErr {
    id: u64,
    ok: bool,
    error: String,
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
    pub admob: bool,
}

#[derive(Serialize)]
pub struct RuntimeConfig {
    pub app_name: String,
    pub default_api_url: String,
    pub user_agent: String,
    pub oauth_callback_scheme: String,
}

#[derive(Serialize)]
struct XboardResponse {
    ok: bool,
    status: u16,
    body: Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

#[derive(Deserialize)]
struct XboardRequest {
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
struct RpcParamsForXboardRequest {
    request: XboardRequest,
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
    let value = std::env::var(name).unwrap_or_default();
    let value = value.trim();
    if value.is_empty() {
        bail!("{name} is required in build config")
    }
    Ok(value.to_string())
}

fn runtime_capabilities() -> RuntimeCapabilities {
    let desktop = cfg!(any(target_os = "windows", target_os = "linux"));
    let vpn = desktop;
    RuntimeCapabilities {
        platform: platform_name(),
        system_proxy: desktop && !vpn,
        oauth_callback: desktop,
        autostart: desktop,
        tray: desktop,
        local_socks: true,
        vpn,
        payment: true,
        admob: false,
    }
}

fn runtime_config() -> Result<RuntimeConfig> {
    Ok(RuntimeConfig {
        app_name: required_env("XBCLIENT_APP_NAME")?,
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
        let mut url = reqwest::Url::parse(resolver)
            .with_context(|| "parse dns resolver URL")?;
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

async fn xboard_request(params: RpcParamsForXboardRequest) -> Result<XboardResponse> {
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
        let bytes = serde_json::to_vec(&body).context("encode xboard request body")?;
        builder = builder
            .body(bytes)
            .header("Content-Type", "application/json; charset=utf-8");
    }

    let response = builder.send().await.context("xboard request")?;
    let status = response.status().as_u16();
    let text = response.text().await.context("read xboard response")?;

    let parsed = if text.is_empty() {
        Value::Null
    } else {
        serde_json::from_str(&text).unwrap_or(Value::String(text))
    };

    let ok = (200..300).contains(&status);
    Ok(XboardResponse {
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
    }
    let input: SubscriptionReq = serde_json::from_value(params.clone()).context("parse subscription_fetch args")?;
    let v = subscription::fetch(&HTTP_CLIENT, &input.url, &input.flag).await?;
    Ok(v)
}

async fn aerion_test_node(params: &Value) -> Result<Value> {
    #[derive(Deserialize)]
    struct TestNodeParams {
        request: Value,
    }
    let input: TestNodeParams = serde_json::from_value(params.clone())
        .context("parse aerion_test_node args")?;
    let json_str = serde_json::to_string(&input.request)?;
    let output = aerion_core::test_node_from_json(&json_str)
        .await
        .map_err(|e| anyhow!(e.to_string()))?;
    Ok(serde_json::from_str(&output).unwrap_or(Value::Null))
}

async fn aerion_start_socks(params: &Value) -> Result<Value> {
    #[derive(Deserialize)]
    struct StartSocksParams {
        node: Value,
    }
    let input: StartSocksParams = serde_json::from_value(params.clone())
        .context("parse aerion_start_socks args")?;
    let wrapped = serde_json::json!({ "node": input.node });
    let input_str = serde_json::to_string(&wrapped)?;
    let output = aerion_core::start_socks_from_json(&input_str)
        .await
        .map_err(|e| anyhow!(e.to_string()))?;
    Ok(serde_json::from_str(&output).unwrap_or(Value::Null))
}

async fn aerion_start_vpn(params: &Value) -> Result<Value> {
    let input = serde_json::to_string(params)?;
    let output = aerion_core::start_vpn_from_json(&input)
        .await
        .map_err(|e| anyhow!(e.to_string()))?;
    Ok(serde_json::from_str(&output).unwrap_or(Value::Null))
}

async fn aerion_stop_vpn(params: &Value) -> Result<Value> {
    let session_id = params
        .get("sessionId")
        .or_else(|| params.get("session_id"))
        .and_then(|v| v.as_u64())
        .ok_or_else(|| anyhow!("aerion_stop_vpn missing sessionId"))?;
    let output = aerion_core::stop_vpn(session_id)
        .await
        .map_err(|e| anyhow!(e.to_string()))?;
    Ok(serde_json::from_str(&output).unwrap_or(Value::Null))
}

async fn aerion_stop(params: &Value) -> Result<Value> {
    // Accept both camelCase and snake_case, to be compatible with invoke payloads.
    let session_id = params
        .get("sessionId")
        .or_else(|| params.get("session_id"))
        .and_then(|v| v.as_u64())
        .ok_or_else(|| anyhow!("aerion_stop missing sessionId"))?;
    let output = aerion_core::stop_socks(session_id).await?;
    Ok(serde_json::from_str(&output).unwrap_or(Value::Null))
}

async fn system_proxy_set(params: &Value) -> Result<()> {
    #[derive(Deserialize)]
    struct SystemProxySetParams {
        host: String,
        port: u16,
    }
    let input: SystemProxySetParams = serde_json::from_value(params.clone())
        .context("parse system_proxy_set args")?;
    system_proxy::set_socks(&input.host, input.port).context("system proxy set")?;
    Ok(())
}

async fn system_proxy_clear() -> Result<()> {
    system_proxy::clear().context("system proxy clear")?;
    Ok(())
}

#[tokio::main]
async fn main() -> Result<()> {
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
        let read = stdin.read_line(&mut lines).await?;
        if read == 0 {
            break;
        }
        let text = lines.trim();
        if text.is_empty() {
            continue;
        }

        let req: RpcRequest = match serde_json::from_str(text) {
            Ok(v) => v,
            Err(_) => continue,
        };

        let out: Result<RpcResponseOk, anyhow::Error> = match req.method.as_str() {
            "runtime_capabilities" => {
                let caps = runtime_capabilities();
                Ok(RpcResponseOk { id: req.id, ok: true, result: serde_json::to_value(caps)? })
            }
            "runtime_config" => {
                let cfg = runtime_config()?;
                Ok(RpcResponseOk { id: req.id, ok: true, result: serde_json::to_value(cfg)? })
            }
            "resolve_node_host" => {
                let params: ResolveNodeHostRequest = serde_json::from_value(req.params)?;
                let resolved = resolve_node_host(params).await?;
                Ok(RpcResponseOk { id: req.id, ok: true, result: serde_json::to_value(resolved)? })
            }
            "xboard_request" => {
                let params: RpcParamsForXboardRequest = serde_json::from_value(req.params)?;
                let resp = xboard_request(params).await?;
                Ok(RpcResponseOk { id: req.id, ok: true, result: serde_json::to_value(resp)? })
            }
            "subscription_fetch" => {
                let resp = subscription_fetch(&req.params).await?;
                Ok(RpcResponseOk { id: req.id, ok: true, result: resp })
            }
            "aerion_test_node" => {
                let resp = aerion_test_node(&req.params).await?;
                Ok(RpcResponseOk { id: req.id, ok: true, result: resp })
            }
            "aerion_start_socks" => {
                let resp = aerion_start_socks(&req.params).await?;
                Ok(RpcResponseOk { id: req.id, ok: true, result: resp })
            }
            "aerion_start_vpn" => {
                let resp = aerion_start_vpn(&req.params).await?;
                Ok(RpcResponseOk { id: req.id, ok: true, result: resp })
            }
            "aerion_stop_vpn" => {
                let resp = aerion_stop_vpn(&req.params).await?;
                Ok(RpcResponseOk { id: req.id, ok: true, result: resp })
            }
            "aerion_stop" => {
                let resp = aerion_stop(&req.params).await?;
                Ok(RpcResponseOk { id: req.id, ok: true, result: resp })
            }
            "system_proxy_set" => {
                system_proxy_set(&req.params).await?;
                Ok(RpcResponseOk { id: req.id, ok: true, result: json!(null) })
            }
            "system_proxy_clear" => {
                system_proxy_clear().await?;
                Ok(RpcResponseOk { id: req.id, ok: true, result: json!(null) })
            }
            other => bail!("unsupported method: {other}"),
        };

        match out {
            Ok(ok) => emit_line(&serde_json::to_value(ok)?),
            Err(error) => {
                let err = error.to_string();
                emit_line(&serde_json::to_value(RpcResponseErr {
                    id: req.id,
                    ok: false,
                    error: err,
                })?)
            }
        }
    }

    Ok(())
}

