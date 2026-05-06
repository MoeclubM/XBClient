use super::config::AnyTlsConfig;
use anyhow::{Context, Result, bail, ensure};
use md5::Md5;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{
    ClientConfig, DigitallySignedStruct, Error as RustlsError, RootCertStore, SignatureScheme,
};
use sha2::{Digest, Sha256};
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt, ReadHalf, WriteHalf, split};
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tokio_rustls::TlsConnector;
use tokio_rustls::client::TlsStream;

#[cfg(target_os = "android")]
use std::os::fd::AsRawFd;

#[cfg(target_os = "android")]
use jni::objects::{Global, JClass};

#[cfg(target_os = "android")]
use jni::{Env, JValue, JavaVM, jni_sig, jni_str};

#[cfg(target_os = "android")]
use once_cell::sync::Lazy;

#[cfg(target_os = "android")]
use std::sync::Mutex as StdMutex;

#[cfg(target_os = "android")]
use tokio::net::TcpSocket;

pub const CMD_PSH: u8 = 2;
pub const CMD_FIN: u8 = 3;
pub const MAX_FRAME_PAYLOAD_LEN: usize = u16::MAX as usize;

const CMD_WASTE: u8 = 0;
const CMD_SYN: u8 = 1;
const CMD_SETTINGS: u8 = 4;
const CMD_ALERT: u8 = 5;
const CMD_UPDATE_PADDING_SCHEME: u8 = 6;
const CMD_SYNACK: u8 = 7;
const CMD_HEART_REQUEST: u8 = 8;
const CMD_HEART_RESPONSE: u8 = 9;
const CMD_SERVER_SETTINGS: u8 = 10;
const FRAME_HEADER_LEN: usize = 7;
const PADDING_CHECKPOINT: isize = -1;
const ANYTLS_CLIENT_NAME: &str = "sing-anytls/0.0.11";
const DEFAULT_PADDING_SCHEME: &[&str] = &[
    "stop=8",
    "0=30-30",
    "1=100-400",
    "2=400-500,c,500-1000,c,500-1000,c,500-1000,c,500-1000",
    "3=9-9,500-1000",
    "4=500-1000",
    "5=500-1000",
    "6=500-1000",
    "7=500-1000",
];

#[cfg(target_os = "android")]
static PASS_VPN_SERVICE_CLASS: Lazy<StdMutex<Option<Global<JClass<'static>>>>> =
    Lazy::new(|| StdMutex::new(None));

#[derive(Clone)]
pub enum SocksTarget {
    Ip(SocketAddr),
    Domain(String, u16),
}

pub struct AnyTlsStream {
    reader: ReadHalf<TlsStream<TcpStream>>,
    pub writer: Arc<Mutex<AnyTlsWriter>>,
    pub stream_id: u32,
}

pub struct AnyTlsWriter {
    inner: WriteHalf<TlsStream<TcpStream>>,
    padding: PaddingScheme,
    packet_counter: u32,
    send_padding: bool,
}

#[derive(Clone)]
struct PaddingScheme {
    md5: String,
    stop: u32,
    rules: Vec<(u32, Vec<PaddingRule>)>,
}

#[derive(Clone)]
enum PaddingRule {
    Range(usize, usize),
    Checkpoint,
}

struct InsecureVerifier;

impl std::fmt::Debug for InsecureVerifier {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("InsecureVerifier")
    }
}

impl ServerCertVerifier for InsecureVerifier {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, RustlsError> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::RSA_PSS_SHA256,
            SignatureScheme::RSA_PSS_SHA384,
            SignatureScheme::RSA_PSS_SHA512,
            SignatureScheme::RSA_PKCS1_SHA256,
            SignatureScheme::RSA_PKCS1_SHA384,
            SignatureScheme::RSA_PKCS1_SHA512,
            SignatureScheme::ED25519,
        ]
    }
}

impl AnyTlsWriter {
    fn new(inner: WriteHalf<TlsStream<TcpStream>>) -> Self {
        Self {
            inner,
            padding: PaddingScheme::default(),
            packet_counter: 0,
            send_padding: true,
        }
    }

