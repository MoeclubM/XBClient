use anyhow::{Context, Result, bail};
use serde_json::{Map, Value, json};

const SUBSCRIPTION_USER_AGENT: &str = "mihomo";
const SUBSCRIPTION_NODE_TYPES: &str =
    "anytls,hysteria,trojan,vless,vmess,mieru,naive,shadowsocks,tuic,http,socks5";

const SUPPORTED_TYPES: &[&str] = &[
    "anytls",
    "hysteria2",
    "hy2",
    "trojan",
    "vless",
    "vmess",
    "mieru",
    "mierus",
    "naive",
    "naive+https",
    "naive+quic",
    "tuic",
    "http",
    "socks",
    "socks5",
    "socks5h",
    "ss",
    "shadowsocks",
];

const SKIP_NAME_PREFIXES: &[&str] = &["剩余流量：", "距离下次重置剩余：", "套餐到期："];

pub async fn fetch(client: &reqwest::Client, url: &str, flag: &str) -> Result<Value> {
    let sing_box = flag.eq_ignore_ascii_case("sing-box");
    let request_url = with_subscription_query(url, flag)?;
    let user_agent = if sing_box {
        "sing-box"
    } else {
        SUBSCRIPTION_USER_AGENT
    };
    let accept = if sing_box {
        "application/json, text/plain, */*"
    } else {
        "text/yaml, application/yaml, text/plain, */*"
    };
    let response = client
        .get(request_url)
        .header("User-Agent", user_agent)
        .header("Accept", accept)
        .send()
        .await
        .context("fetch subscription")?;
    let status = response.status().as_u16();
    let subscription_userinfo = response
        .headers()
        .get("subscription-userinfo")
        .and_then(|value| value.to_str().ok())
        .map(str::to_string);
    let text = response.text().await.context("read subscription body")?;
    if !(200..300).contains(&status) {
        return Ok(json!({
            "ok": false,
            "status": status,
            "error": format!("HTTP {status}"),
            "body": text,
        }));
    }
    let nodes = if sing_box {
        parse_singbox(&text)?
    } else {
        parse_clash_meta(&text)?
    };
    Ok(json!({
        "ok": true,
        "status": status,
        "format": if sing_box { "sing-box" } else { "clashmeta" },
        "flag": flag,
        "subscription_userinfo": subscription_userinfo,
        "nodes": nodes,
    }))
}

fn with_subscription_query(url: &str, flag: &str) -> Result<reqwest::Url> {
    let mut parsed = reqwest::Url::parse(url).context("parse subscription URL")?;
    parsed
        .query_pairs_mut()
        .append_pair("types", SUBSCRIPTION_NODE_TYPES)
        .append_pair("flag", flag);
    Ok(parsed)
}

fn parse_clash_meta(text: &str) -> Result<Vec<Value>> {
    let root: Value = serde_yaml::from_str::<serde_yaml::Value>(text)
        .context("parse clash-meta YAML")
        .and_then(yaml_to_json)?;
    let proxies = root
        .get("proxies")
        .and_then(Value::as_array)
        .context("clash-meta subscription missing proxies list")?;
    let mut nodes = Vec::new();
    for proxy in proxies {
        let Some(raw_type) = proxy
            .get("type")
            .and_then(Value::as_str)
            .map(|value| value.to_ascii_lowercase())
        else {
            continue;
        };
        if !SUPPORTED_TYPES.contains(&raw_type.as_str()) {
            continue;
        }
        let name = proxy
            .get("name")
            .and_then(Value::as_str)
            .unwrap_or_default();
        if SKIP_NAME_PREFIXES
            .iter()
            .any(|prefix| name.starts_with(prefix))
        {
            continue;
        }
        nodes.push(normalize_proxy_node(proxy, &raw_type));
    }
    Ok(nodes)
}

fn parse_singbox(text: &str) -> Result<Vec<Value>> {
    let root: Value = serde_json::from_str(text).context("parse sing-box JSON")?;
    let outbounds = root
        .get("outbounds")
        .and_then(Value::as_array)
        .context("sing-box subscription missing outbounds array")?;
    let mut nodes = Vec::new();
    for outbound in outbounds {
        let Some(raw_type) = outbound
            .get("type")
            .and_then(Value::as_str)
            .map(|value| value.to_ascii_lowercase())
        else {
            continue;
        };
        if !SUPPORTED_TYPES.contains(&raw_type.as_str()) {
            continue;
        }
        nodes.push(normalize_outbound_node(outbound, &raw_type));
    }
    Ok(nodes)
}

