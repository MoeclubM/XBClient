use anyhow::{Context, Result, bail};
use serde_json::{Map, Value, json};

const SUBSCRIPTION_USER_AGENT: &str = "mihomo";
const SUBSCRIPTION_NODE_TYPES: &str =
    "anytls,nodeexpand,hysteria,trojan,vless,vmess,mieru,naive,shadowsocks,tuic,http,socks5,direct,block";

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
        .map(|value| {
            value
                .to_str()
                .context("subscription-userinfo header is not valid UTF-8")
                .map(str::to_string)
        })
        .transpose()?;
    let text = response.text().await.context("read subscription body")?;
    if !(200..300).contains(&status) {
        return Ok(json!({
            "ok": false,
            "status": status,
            "error": format!("HTTP {status}"),
            "body": text,
        }));
    }
    let routing = if sing_box {
        json!({
            "has_rules": false,
            "rule_count": 0,
            "proxy_group_count": 0,
            "rule_provider_count": 0,
            "rules_preview": [],
            "route_config_yaml": null,
        })
    } else {
        clash_routing_meta(&text)?
    };
    Ok(json!({
        "ok": true,
        "status": status,
        "format": if sing_box { "sing-box" } else { "clashmeta" },
        "flag": flag,
        "subscription_userinfo": subscription_userinfo,
        "routing": routing,
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

fn clash_routing_meta(text: &str) -> Result<Value> {
    let root: Value = serde_yaml::from_str::<serde_yaml::Value>(text)
        .context("parse clash-meta routing YAML")
        .and_then(yaml_to_json)?;
    let rules = root
        .get("rules")
        .and_then(Value::as_array)
        .cloned()
        .context("clash-meta routing YAML missing rules array")?;
    let mut rule_strings = Vec::with_capacity(rules.len());
    for rule in rules {
        let rule = rule
            .as_str()
            .context("clash-meta routing YAML rules item must be a string")?;
        rule_strings.push(rule.to_string());
    }
    let proxy_group_count = match root.get("proxy-groups") {
        None => 0,
        Some(Value::Array(items)) => items.len(),
        Some(_) => bail!("clash-meta routing YAML field proxy-groups must be an array"),
    };
    let rule_provider_count = match root.get("rule-providers") {
        None => 0,
        Some(Value::Object(items)) => items.len(),
        Some(_) => bail!("clash-meta routing YAML field rule-providers must be an object"),
    };
    let preview: Vec<&str> = rule_strings.iter().take(20).map(String::as_str).collect();
    Ok(json!({
        "has_rules": !rule_strings.is_empty(),
        "rule_count": rule_strings.len(),
        "proxy_group_count": proxy_group_count,
        "rule_provider_count": rule_provider_count,
        "rules_preview": preview,
        "route_config_yaml": if rule_strings.is_empty() { Value::Null } else { Value::String(text.to_string()) },
    }))
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
                    _ => bail!("YAML object key must be a string"),
                };
                object.insert(key, yaml_to_json(value)?);
            }
            Ok(Value::Object(object))
        }
        serde_yaml::Value::Tagged(tagged) => yaml_to_json(tagged.value),
    }
}
