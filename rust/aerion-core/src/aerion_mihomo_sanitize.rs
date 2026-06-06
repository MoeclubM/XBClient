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
        if name == "rule-providers" || name == "rule_providers" {
            validate_inline_rule_providers(&value)?;
        }
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

fn validate_inline_rule_providers(value: &Value) -> Result<()> {
    let Value::Mapping(providers) = value else {
        bail!("mihomo route config rule-providers must be a mapping");
    };
    for (key, provider) in providers {
        let name = mapping_key(key).unwrap_or_else(|| "unnamed".to_string());
        let Value::Mapping(fields) = provider else {
            bail!("mihomo route rule-provider {name} must be a mapping");
        };
        let provider_type = string_field(fields, "type")
            .with_context(|| format!("mihomo route rule-provider {name} missing type"))?;
        ensure!(
            provider_type.eq_ignore_ascii_case("inline"),
            "mihomo route rule-provider {name} type {provider_type} is not supported"
        );
        string_field(fields, "behavior")
            .with_context(|| format!("mihomo route rule-provider {name} missing behavior"))?;
        ensure!(
            matches!(
                fields.get(Value::String("payload".into())),
                Some(Value::Sequence(_))
            ),
            "mihomo route rule-provider {name} payload must be an array"
        );
        for unsupported in ["path", "url", "format"] {
            ensure!(
                !fields.contains_key(Value::String(unsupported.into())),
                "mihomo route inline rule-provider {name} sets {unsupported}"
            );
        }
    }
    Ok(())
}

fn string_field(mapping: &Mapping, name: &str) -> Option<String> {
    mapping
        .get(Value::String(name.into()))
        .and_then(mapping_key)
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

fn mapping_key(key: &Value) -> Option<String> {
    match key {
        Value::String(value) => Some(value.clone()),
        Value::Number(value) => Some(value.to_string()),
        Value::Bool(value) => Some(value.to_string()),
        _ => None,
    }
}
