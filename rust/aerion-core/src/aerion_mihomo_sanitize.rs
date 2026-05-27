use anyhow::{Context, Result, bail, ensure};
use serde_yaml::{Mapping, Value};

const ROUTE_KEYS: &[&str] = &[
    "proxies",
    "proxy-groups",
    "proxy_groups",
    "rule-providers",
    "rule_providers",
    "rules",
    "ipv6",
];

pub fn sanitize_mihomo_route_yaml(text: &str) -> Result<String> {
    let root: Value = serde_yaml::from_str(text).context("parse mihomo route YAML")?;
    let mapping = match root {
        Value::Mapping(mapping) => mapping,
        _ => bail!("mihomo route config root must be a mapping"),
    };
    let mut out = Mapping::new();
    for (key, value) in mapping {
        let Some(name) = mapping_key(&key) else {
            continue;
        };
        if ROUTE_KEYS.iter().any(|candidate| name == *candidate) {
            out.insert(key, value);
        }
    }
    ensure!(
        out.contains_key(Value::String("rules".into()))
            || out.contains_key(Value::String("proxies".into())),
        "mihomo route config has no rules or proxies"
    );
    serde_yaml::to_string(&Value::Mapping(out)).context("encode sanitized mihomo route YAML")
}

fn mapping_key(key: &Value) -> Option<String> {
    match key {
        Value::String(value) => Some(value.clone()),
        Value::Number(value) => Some(value.to_string()),
        Value::Bool(value) => Some(value.to_string()),
        _ => None,
    }
}
