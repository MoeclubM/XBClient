use anyhow::{Context, Result, ensure};
use percent_encoding::percent_decode_str;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use url::Url;

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Hysteria2Config {
    pub name: String,
    pub raw: String,
    #[serde(default)]
    pub server: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub host: Option<String>,
    #[serde(alias = "server_port", alias = "server-port")]
    pub port: u16,
    pub password: String,
    #[serde(
        default,
        alias = "servername",
        alias = "peer",
        skip_serializing_if = "Option::is_none"
    )]
    pub sni: Option<String>,
    #[serde(default, alias = "skip-cert-verify")]
    pub insecure: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub obfs: Option<String>,
    #[serde(
        default,
        alias = "obfs-password",
        alias = "obfsPassword",
        skip_serializing_if = "Option::is_none"
    )]
    pub obfs_password: Option<String>,
    #[serde(
        default,
        alias = "down",
        alias = "download",
        alias = "down_mbps",
        alias = "down-mbps",
        skip_serializing_if = "Option::is_none"
    )]
    pub download_bandwidth: Option<u64>,
    #[serde(default)]
    pub udp: bool,
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
impl Hysteria2Config {
    pub fn normalized(mut self) -> Result<Self> {
        if self.server.trim().is_empty() {
            self.server = self
                .host
                .as_deref()
                .filter(|value| !value.trim().is_empty())
                .context("Hysteria2 server is required")?
                .trim()
                .to_string();
        } else {
            self.server = self.server.trim().to_string();
        }
        ensure!(self.port > 0, "Hysteria2 port is required");
        ensure!(
            !self.password.trim().is_empty(),
            "Hysteria2 password is required"
        );
        self.password = self.password.trim().to_string();
        self.sni = self
            .sni
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty());
        Ok(self)
    }

    pub fn effective_sni(&self) -> &str {
        self.sni.as_deref().unwrap_or(&self.server)
    }

    #[allow(dead_code)]
    pub fn from_uri(value: &str) -> Result<Self> {
        let url = Url::parse(value).with_context(|| format!("parse Hysteria2 URI: {value}"))?;
        ensure!(
            url.scheme() == "hysteria2" || url.scheme() == "hy2",
            "unsupported Hysteria2 URI scheme: {}",
            url.scheme()
        );
        let server = url
            .host_str()
            .context("Hysteria2 host is required")?
            .to_string();
        let port = url.port().context("Hysteria2 port is required")?;
        let password = percent_decode_str(url.username())
            .decode_utf8()
            .context("decode Hysteria2 password")?
            .to_string();
        ensure!(!password.is_empty(), "Hysteria2 password is required");
        let mut sni = None;
        let mut insecure = false;
        let mut obfs = None;
        let mut obfs_password = None;
        let mut download_bandwidth = None;
        for (key, value) in url.query_pairs() {
            match key.as_ref() {
                "sni" | "peer" | "servername" => {
                    if !value.is_empty() {
                        sni = Some(value.to_string());
                    }
                }
                "insecure" | "allowInsecure" => {
                    insecure = matches!(value.as_ref(), "1" | "true" | "TRUE" | "True");
                }
                "obfs" => {
                    if !value.is_empty() {
                        obfs = Some(value.to_string());
                    }
                }
                "obfs-password" | "obfsPassword" => {
                    if !value.is_empty() {
                        obfs_password = Some(value.to_string());
                    }
                }
                "down" | "download" => {
                    if let Ok(bw) = value.parse::<u64>() {
                        download_bandwidth = Some(bw);
                    }
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
            .unwrap_or_else(|| format!("{server}:{port}"));
        Ok(Self {
            name,
            raw: value.to_string(),
            server,
            host: None,
            port,
            password,
            sni,
            insecure,
            obfs,
            obfs_password,
            download_bandwidth,
            udp: true,
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
            proxy_type == "hysteria2",
            "unsupported Clash proxy type: {proxy_type}"
        );
        let server = string_field(value, "server")?.to_string();
        let port = match object
            .get("port")
            .or_else(|| object.get("server-port"))
            .or_else(|| object.get("server_port"))
            .context("Clash Hysteria2 port is required")?
        {
            Value::Number(number) => number
                .as_u64()
                .filter(|port| *port <= u16::MAX as u64)
                .context("Clash Hysteria2 port is out of range")?
                as u16,
            Value::String(text) => text.parse::<u16>().context("parse Clash Hysteria2 port")?,
            _ => anyhow::bail!("Clash Hysteria2 port must be a number or string"),
        };
        let password = string_field(value, "password")?.to_string();
        let sni = object
            .get("sni")
            .and_then(Value::as_str)
            .filter(|value| !value.is_empty())
            .map(|s| s.to_string());
        let obfs = match object.get("obfs") {
            Some(Value::String(value)) if !value.is_empty() => Some(value.to_string()),
            Some(Value::Object(value)) => value
                .get("type")
                .and_then(Value::as_str)
                .filter(|value| !value.is_empty())
                .map(|s| s.to_string()),
            _ => None,
        };
        let obfs_password = object
            .get("obfs-password")
            .or_else(|| object.get("obfs_password"))
            .or_else(|| object.get("obfsPassword"))
            .and_then(Value::as_str)
            .filter(|value| !value.is_empty())
            .map(|s| s.to_string())
            .or_else(|| {
                object
                    .get("obfs")
                    .and_then(Value::as_object)
                    .and_then(|obfs| obfs.get("password"))
                    .and_then(Value::as_str)
                    .filter(|value| !value.is_empty())
                    .map(|s| s.to_string())
            });
        let download_bandwidth = object
            .get("down")
            .or_else(|| object.get("download"))
            .or_else(|| object.get("down_mbps"))
            .or_else(|| object.get("down-mbps"))
            .and_then(Value::as_u64);
        Ok(Self {
            name: object
                .get("name")
                .and_then(Value::as_str)
                .filter(|value| !value.is_empty())
                .unwrap_or_else(|| server.as_str())
                .to_string(),
            raw: serde_json::to_string(value).context("serialize Clash Hysteria2 proxy")?,
            server,
            host: None,
            port,
            password,
            sni,
            insecure: object
                .get("skip-cert-verify")
                .or_else(|| object.get("insecure"))
                .and_then(Value::as_bool)
                .unwrap_or(false),
            obfs,
            obfs_password,
            download_bandwidth,
            udp: object.get("udp").and_then(Value::as_bool).unwrap_or(true),
        })
    }
}

#[allow(dead_code)]
fn string_field<'a>(value: &'a Value, key: &str) -> Result<&'a str> {
    value
        .get(key)
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty())
        .with_context(|| format!("Clash Hysteria2 {key} is required"))
}