fn normalize_proxy_node(raw: &Value, raw_type: &str) -> Value {
    let mut node = raw.clone();
    let protocol = canonical_protocol(raw_type);
    let raw_text = raw.to_string();
    let object = node.as_object_mut().expect("proxy entry must be object");
    object.insert("type".to_string(), Value::String(protocol.to_string()));
    object.insert("raw".to_string(), Value::String(raw_text));
    ensure_host_from_server(object);
    if raw_type == "naive+quic" {
        object.insert("quic".to_string(), Value::Bool(true));
    }
    node
}

fn normalize_outbound_node(raw: &Value, raw_type: &str) -> Value {
    let mut node = raw.clone();
    let protocol = canonical_protocol(raw_type);
    let raw_text = raw.to_string();
    let object = node.as_object_mut().expect("outbound entry must be object");
    if object
        .get("name")
        .and_then(Value::as_str)
        .map_or(true, str::is_empty)
    {
        if let Some(tag) = object
            .get("tag")
            .and_then(Value::as_str)
            .map(str::to_string)
            .filter(|value| !value.is_empty())
        {
            object.insert("name".to_string(), Value::String(tag));
        }
    }
    ensure_host_from_server(object);
    object.insert("type".to_string(), Value::String(protocol.to_string()));
    object.insert("raw".to_string(), Value::String(raw_text));
    node
}

fn canonical_protocol(raw_type: &str) -> &'static str {
    match raw_type {
        "hy2" => "hysteria2",
        "mierus" => "mieru",
        "naive+https" | "naive+quic" => "naive",
        "shadowsocks" => "ss",
        "socks" | "socks5" | "socks5h" => "socks5",
        "anytls" => "anytls",
        "hysteria2" => "hysteria2",
        "trojan" => "trojan",
        "vless" => "vless",
        "vmess" => "vmess",
        "mieru" => "mieru",
        "naive" => "naive",
        "tuic" => "tuic",
        "http" => "http",
        "ss" => "ss",
        _ => "unknown",
    }
}

fn ensure_host_from_server(object: &mut Map<String, Value>) {
    let needs_host = object
        .get("host")
        .and_then(Value::as_str)
        .map_or(true, str::is_empty);
    if !needs_host {
        return;
    }
    if let Some(server) = object
        .get("server")
        .and_then(Value::as_str)
        .map(str::to_string)
        .filter(|value| !value.is_empty())
    {
        object.insert("host".to_string(), Value::String(server));
    }
}

fn yaml_to_json(value: serde_yaml::Value) -> Result<Value> {
    match value {
        serde_yaml::Value::Null => Ok(Value::Null),
        serde_yaml::Value::Bool(value) => Ok(Value::Bool(value)),
        serde_yaml::Value::Number(value) => {
            if let Some(i) = value.as_i64() {
                Ok(Value::from(i))
            } else if let Some(u) = value.as_u64() {
                Ok(Value::from(u))
            } else if let Some(f) = value.as_f64() {
                serde_json::Number::from_f64(f)
                    .map(Value::Number)
                    .context("invalid YAML number")
            } else {
                bail!("unrecognized YAML number")
            }
        }
        serde_yaml::Value::String(value) => Ok(Value::String(value)),
        serde_yaml::Value::Sequence(items) => items
            .into_iter()
            .map(yaml_to_json)
            .collect::<Result<Vec<_>>>()
            .map(Value::Array),
        serde_yaml::Value::Mapping(mapping) => {
            let mut object = Map::new();
            for (key, value) in mapping {
                let key = match key {
                    serde_yaml::Value::String(value) => value,
                    serde_yaml::Value::Number(value) => value.to_string(),
                    serde_yaml::Value::Bool(value) => value.to_string(),
                    other => serde_yaml::to_string(&other)
                        .context("encode YAML key as string")?
                        .trim()
                        .to_string(),
                };
                object.insert(key, yaml_to_json(value)?);
            }
            Ok(Value::Object(object))
        }
        serde_yaml::Value::Tagged(tagged) => yaml_to_json(tagged.value),
    }
}