    async fn write_raw(&mut self, payload: &[u8], context: &'static str) -> Result<()> {
        self.inner.write_all(payload).await.context(context)?;
        self.inner.flush().await.context("flush AnyTLS writer")?;
        Ok(())
    }

    async fn write_packet(&mut self, mut payload: &[u8]) -> Result<()> {
        if self.send_padding {
            self.packet_counter = self.packet_counter.saturating_add(1);
            let packet = self.packet_counter;
            if packet < self.padding.stop {
                for size in self.padding.record_payload_sizes(packet)? {
                    if size == PADDING_CHECKPOINT {
                        if payload.is_empty() {
                            break;
                        }
                        continue;
                    }
                    let size = size as usize;
                    if payload.len() > size {
                        self.inner
                            .write_all(&payload[..size])
                            .await
                            .context("write AnyTLS padded payload chunk")?;
                        payload = &payload[size..];
                    } else if !payload.is_empty() {
                        if size > payload.len() + FRAME_HEADER_LEN {
                            let padding_len = size - payload.len() - FRAME_HEADER_LEN;
                            let padding = vec![0u8; padding_len];
                            let padding_frame = encode_frame(CMD_WASTE, 0, &padding);
                            let mut packet =
                                Vec::with_capacity(payload.len() + padding_frame.len());
                            packet.extend_from_slice(payload);
                            packet.extend_from_slice(&padding_frame);
                            self.inner
                                .write_all(&packet)
                                .await
                                .context("write AnyTLS padded payload")?;
                        } else {
                            self.inner
                                .write_all(payload)
                                .await
                                .context("write AnyTLS payload")?;
                        }
                        payload = &[];
                    } else {
                        let padding = vec![0u8; size];
                        let padding_frame = encode_frame(CMD_WASTE, 0, &padding);
                        self.inner
                            .write_all(&padding_frame)
                            .await
                            .context("write AnyTLS padding frame")?;
                    }
                }
                if payload.is_empty() {
                    self.inner.flush().await.context("flush AnyTLS packet")?;
                    return Ok(());
                }
            } else {
                self.send_padding = false;
            }
        }

        self.inner
            .write_all(payload)
            .await
            .context("write AnyTLS payload")?;
        self.inner.flush().await.context("flush AnyTLS packet")?;
        Ok(())
    }
}

impl Default for PaddingScheme {
    fn default() -> Self {
        Self::from_raw(&DEFAULT_PADDING_SCHEME.join("\n"))
            .expect("default AnyTLS padding scheme must be valid")
    }
}

impl PaddingScheme {
    fn from_raw(raw: &str) -> Result<Self> {
        let mut stop = None;
        let mut rules = Vec::new();
        for line in raw.lines().filter(|line| !line.trim().is_empty()) {
            let (key, value) = line
                .split_once('=')
                .with_context(|| format!("invalid AnyTLS padding line: {line}"))?;
            if key == "stop" {
                let parsed = value.parse::<u32>().context("parse AnyTLS padding stop")?;
                ensure!(parsed > 0, "AnyTLS padding stop must be positive");
                stop = Some(parsed);
                continue;
            }
            let packet = key
                .parse::<u32>()
                .with_context(|| format!("parse AnyTLS padding packet index: {key}"))?;
            let mut packet_rules = Vec::new();
            for rule in value.split(',') {
                if rule == "c" {
                    packet_rules.push(PaddingRule::Checkpoint);
                    continue;
                }
                let (min, max) = rule
                    .split_once('-')
                    .with_context(|| format!("invalid AnyTLS padding range: {rule}"))?;
                let mut min = min
                    .parse::<usize>()
                    .with_context(|| format!("parse AnyTLS padding range minimum: {rule}"))?;
                let mut max = max
                    .parse::<usize>()
                    .with_context(|| format!("parse AnyTLS padding range maximum: {rule}"))?;
                if min > max {
                    std::mem::swap(&mut min, &mut max);
                }
                ensure!(min > 0 && max > 0, "AnyTLS padding range must be positive");
                packet_rules.push(PaddingRule::Range(min, max));
            }
            rules.push((packet, packet_rules));
        }
        let stop = stop.context("AnyTLS padding scheme missing stop")?;
        Ok(Self {
            md5: hex::encode(Md5::digest(raw.as_bytes())),
            stop,
            rules,
        })
    }

