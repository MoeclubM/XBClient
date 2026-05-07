use super::config::AnyTlsConfig;
use anyhow::{Context, Result};
use serde::Deserialize;

#[derive(Deserialize)]
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
struct TestNodeRequest {
    node: AnyTlsConfig,
    target_host: Option<String>,
    target_port: Option<u16>,
    target_tls: Option<bool>,
    timeout_ms: Option<u64>,
}

#[cfg(target_os = "android")]
mod platform {
    use super::*;
    use crate::anytls::protocol::{AnyTlsStream, SocksTarget};
    use rustls::pki_types::ServerName;
    use rustls::{ClientConfig, ClientConnection, RootCertStore};
    use serde_json::json;
    use std::io::{Cursor, ErrorKind, Read, Write};
    use std::sync::Arc;
    use std::time::Instant;
    use tokio::time::{Duration, timeout};

    pub async fn test(input: &str) -> Result<String> {
        let request: TestNodeRequest =
            serde_json::from_str(input).context("parse node test request")?;
        let target_host = request
            .target_host
            .filter(|value| !value.is_empty())
            .unwrap_or_else(|| "cp.cloudflare.com".to_string());
        let target_port = request.target_port.unwrap_or(80);
        let target_tls = request.target_tls.unwrap_or(target_port == 443);
        let timeout_duration = Duration::from_millis(request.timeout_ms.unwrap_or(8000));
        let (first_latency, second_latency) = test_connection(
            request.node,
            target_host.clone(),
            target_port,
            target_tls,
            timeout_duration,
        )
        .await
        .context("AnyTLS target test")?;
        Ok(json!({
            "ok": true,
            "latency_ms": second_latency,
            "first_latency_ms": first_latency,
            "target_host": target_host,
            "target_port": target_port,
            "target_tls": target_tls,
        })
        .to_string())
    }

    async fn test_connection(
        node: AnyTlsConfig,
        target_host: String,
        target_port: u16,
        target_tls: bool,
        timeout_duration: Duration,
    ) -> Result<(u64, u64)> {
        timeout(
            timeout_duration,
            test_connection_inner(node, target_host, target_port, target_tls),
        )
        .await
        .context("AnyTLS target test timed out")?
    }

    async fn test_connection_inner(
        node: AnyTlsConfig,
        target_host: String,
        target_port: u16,
        target_tls: bool,
    ) -> Result<(u64, u64)> {
        let target = SocksTarget::Domain(target_host.clone(), target_port);
        let mut stream = AnyTlsStream::connect(node, target.clone()).await?;
        let first_latency = if target_tls {
            probe_https(&mut stream, &target_host, target_port)
                .await
                .context("first HTTPS HEAD probe")?
        } else {
            probe_http(&mut stream, &target_host, target_port)
                .await
                .context("first HTTP HEAD probe")?
        };
        stream
            .close_payload()
            .await
            .context("close first AnyTLS test stream")?;
        stream
            .open_target(target)
            .await
            .context("open second AnyTLS test stream")?;
        let second_latency = if target_tls {
            probe_https(&mut stream, &target_host, target_port)
                .await
                .context("second HTTPS HEAD probe")?
        } else {
            probe_http(&mut stream, &target_host, target_port)
                .await
                .context("second HTTP HEAD probe")?
        };
        Ok((first_latency, second_latency))
    }

    async fn probe_http(
        stream: &mut AnyTlsStream,
        target_host: &str,
        target_port: u16,
    ) -> Result<u64> {
        let host_header = if target_port == 80 {
            target_host.to_string()
        } else {
            format!("{target_host}:{target_port}")
        };
        send_http_probe(stream, &host_header).await
    }

    async fn send_http_probe(stream: &mut AnyTlsStream, host_header: &str) -> Result<u64> {
        let request = format!(
            "HEAD / HTTP/1.1\r\nHost: {host_header}\r\nUser-Agent: XBClient\r\nConnection: close\r\n\r\n"
        );
        let started = Instant::now();
        stream
            .write_payload(request.as_bytes())
            .await
            .context("write HTTP probe request")?;
        let mut response = Vec::new();
        let mut latency = None;
        while response.len() < 4096 {
            let payload = stream
                .read_payload()
                .await?
                .context("target closed before HTTP response")?;
            if !payload.is_empty() && latency.is_none() {
                latency = Some(started.elapsed().as_millis() as u64);
            }
            response.extend_from_slice(&payload);
            let prefix_len = response.len().min(5);
            if prefix_len > 0 && response[..prefix_len] != b"HTTP/"[..prefix_len] {
                anyhow::bail!("target response is not HTTP");
            }
            if response.windows(4).any(|window| window == b"\r\n\r\n") {
                return latency.context("target returned an empty HTTP response");
            }
        }
        anyhow::bail!("target HTTP response header is too large")
    }

