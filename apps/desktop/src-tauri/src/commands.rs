use once_cell::sync::Lazy;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::net::IpAddr;
use std::time::Duration;

static HTTP_CLIENT: Lazy<reqwest::Client> = Lazy::new(|| {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(30))
        .timeout(Duration::from_secs(30))
        .build()
        .expect("build reqwest client")
});

#[derive(Serialize)]
pub struct XboardResponse {
    pub ok: bool,
    pub status: u16,
    pub body: Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Deserialize)]
pub struct XboardRequest {
    pub method: String,
    pub url: String,
    pub headers: Option<HashMap<String, String>>,
    pub body: Option<Value>,
}

#[derive(Serialize)]
pub struct RuntimeCapabilities {
    pub platform: &'static str,
    pub system_proxy: bool,
    pub autostart: bool,
    pub tray: bool,
    pub local_socks: bool,
    pub vpn: bool,
    pub payment: bool,
    pub admob: bool,
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

#[tauri::command]
pub fn runtime_capabilities() -> RuntimeCapabilities {
    RuntimeCapabilities {
        platform: platform_name(),
        system_proxy: cfg!(windows),
        autostart: cfg!(any(
            target_os = "windows",
            target_os = "macos",
            target_os = "linux"
        )),
        tray: cfg!(any(
            target_os = "windows",
            target_os = "macos",
            target_os = "linux"
        )),
        local_socks: true,
        vpn: false,
        payment: true,
        admob: false,
    }
}

#[tauri::command]
pub async fn resolve_node_host(dns_url: String, host: String) -> Result<String, String> {
    let host = host.trim();
    if host.parse::<IpAddr>().is_ok() {
        return Ok(host.to_string());
    }
    let resolver = dns_url.trim();
    if !resolver.starts_with("http://") && !resolver.starts_with("https://") {
        return Err("节点 DNS 必须是 DoH 地址。".to_string());
    }
    for record_type in ["A", "AAAA"] {
        let mut url =
            reqwest::Url::parse(resolver).map_err(|error| format!("节点 DNS 地址无效：{error}"))?;
        url.query_pairs_mut()
            .append_pair("name", host)
            .append_pair("type", record_type);
        let response = HTTP_CLIENT
            .get(url)
            .header("Accept", "application/dns-json, application/json")
            .send()
            .await
            .map_err(|error| format!("节点 DNS 请求失败：{error}"))?;
        let status = response.status();
        if !status.is_success() {
            return Err(format!("节点 DNS 请求失败：HTTP {}", status.as_u16()));
        }
        let body = response
            .json::<DohResponse>()
            .await
            .map_err(|error| format!("节点 DNS 响应不是 JSON：{error}"))?;
        if let Some(answer) = body.answer {
            for item in answer {
                if item.data.parse::<IpAddr>().is_ok() {
                    return Ok(item.data);
                }
            }
        }
    }
    Err("节点 DNS 无可用 A/AAAA 记录。".to_string())
}

#[tauri::command]
pub async fn xboard_request(request: XboardRequest) -> Result<XboardResponse, String> {
    let method = request
        .method
        .parse::<reqwest::Method>()
        .map_err(|error| format!("invalid HTTP method: {error}"))?;
    let mut builder = HTTP_CLIENT
        .request(method, &request.url)
        .header("Accept", "application/json");
    if let Some(headers) = request.headers {
        for (key, value) in headers {
            builder = builder.header(key, value);
        }
    }
    if let Some(body) = request.body {
        let bytes = serde_json::to_vec(&body).map_err(|error| error.to_string())?;
        builder = builder
            .body(bytes)
            .header("Content-Type", "application/json; charset=utf-8");
    }
    let response = builder.send().await.map_err(|error| error.to_string())?;
    let status = response.status().as_u16();
    let text = response.text().await.map_err(|error| error.to_string())?;
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

#[tauri::command]
pub async fn subscription_fetch(url: String, flag: String) -> Result<Value, String> {
    super::subscription::fetch(&HTTP_CLIENT, &url, &flag)
        .await
        .map_err(|error| format!("{error:#}"))
}

#[tauri::command]
pub async fn aerion_test_node(request: Value) -> Result<Value, String> {
    let input = serde_json::to_string(&request).map_err(|error| error.to_string())?;
    let output = aerion_core::test_node_from_json(&input)
        .await
        .map_err(|error| format!("{error:#}"))?;
    serde_json::from_str(&output).map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn aerion_start_socks(node: Value) -> Result<Value, String> {
    let input = serde_json::to_string(&serde_json::json!({ "node": node }))
        .map_err(|error| error.to_string())?;
    let output = aerion_core::start_socks_from_json(&input)
        .await
        .map_err(|error| format!("{error:#}"))?;
    serde_json::from_str(&output).map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn aerion_stop(session_id: u64) -> Result<Value, String> {
    let output = aerion_core::stop_socks(session_id)
        .await
        .map_err(|error| format!("{error:#}"))?;
    serde_json::from_str(&output).map_err(|error| error.to_string())
}

#[tauri::command]
pub fn system_proxy_set(host: String, port: u16) -> Result<(), String> {
    super::system_proxy::set_socks(&host, port).map_err(|error| format!("{error:#}"))
}

#[tauri::command]
pub fn system_proxy_clear() -> Result<(), String> {
    super::system_proxy::clear().map_err(|error| format!("{error:#}"))
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