    fn record_payload_sizes(&self, packet: u32) -> Result<Vec<isize>> {
        let Some((_, rules)) = self.rules.iter().find(|(index, _)| *index == packet) else {
            return Ok(Vec::new());
        };
        let mut sizes = Vec::with_capacity(rules.len());
        for rule in rules {
            match rule {
                PaddingRule::Checkpoint => sizes.push(PADDING_CHECKPOINT),
                PaddingRule::Range(min, max) if min == max => sizes.push(*min as isize),
                PaddingRule::Range(min, max) => {
                    let mut bytes = [0u8; 8];
                    getrandom::fill(&mut bytes)
                        .context("generate AnyTLS padding size randomness")?;
                    let span = (*max - *min) as u64;
                    sizes.push((*min + (u64::from_ne_bytes(bytes) % span) as usize) as isize);
                }
            }
        }
        Ok(sizes)
    }
}

impl AnyTlsStream {
    pub async fn connect(config: AnyTlsConfig, target: SocksTarget) -> Result<Self> {
        Self::connect_with_initial(config, target, &[]).await
    }

    pub async fn connect_with_initial(
        config: AnyTlsConfig,
        target: SocksTarget,
        initial_payload: &[u8],
    ) -> Result<Self> {
        Self::connect_inner(config, target, initial_payload, true).await
    }

    pub async fn connect_with_initial_without_synack(
        config: AnyTlsConfig,
        target: SocksTarget,
        initial_payload: &[u8],
    ) -> Result<Self> {
        Self::connect_inner(config, target, initial_payload, false).await
    }

    async fn connect_inner(
        config: AnyTlsConfig,
        target: SocksTarget,
        initial_payload: &[u8],
        wait_synack: bool,
    ) -> Result<Self> {
        let tcp_stream = connect_server_tcp(&config.host, config.port)
            .await
            .with_context(|| format!("connect AnyTLS server {}:{}", config.host, config.port))?;
        let _ = tcp_stream.set_nodelay(true);
        let connector = TlsConnector::from(build_client_config(config.insecure));
        let server_name = ServerName::try_from(config.sni.clone())
            .with_context(|| format!("invalid AnyTLS SNI: {}", config.sni))?;
        let tls_stream = connector
            .connect(server_name, tcp_stream)
            .await
            .context("TLS connect to AnyTLS server")?;
        let (reader, writer) = split(tls_stream);
        let writer = Arc::new(Mutex::new(AnyTlsWriter::new(writer)));
        send_auth_preface(&writer, &config.password).await?;
        let stream_id = 1;
        let mut first_payload = encode_target(&target)?;
        first_payload.extend_from_slice(initial_payload);
        send_initial_stream_open(&writer, stream_id, &first_payload).await?;
        let mut stream = Self {
            reader,
            writer,
            stream_id,
        };
        if wait_synack {
            stream.wait_synack().await?;
        }
        Ok(stream)
    }

    pub async fn read_payload(&mut self) -> Result<Option<Vec<u8>>> {
        loop {
            let (cmd, stream_id, payload) = self.read_frame().await?;
            match cmd {
                CMD_PSH if stream_id == self.stream_id => return Ok(Some(payload)),
                CMD_FIN if stream_id == self.stream_id => return Ok(None),
                CMD_ALERT => bail!("server alert: {}", String::from_utf8_lossy(&payload)),
                CMD_HEART_REQUEST => {
                    write_frame(&self.writer, CMD_HEART_RESPONSE, stream_id, &[]).await?
                }
                CMD_SYNACK if stream_id == self.stream_id && !payload.is_empty() => {
                    bail!("stream open failed: {}", String::from_utf8_lossy(&payload));
                }
                CMD_SYNACK if stream_id == self.stream_id => {}
                CMD_WASTE => {}
                CMD_SETTINGS | CMD_SERVER_SETTINGS | CMD_HEART_RESPONSE => {}
                CMD_UPDATE_PADDING_SCHEME => update_padding_scheme(&self.writer, &payload).await?,
                _ => {}
            }
        }
    }

    pub async fn write_payload(&self, payload: &[u8]) -> Result<()> {
        for chunk in payload.chunks(MAX_FRAME_PAYLOAD_LEN) {
            write_frame(&self.writer, CMD_PSH, self.stream_id, chunk).await?;
        }
        Ok(())
    }