#[cfg(test)]
mod tests {
    use super::Hysteria2Config;
    use serde_json::json;

    #[test]
    fn parses_hysteria2_uri() {
        let node = Hysteria2Config::from_uri(
            "hysteria2://abc@example.com:443?sni=edge.example.com&insecure=1&obfs=salamander&obfs-password=secret&down=100#Edge",
        )
        .unwrap();
        assert_eq!(node.password, "abc");
        assert_eq!(node.server, "example.com");
        assert_eq!(node.port, 443);
        assert_eq!(node.sni, Some("edge.example.com".to_string()));
        assert!(node.insecure);
        assert_eq!(node.obfs, Some("salamander".to_string()));
        assert_eq!(node.obfs_password, Some("secret".to_string()));
        assert_eq!(node.download_bandwidth, Some(100));
        assert_eq!(node.name, "Edge");
    }

    #[test]
    fn parses_clash_hysteria2_proxy() {
        let node = Hysteria2Config::from_clash_proxy(&json!({
            "name": "Edge",
            "type": "hysteria2",
            "server": "example.com",
            "port": 443,
            "password": "abc",
            "sni": "edge.example.com",
            "skip-cert-verify": true,
            "obfs": "salamander",
            "obfs-password": "secret",
            "down": 200
        }))
        .unwrap();
        assert_eq!(node.password, "abc");
        assert_eq!(node.server, "example.com");
        assert_eq!(node.port, 443);
        assert_eq!(node.sni, Some("edge.example.com".to_string()));
        assert!(node.insecure);
        assert_eq!(node.obfs, Some("salamander".to_string()));
        assert_eq!(node.obfs_password, Some("secret".to_string()));
        assert_eq!(node.download_bandwidth, Some(200));
        assert_eq!(node.name, "Edge");
    }

    #[test]
    fn parses_client_node_with_host_and_server() {
        let node = serde_json::from_value::<Hysteria2Config>(json!({
            "name": "Edge",
            "type": "hysteria2",
            "raw": "{}",
            "host": "edge.example.com",
            "server": "203.0.113.7",
            "port": 443,
            "password": "abc",
            "sni": "edge.example.com"
        }))
        .unwrap()
        .normalized()
        .unwrap();
        assert_eq!(node.server, "203.0.113.7");
        assert_eq!(node.host, Some("edge.example.com".to_string()));
        assert_eq!(node.effective_sni(), "edge.example.com");
    }

    #[test]
    fn hysteria2_uri_minimal() {
        let node = Hysteria2Config::from_uri("hysteria2://pass@host:8443").unwrap();
        assert_eq!(node.password, "pass");
        assert_eq!(node.server, "host");
        assert_eq!(node.port, 8443);
        assert_eq!(node.sni, None);
        assert!(!node.insecure);
        assert_eq!(node.obfs, None);
        assert_eq!(node.obfs_password, None);
        assert_eq!(node.download_bandwidth, None);
        assert_eq!(node.name, "host:8443");
    }
}
