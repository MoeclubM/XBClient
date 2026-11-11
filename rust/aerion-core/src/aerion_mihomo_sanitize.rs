use anyhow::{Context, Result, bail, ensure};
use serde_yaml::{Mapping, Value};
use std::collections::BTreeSet;

const ROUTE_KEYS: &[&str] = &[
    "proxies",
    "proxy-groups",
    "proxy_groups",
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
    let mut kept_providers = Mapping::new();
    let mut stripped_providers = BTreeSet::new();
    for (key, value) in mapping {
        let Some(name) = mapping_key(&key) else {
            continue;
        };
        if name == "rule-providers" || name == "rule_providers" {
            partition_rule_providers(&value, &mut kept_providers, &mut stripped_providers)?;
            continue;
        }
        if ROUTE_KEYS.iter().any(|candidate| name == *candidate) {
            out.insert(key, value);
        }
    }
    if !kept_providers.is_empty() {
        out.insert(
            Value::String("rule-providers".into()),
            Value::Mapping(kept_providers),
        );
    }
    if !stripped_providers.is_empty()
        && let Some(Value::Sequence(rules)) = out.get_mut(Value::String("rules".into()))
    {
        *rules = rewrite_stripped_rule_sets(rules, &stripped_providers);
    }
    ensure!(
        out.contains_key(Value::String("rules".into()))
            || out.contains_key(Value::String("proxies".into())),
        "mihomo route config has no rules or proxies"
    );
    serde_yaml::to_string(&Value::Mapping(out)).context("encode sanitized mihomo route YAML")
}

fn partition_rule_providers(
    value: &Value,
    kept: &mut Mapping,
    stripped: &mut BTreeSet<String>,
) -> Result<()> {
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
        let format = string_field(fields, "format").unwrap_or_default();
        if provider_type.eq_ignore_ascii_case("http") || format.eq_ignore_ascii_case("mrs") {
            stripped.insert(name);
            continue;
        }
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
        kept.insert(key.clone(), provider.clone());
    }
    Ok(())
}

fn rewrite_stripped_rule_sets(rules: &[Value], stripped: &BTreeSet<String>) -> Vec<Value> {
    let mut has_cn_domain = rules.iter().any(|item| {
        mapping_key(item).is_some_and(|rule| {
            let upper = rule.trim().to_ascii_uppercase();
            upper.starts_with("DOMAIN-SUFFIX,CN,") || upper.starts_with("GEOSITE,CN,")
        })
    });
    let mut has_geoip_cn = rules.iter().any(|item| {
        mapping_key(item).is_some_and(|rule| {
            rule.trim()
                .to_ascii_uppercase()
                .starts_with("GEOIP,CN,")
        })
    });
    let mut cleaned = Vec::with_capacity(rules.len());
    for item in rules {
        let Some(raw) = mapping_key(item) else {
            continue;
        };
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            continue;
        }
        let parts: Vec<&str> = trimmed.split(',').map(str::trim).collect();
        if parts.len() >= 3 && parts[0].eq_ignore_ascii_case("RULE-SET") {
            let set_name = parts[1];
            let target = parts[2];
            if stripped.contains(set_name) {
                let lower = set_name.to_ascii_lowercase();
                if !has_cn_domain
                    && (lower == "cn-domain" || (lower.contains("cn") && lower.contains("domain")))
                {
                    cleaned.push(Value::String(format!("DOMAIN-SUFFIX,cn,{target}")));
                    has_cn_domain = true;
                } else if !has_geoip_cn
                    && (lower == "cn-ip" || (lower.contains("cn") && lower.contains("ip")))
                {
                    cleaned.push(Value::String(format!("GEOIP,CN,{target},no-resolve")));
                    has_geoip_cn = true;
                }
                continue;
            }
        }
        cleaned.push(Value::String(trimmed.to_string()));
    }
    cleaned
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_mrs_http_rule_providers_and_rewrites_cn_rules() -> Result<()> {
        let sanitized = sanitize_mihomo_route_yaml(
            r#"
proxies: []
rule-providers:
  cn-domain:
    type: http
    behavior: domain
    format: mrs
    url: https://example.com/cn-domain.mrs
    path: ./ruleset/cn-domain.mrs
  cn-ip:
    type: http
    behavior: ipcidr
    format: mrs
    url: https://example.com/cn-ip.mrs
    path: ./ruleset/cn-ip.mrs
  ads:
    type: http
    behavior: domain
    format: mrs
    url: https://example.com/ads.mrs
rules:
  - RULE-SET,ads,REJECT
  - RULE-SET,cn-domain,DIRECT
  - RULE-SET,cn-ip,DIRECT,no-resolve
  - MATCH,Proxy
"#,
        )?;
        assert!(!sanitized.contains("rule-providers"));
        assert!(!sanitized.contains("RULE-SET"));
        assert!(sanitized.contains("DOMAIN-SUFFIX,cn,DIRECT"));
        assert!(sanitized.contains("GEOIP,CN,DIRECT,no-resolve"));
        assert!(sanitized.contains("MATCH,Proxy"));
        Ok(())
    }
}