    async fn wait_synack(&mut self) -> Result<()> {
        loop {
            let (cmd, stream_id, payload) = self.read_frame().await?;
            match cmd {
                CMD_SYNACK if stream_id == self.stream_id && payload.is_empty() => return Ok(()),
                CMD_SYNACK if stream_id == self.stream_id => {
                    bail!("stream open failed: {}", String::from_utf8_lossy(&payload));
                }
                CMD_ALERT => bail!("server alert: {}", String::from_utf8_lossy(&payload)),
                CMD_HEART_REQUEST => {
                    write_frame(&self.writer, CMD_HEART_RESPONSE, stream_id, &[]).await?
                }
                CMD_WASTE => {}
                CMD_SETTINGS | CMD_SERVER_SETTINGS | CMD_HEART_RESPONSE => {}
                CMD_UPDATE_PADDING_SCHEME => update_padding_scheme(&self.writer, &payload).await?,
                _ => {}
            }
        }
    }

    async fn read_frame(&mut self) -> Result<(u8, u32, Vec<u8>)> {
        let mut header = [0u8; 7];
        self.reader
            .read_exact(&mut header)
            .await
            .context("read AnyTLS frame header")?;
        let cmd = header[0];
        let stream_id = u32::from_be_bytes([header[1], header[2], header[3], header[4]]);
        let length = u16::from_be_bytes([header[5], header[6]]) as usize;
        let mut payload = vec![0u8; length];
        if length > 0 {
            self.reader
                .read_exact(&mut payload)
                .await
                .context("read AnyTLS frame payload")?;
        }
        Ok((cmd, stream_id, payload))
    }
}

#[cfg(not(target_os = "android"))]
async fn connect_server_tcp(host: &str, port: u16) -> Result<TcpStream> {
    Ok(TcpStream::connect((host, port)).await?)
}

#[cfg(target_os = "android")]
async fn connect_server_tcp(host: &str, port: u16) -> Result<TcpStream> {
    let mut last_error = None;
    for address in tokio::net::lookup_host((host, port))
        .await
        .with_context(|| format!("resolve AnyTLS server {host}:{port}"))?
    {
        let socket = if address.is_ipv4() {
            TcpSocket::new_v4().context("create protected IPv4 socket")?
        } else {
            TcpSocket::new_v6().context("create protected IPv6 socket")?
        };
        protect_android_socket(socket.as_raw_fd())?;
        match socket.connect(address).await {
            Ok(stream) => return Ok(stream),
            Err(error) => last_error = Some(error),
        }
    }
    if let Some(error) = last_error {
        return Err(error.into());
    }
    bail!("AnyTLS server resolved to no address: {host}:{port}")
}

#[cfg(target_os = "android")]
pub fn initialize_android(env: &Env<'_>, service_class: &JClass<'_>) -> jni::errors::Result<()> {
    let global_class = env.new_global_ref(service_class)?;
    *PASS_VPN_SERVICE_CLASS
        .lock()
        .expect("XbClientVpnService class lock poisoned") = Some(global_class);
    Ok(())
}

#[cfg(target_os = "android")]
fn protect_android_socket(fd: i32) -> Result<()> {
    let protected = JavaVM::singleton()
        .context("get Java VM for Android VPN socket protection")?
        .attach_current_thread(|env| -> Result<bool> {
            let service_class = PASS_VPN_SERVICE_CLASS
                .lock()
                .expect("XbClientVpnService class lock poisoned");
            let class = service_class
                .as_ref()
                .context("XbClientVpnService class has not been initialized")?;
            Ok(env
                .call_static_method(
                    class,
                    jni_str!("protectSocketFd"),
                    jni_sig!("(I)Z"),
                    &[JValue::Int(fd)],
                )?
                .z()?)
        })
        .map_err(|error| anyhow::anyhow!("protect Android socket fd {fd}: {error}"))?;
    ensure!(protected, "Android VPN socket protection returned false");
    Ok(())
}

