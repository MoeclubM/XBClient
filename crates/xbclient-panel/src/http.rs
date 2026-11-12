use crate::config::normalize_base_url;
use anyhow::{Context, Result, anyhow, bail, ensure};
use neto_core::doh::SHARED_HTTP_CLIENT;
use reqwest::header::{ACCEPT, AUTHORIZATION, CONTENT_TYPE, HeaderMap, HeaderValue, USER_AGENT};
use serde_json::{Value, json};

#[derive(Clone)]
pub struct BatchCall {
    pub key: &'static str,
    pub service: &'static str,
    pub method: &'static str,
    pub input: Value,
}

pub fn public_url(base_url: &str, path_or_url: &str) -> String {
    let p = path_or_url.trim();
    if p.starts_with("http://") || p.starts_with("https://") {
        return p.to_string();
    }
    let base = normalize_base_url(base_url);
    if p.starts_with('/') {
        format!("{base}{p}")
    } else {
        format!("{base}/{p}")
    }
}

fn build_headers(user_agent: &str, bearer_or_raw: &str, is_pxboard: bool) -> Result<HeaderMap> {
    let mut headers = HeaderMap::new();
    headers.insert(ACCEPT, HeaderValue::from_static("application/json"));
    headers.insert(CONTENT_TYPE, HeaderValue::from_static("application/json"));
    let ua = if user_agent.trim().is_empty() {
        "Aerion"
    } else {
        user_agent.trim()
    };
    headers.insert(USER_AGENT, HeaderValue::from_str(ua)?);
    let auth = bearer_or_raw.trim();
    if !auth.is_empty() {
        let val = if is_pxboard {
            let tok = auth
                .strip_prefix("PxBoard ")
                .or_else(|| auth.strip_prefix("Bearer "))
                .unwrap_or(auth)
                .trim();
            format!("Bearer {tok}")
        } else {
            auth.to_string()
        };
        headers.insert(AUTHORIZATION, HeaderValue::from_str(&val)?);
    }
    Ok(headers)
}

pub async fn pxboard_rpc(
    base_url: &str,
    user_agent: &str,
    auth_data: &str,
    service: &str,
    method: &str,
    input: Value,
) -> Result<Value> {
    let base = normalize_base_url(base_url);
    ensure!(!base.is_empty(), "面板 API 地址未配置");
    let url = format!("{base}/rpc/v1/{service}/{method}");
    let headers = build_headers(user_agent, auth_data, true)?;
    let resp = SHARED_HTTP_CLIENT
        .post(&url)
        .headers(headers)
        .json(&input)
        .send()
        .await
        .with_context(|| format!("POST {url}"))?;
    let status = resp.status();
    let body: Value = resp.json().await.unwrap_or_else(|_| json!({}));
    if !status.is_success() {
        let msg = body
            .pointer("/error/message")
            .and_then(Value::as_str)
            .unwrap_or("");
        bail!("{}", if msg.is_empty() { format!("HTTP {}", status.as_u16()) } else { msg.to_string() });
    }
    body.get("data")
        .cloned()
        .ok_or_else(|| anyhow!("RPC 响应缺少 data 字段"))
}

pub async fn pxboard_batch(
    base_url: &str,
    user_agent: &str,
    auth_data: &str,
    calls: &[BatchCall],
) -> Result<Value> {
    let base = normalize_base_url(base_url);
    ensure!(!base.is_empty(), "面板 API 地址未配置");
    let url = format!("{base}/rpc/v1/$batch");
    let headers = build_headers(user_agent, auth_data, true)?;
    let payload = json!({
        "calls": calls.iter().map(|c| json!({
            "key": c.key,
            "service": c.service,
            "method": c.method,
            "input": c.input,
        })).collect::<Vec<_>>()
    });
    let resp = SHARED_HTTP_CLIENT
        .post(&url)
        .headers(headers)
        .json(&payload)
        .send()
        .await
        .with_context(|| format!("POST {url}"))?;
    let status = resp.status();
    let body: Value = resp.json().await.unwrap_or_else(|_| json!({}));
    if !status.is_success() {
        let msg = body
            .pointer("/error/message")
            .and_then(Value::as_str)
            .unwrap_or("");
        bail!("{}", if msg.is_empty() { format!("HTTP {}", status.as_u16()) } else { msg.to_string() });
    }
    body.get("data")
        .cloned()
        .ok_or_else(|| anyhow!("$batch 响应缺少 data 字段"))
}

pub async fn xboard_get(
    base_url: &str,
    user_agent: &str,
    auth_data: &str,
    path: &str,
    query: &[(&str, String)],
) -> Result<Value> {
    let base = normalize_base_url(base_url);
    ensure!(!base.is_empty(), "面板 API 地址未配置");
    let mut url = url::Url::parse(&format!("{base}{path}"))?;
    if !query.is_empty() {
        let mut qp = url.query_pairs_mut();
        for (k, v) in query {
            qp.append_pair(k, v);
        }
    }
    let mut headers = build_headers(user_agent, auth_data, false)?;
    if path == "/api/v1/admob/user/nodes" {
        headers.insert(
            "X-XBClient-Protocols",
            HeaderValue::from_static("sudoku,flux"),
        );
    }
    let resp = SHARED_HTTP_CLIENT
        .get(url.as_str())
        .headers(headers)
        .send()
        .await?;
    parse_xboard_response(resp).await
}

pub async fn xboard_post(
    base_url: &str,
    user_agent: &str,
    auth_data: &str,
    path: &str,
    body: Value,
) -> Result<Value> {
    let base = normalize_base_url(base_url);
    ensure!(!base.is_empty(), "面板 API 地址未配置");
    let url = format!("{base}{path}");
    let headers = build_headers(user_agent, auth_data, false)?;
    let resp = SHARED_HTTP_CLIENT
        .post(&url)
        .headers(headers)
        .json(&body)
        .send()
        .await?;
    parse_xboard_response(resp).await
}

async fn parse_xboard_response(resp: reqwest::Response) -> Result<Value> {
    let status = resp.status();
    let body: Value = resp.json().await.unwrap_or_else(|_| json!({}));
    if !status.is_success() || body.get("status").and_then(Value::as_str) == Some("fail") {
        let msg = body
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("");
        bail!("{}", if msg.is_empty() { format!("HTTP {}", status.as_u16()) } else { msg.to_string() });
    }
    Ok(body)
}

pub fn open_external_url(url: &str) -> Result<()> {
    let target = url.trim();
    ensure!(!target.is_empty(), "目标链接为空");
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("rundll32.exe")
            .args(["url.dll,FileProtocolHandler", target])
            .spawn()
            .context("launch browser on Windows")?;
    }
    #[cfg(target_os = "linux")]
    {
        std::process::Command::new("xdg-open")
            .arg(target)
            .spawn()
            .context("launch xdg-open on Linux")?;
    }
    #[cfg(not(any(target_os = "windows", target_os = "linux")))]
    {
        let _ = target;
    }
    Ok(())
}
