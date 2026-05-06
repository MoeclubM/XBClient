use anyhow::{Context, Result, ensure};
use percent_encoding::percent_decode_str;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use url::Url;

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct AnyTlsConfig {
    pub name: String,
    pub raw: String,
    pub host: String,
    pub port: u16,
    pub password: String,
    pub sni: String,
    pub insecure: bool,
    #[serde(default)]
    pub udp: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ech: Option<Value>,
}

impl AnyTlsConfig {
    #[allow(dead_code)]
    pub fn from_uri(value: &str) -> Result<Self> {
        let url = Url::parse(value).with_context(|| format!("parse AnyTLS URI: {value}"))?;
        ensure!(
            url.scheme() == "anytls",
            "unsupported AnyTLS URI scheme: {}",
            url.scheme()
        );
        let host = url
            .host_str()
            .context("AnyTLS host is required")?
            .to_string();
        let port = url.port().context("AnyTLS port is required")?;
        let password = percent_decode_str(url.username())
            .decode_utf8()
            .context("decode AnyTLS password")?
            .to_string();
        ensure!(!password.is_empty(), "AnyTLS password is required");
        let mut sni = host.clone();
        let mut insecure = false;
        for (key, value) in url.query_pairs() {
            match key.as_ref() {
                "sni" | "peer" | "servername" => {
                    if !value.is_empty() {
                        sni = value.to_string();
                    }
                }
                "insecure" | "allowInsecure" => {
                    insecure = matches!(value.as_ref(), "1" | "true" | "TRUE" | "True");
                }
                _ => {}
            }
        }
        let name = url
            .fragment()
            .map(|fragment| {
                percent_decode_str(fragment)
                    .decode_utf8_lossy()
                    .into_owned()
            })
            .filter(|fragment| !fragment.is_empty())
            .unwrap_or_else(|| format!("{host}:{port}"));
        Ok(Self {
            name,
            raw: value.to_string(),
            host,
            port,
            password,
            sni,
            insecure,
            udp: false,
            ech: None,
        })
    }

    #[allow(dead_code)]
    pub fn from_clash_proxy(value: &Value) -> Result<Self> {
        let object = value.as_object().context("Clash proxy must be an object")?;
        let proxy_type = object
            .get("type")
            .and_then(Value::as_str)
            .context("Clash proxy type is required")?;
        ensure!(
            proxy_type == "anytls",
            "unsupported Clash proxy type: {proxy_type}"
        );
        let host = string_field(value, "server")?.to_string();
        let port = match object
            .get("port")
            .context("Clash AnyTLS port is required")?
        {
            Value::Number(number) => number
                .as_u64()
                .filter(|port| *port <= u16::MAX as u64)
                .context("Clash AnyTLS port is out of range")?
                as u16,
            Value::String(text) => text.parse::<u16>().context("parse Clash AnyTLS port")?,
            _ => anyhow::bail!("Clash AnyTLS port must be a number or string"),
        };
        let password = string_field(value, "password")?.to_string();
        ensure!(!password.is_empty(), "Clash AnyTLS password is required");
        let sni = object
            .get("sni")
            .and_then(Value::as_str)
            .filter(|value| !value.is_empty())
            .unwrap_or(&host)
            .to_string();
        Ok(Self {
            name: object
                .get("name")
                .and_then(Value::as_str)
                .filter(|value| !value.is_empty())
                .unwrap_or_else(|| host.as_str())
                .to_string(),
            raw: serde_json::to_string(value).context("serialize Clash AnyTLS proxy")?,
            host,
            port,
            password,
            sni,
            insecure: object
                .get("skip-cert-verify")
                .and_then(Value::as_bool)
                .unwrap_or(false),
            udp: object.get("udp").and_then(Value::as_bool).unwrap_or(false),
            ech: object.get("ech-opts").cloned(),
        })
    }
}

#[allow(dead_code)]
fn string_field<'a>(value: &'a Value, key: &str) -> Result<&'a str> {
    value
        .get(key)
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty())
        .with_context(|| format!("Clash AnyTLS {key} is required"))
}

#[cfg(test)]
mod tests {
    use super::AnyTlsConfig;
    use serde_json::json;

    #[test]
    fn parses_anytls_uri() {
        let node = AnyTlsConfig::from_uri(
            "anytls://abc@example.com:443?sni=edge.example.com&insecure=1#Edge",
        )
        .unwrap();
        assert_eq!(node.password, "abc");
        assert_eq!(node.host, "example.com");
        assert_eq!(node.port, 443);
        assert_eq!(node.sni, "edge.example.com");
        assert!(node.insecure);
        assert_eq!(node.name, "Edge");
    }

    #[test]
    fn parses_clash_anytls_proxy() {
        let node = AnyTlsConfig::from_clash_proxy(&json!({
            "name": "Edge",
            "type": "anytls",
            "server": "example.com",
            "port": 443,
            "password": "abc",
            "udp": true,
            "sni": "edge.example.com",
            "skip-cert-verify": true
        }))
        .unwrap();
        assert_eq!(node.password, "abc");
        assert_eq!(node.host, "example.com");
        assert_eq!(node.port, 443);
        assert_eq!(node.sni, "edge.example.com");
        assert!(node.insecure);
        assert!(node.udp);
        assert_eq!(node.name, "Edge");
    }
}