pub async fn write_frame(
    writer: &Arc<Mutex<AnyTlsWriter>>,
    cmd: u8,
    stream_id: u32,
    payload: &[u8],
) -> Result<()> {
    ensure!(
        payload.len() <= MAX_FRAME_PAYLOAD_LEN,
        "AnyTLS payload too large"
    );
    let frame = encode_frame(cmd, stream_id, payload);
    let mut guard = writer.lock().await;
    guard
        .write_packet(&frame)
        .await
        .context("write AnyTLS frame")?;
    Ok(())
}

fn encode_frame(cmd: u8, stream_id: u32, payload: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(7 + payload.len());
    frame.push(cmd);
    frame.extend_from_slice(&stream_id.to_be_bytes());
    frame.extend_from_slice(&(payload.len() as u16).to_be_bytes());
    frame.extend_from_slice(payload);
    frame
}

fn build_client_config(insecure: bool) -> Arc<ClientConfig> {
    let mut config = if insecure {
        ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(InsecureVerifier))
            .with_no_client_auth()
    } else {
        let mut roots = RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth()
    };
    config.alpn_protocols.clear();
    Arc::new(config)
}

async fn send_auth_preface(writer: &Arc<Mutex<AnyTlsWriter>>, password: &str) -> Result<()> {
    let password_hash: [u8; 32] = Sha256::digest(password.as_bytes()).into();
    let mut guard = writer.lock().await;
    let padding_len = guard
        .padding
        .record_payload_sizes(0)?
        .first()
        .copied()
        .unwrap_or(0);
    ensure!(
        (0..=u16::MAX as isize).contains(&padding_len),
        "AnyTLS preface padding length out of range"
    );
    let padding = vec![0u8; padding_len as usize];
    let mut preface = Vec::with_capacity(34 + padding.len());
    preface.extend_from_slice(&password_hash);
    preface.extend_from_slice(&(padding_len as u16).to_be_bytes());
    preface.extend_from_slice(&padding);
    guard
        .write_raw(&preface, "write AnyTLS authentication preface")
        .await?;
    Ok(())
}

async fn send_initial_stream_open(
    writer: &Arc<Mutex<AnyTlsWriter>>,
    stream_id: u32,
    first_payload: &[u8],
) -> Result<()> {
    let padding_md5 = {
        let guard = writer.lock().await;
        guard.padding.md5.clone()
    };
    let settings = format!("v=2\nclient={ANYTLS_CLIENT_NAME}\npadding-md5={padding_md5}");
    let mut packet = Vec::new();
    packet.extend_from_slice(&encode_frame(CMD_SETTINGS, 0, settings.as_bytes()));
    packet.extend_from_slice(&encode_frame(CMD_SYN, stream_id, &[]));
    packet.extend_from_slice(&encode_frame(CMD_PSH, stream_id, first_payload));
    let mut guard = writer.lock().await;
    guard
        .write_packet(&packet)
        .await
        .context("write initial AnyTLS frames")?;
    Ok(())
}

async fn update_padding_scheme(writer: &Arc<Mutex<AnyTlsWriter>>, payload: &[u8]) -> Result<()> {
    let raw = std::str::from_utf8(payload).context("decode AnyTLS padding scheme update")?;
    let padding = PaddingScheme::from_raw(raw).context("parse AnyTLS padding scheme update")?;
    writer.lock().await.padding = padding;
    Ok(())
}

fn encode_target(target: &SocksTarget) -> Result<Vec<u8>> {
    let mut encoded = Vec::new();
    match target {
        SocksTarget::Ip(addr) => match addr.ip() {
            IpAddr::V4(ip) => {
                encoded.push(0x01);
                encoded.extend_from_slice(&ip.octets());
                encoded.extend_from_slice(&addr.port().to_be_bytes());
            }
            IpAddr::V6(ip) => {
                encoded.push(0x04);
                encoded.extend_from_slice(&ip.octets());
                encoded.extend_from_slice(&addr.port().to_be_bytes());
            }
        },
        SocksTarget::Domain(host, port) => {
            ensure!(host.len() <= u8::MAX as usize, "domain too long");
            encoded.push(0x03);
            encoded.push(host.len() as u8);
            encoded.extend_from_slice(host.as_bytes());
            encoded.extend_from_slice(&port.to_be_bytes());
        }
    }
    Ok(encoded)
}