    async fn probe_https(
        stream: &mut AnyTlsStream,
        target_host: &str,
        target_port: u16,
    ) -> Result<u64> {
        let mut roots = RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let config = ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth();
        let server_name = ServerName::try_from(target_host.to_string())
            .with_context(|| format!("invalid HTTPS test target: {target_host}"))?;
        let mut tls = ClientConnection::new(Arc::new(config), server_name)
            .context("create HTTPS probe TLS client")?;
        while tls.is_handshaking() {
            flush_tls(&mut tls, stream).await?;
            if tls.wants_read() {
                read_tls(&mut tls, stream).await?;
            }
        }
        let host_header = if target_port == 443 {
            target_host.to_string()
        } else {
            format!("{target_host}:{target_port}")
        };
        send_https_probe(&mut tls, stream, &host_header).await
    }

    async fn send_https_probe(
        tls: &mut ClientConnection,
        stream: &mut AnyTlsStream,
        host_header: &str,
    ) -> Result<u64> {
        let request = format!(
            "HEAD / HTTP/1.1\r\nHost: {host_header}\r\nUser-Agent: XBClient\r\nConnection: close\r\n\r\n"
        );
        let started = Instant::now();
        tls.writer()
            .write_all(request.as_bytes())
            .context("write HTTPS probe request")?;
        flush_tls(tls, stream).await?;
        let mut response = Vec::new();
        let mut latency = None;
        while response.len() < 4096 {
            let mut buffer = [0u8; 1024];
            match tls.reader().read(&mut buffer) {
                Ok(0) => {}
                Ok(read) => {
                    if latency.is_none() {
                        latency = Some(started.elapsed().as_millis() as u64);
                    }
                    response.extend_from_slice(&buffer[..read]);
                    let prefix_len = response.len().min(5);
                    if prefix_len > 0 && response[..prefix_len] != b"HTTP/"[..prefix_len] {
                        anyhow::bail!("target HTTPS response is not HTTP");
                    }
                    if response.windows(4).any(|window| window == b"\r\n\r\n") {
                        return latency.context("target returned an empty HTTPS response");
                    }
                    continue;
                }
                Err(error) if error.kind() == ErrorKind::WouldBlock => {}
                Err(error) => return Err(error).context("read HTTPS probe response"),
            }
            if tls.wants_write() {
                flush_tls(tls, stream).await?;
            }
            read_tls(tls, stream).await?;
        }
        anyhow::bail!("target HTTPS response header is too large")
    }

    async fn flush_tls(tls: &mut ClientConnection, stream: &mut AnyTlsStream) -> Result<()> {
        while tls.wants_write() {
            let mut packet = Vec::new();
            tls.write_tls(&mut packet)
                .context("encode HTTPS probe TLS packet")?;
            if packet.is_empty() {
                break;
            }
            stream
                .write_payload(&packet)
                .await
                .context("write HTTPS probe TLS packet")?;
        }
        Ok(())
    }

    async fn read_tls(tls: &mut ClientConnection, stream: &mut AnyTlsStream) -> Result<()> {
        let packet = stream
            .read_payload()
            .await?
            .context("target closed during HTTPS probe")?;
        let mut cursor = Cursor::new(packet);
        tls.read_tls(&mut cursor)
            .context("read HTTPS probe TLS packet")?;
        tls.process_new_packets()
            .context("process HTTPS probe TLS packet")?;
        Ok(())
    }
}

#[cfg(not(target_os = "android"))]
mod platform {
    use super::*;

    pub async fn test(input: &str) -> Result<String> {
        let _request: TestNodeRequest =
            serde_json::from_str(input).context("parse node test request")?;
        anyhow::bail!("AnyTLS node test is only available on Android target builds")
    }
}

pub async fn test_node_from_json(input: &str) -> Result<String> {
    platform::test(input).await
}
