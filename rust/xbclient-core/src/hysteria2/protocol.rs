use super::config::Hysteria2Config;
use anyhow::{Context, Result, bail, ensure};
use blake2::Blake2bVar;
use blake2::digest::{Update, VariableOutput};
use bytes::Bytes;
use quinn::udp::{RecvMeta, Transmit};
use quinn::{AsyncUdpSocket, ClientConfig, Endpoint, IdleTimeout, UdpPoller, VarInt};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, Error as RustlsError, RootCertStore, SignatureScheme};
use std::fmt;
use std::future::Future;
use std::io::{self, IoSliceMut};
use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::pin::Pin;
use std::sync::{Arc, Mutex as StdMutex};
use std::task::{Context as TaskContext, Poll, ready};
use std::time::Duration;
use tokio::io::AsyncReadExt;
use tokio::net::UdpSocket;
use tokio::task::JoinHandle;

#[cfg(target_os = "android")]
use jni::objects::{Global, JClass};

#[cfg(target_os = "android")]
use jni::{Env, JValue, JavaVM, jni_sig, jni_str};

#[cfg(target_os = "android")]
use once_cell::sync::Lazy;

#[cfg(target_os = "android")]
use std::os::fd::AsRawFd;

const H3_ALPN: &[u8] = b"h3";
const AUTH_URI: &str = "https://hysteria/auth";
const HY2_TCP_REQUEST_ID: u64 = 0x401;
const DEFAULT_QUIC_IDLE_TIMEOUT: Duration = Duration::from_secs(30);
const HY2_STREAM_RECEIVE_WINDOW: u32 = 8 * 1024 * 1024;
const HY2_CONN_RECEIVE_WINDOW: u32 = 20 * 1024 * 1024;
const HY2_DATAGRAM_BUFFER_SIZE: usize = 8 * 1024 * 1024;
const MAX_ADDRESS_LEN: u64 = 2048;
const MAX_PADDING_LEN: u64 = 4096;
const SALAMANDER_SALT_LEN: usize = 8;
const SALAMANDER_KEY_LEN: usize = 32;
const SALAMANDER_MIN_PASSWORD_LEN: usize = 4;

#[cfg(target_os = "android")]
static PASS_VPN_SERVICE_CLASS: Lazy<StdMutex<Option<Global<JClass<'static>>>>> =
    Lazy::new(|| StdMutex::new(None));

#[derive(Clone, Debug)]
pub enum SocksTarget {
    Ip(SocketAddr),
    Domain(String, u16),
}

impl SocksTarget {
    pub fn host_port(&self) -> String {
        match self {
            SocksTarget::Ip(addr) => addr.to_string(),
            SocksTarget::Domain(host, port) => format!("{host}:{port}"),
        }
    }
}

pub struct Hysteria2Client {
    endpoint: Endpoint,
    connection: quinn::Connection,
    h3_driver: JoinHandle<()>,
    h3_sender: h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>,
    pub udp_enabled: bool,
}

pub struct Hysteria2Stream {
    client: Hysteria2Client,
    send: quinn::SendStream,
    recv: quinn::RecvStream,
}

#[derive(Debug, Clone)]
struct SalamanderConfig {
    password: Vec<u8>,
}

#[derive(Debug)]
struct SalamanderUdpSocket {
    io: UdpSocket,
    password: Vec<u8>,
    recv_buffer: StdMutex<Vec<u8>>,
}

type IoFuture = Pin<Box<dyn Future<Output = io::Result<()>> + Send + Sync>>;

struct SalamanderUdpPoller {
    socket: Arc<SalamanderUdpSocket>,
    future: Option<IoFuture>,
}

struct InsecureVerifier;

impl fmt::Debug for InsecureVerifier {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
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

impl SalamanderConfig {
    fn new(password: &str) -> Result<Self> {
        let password = password.as_bytes().to_vec();
        ensure!(
            password.len() >= SALAMANDER_MIN_PASSWORD_LEN,
            "Hysteria2 salamander obfs password must be at least {SALAMANDER_MIN_PASSWORD_LEN} bytes"
        );
        Ok(Self { password })
    }
}

impl SalamanderUdpSocket {
    fn new(socket: std::net::UdpSocket, config: SalamanderConfig) -> io::Result<Self> {
        Ok(Self {
            io: UdpSocket::from_std(socket)?,
            password: config.password,
            recv_buffer: StdMutex::new(Vec::new()),
        })
    }
}

impl AsyncUdpSocket for SalamanderUdpSocket {
    fn create_io_poller(self: Arc<Self>) -> Pin<Box<dyn UdpPoller>> {
        Box::pin(SalamanderUdpPoller {
            socket: self,
            future: None,
        })
    }

    fn try_send(&self, transmit: &Transmit<'_>) -> io::Result<()> {
        let mut salt = [0u8; SALAMANDER_SALT_LEN];
        getrandom::fill(&mut salt)
            .map_err(|error| io::Error::other(format!("generate HY2 salamander salt: {error}")))?;
        let mut packet = vec![0u8; SALAMANDER_SALT_LEN + transmit.contents.len()];
        packet[..SALAMANDER_SALT_LEN].copy_from_slice(&salt);
        salamander_xor(
            &self.password,
            &salt,
            transmit.contents,
            &mut packet[SALAMANDER_SALT_LEN..],
        );
        let written = self.io.try_send_to(&packet, transmit.destination)?;
        if written == packet.len() {
            Ok(())
        } else {
            Err(io::Error::new(
                io::ErrorKind::WriteZero,
                "failed to write full HY2 salamander packet",
            ))
        }
    }

    fn poll_recv(
        &self,
        cx: &mut TaskContext<'_>,
        bufs: &mut [IoSliceMut<'_>],
        meta: &mut [RecvMeta],
    ) -> Poll<io::Result<usize>> {
        loop {
            ready!(self.io.poll_recv_ready(cx))?;
            let mut buffer = self
                .recv_buffer
                .lock()
                .expect("HY2 salamander recv buffer poisoned");
            buffer.resize(bufs[0].len() + SALAMANDER_SALT_LEN, 0);
            match self.io.try_recv_from(&mut buffer) {
                Ok((read, addr)) => {
                    if read <= SALAMANDER_SALT_LEN {
                        continue;
                    }
                    let mut salt = [0u8; SALAMANDER_SALT_LEN];
                    salt.copy_from_slice(&buffer[..SALAMANDER_SALT_LEN]);
                    let output_len = read - SALAMANDER_SALT_LEN;
                    salamander_xor(
                        &self.password,
                        &salt,
                        &buffer[SALAMANDER_SALT_LEN..read],
                        &mut bufs[0][..output_len],
                    );
                    meta[0] = RecvMeta {
                        addr,
                        len: output_len,
                        stride: output_len,
                        ecn: None,
                        dst_ip: None,
                    };
                    return Poll::Ready(Ok(1));
                }
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => continue,
                Err(error) => return Poll::Ready(Err(error)),
            }
        }
    }

    fn local_addr(&self) -> io::Result<SocketAddr> {
        self.io.local_addr()
    }
}

impl fmt::Debug for SalamanderUdpPoller {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SalamanderUdpPoller")
            .finish_non_exhaustive()
    }
}

impl UdpPoller for SalamanderUdpPoller {
    fn poll_writable(self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if this.future.is_none() {
            let socket = this.socket.clone();
            this.future = Some(Box::pin(async move { socket.io.writable().await }));
        }
        let result = this
            .future
            .as_mut()
            .expect("HY2 writable future set")
            .as_mut()
            .poll(cx);
        if result.is_ready() {
            this.future = None;
        }
        result
    }
}

impl Hysteria2Client {
    pub async fn connect(config: Hysteria2Config) -> Result<Self> {
        let config = config.normalized()?;
        let remote_addr = resolve_server_addr(&config.server, config.port)
            .await
            .with_context(|| {
                format!("resolve Hysteria2 server {}:{}", config.server, config.port)
            })?;
        let endpoint = build_endpoint(&config, remote_addr.is_ipv6())?;
        let server_name = config.effective_sni().to_string();
        let connection = endpoint
            .connect(remote_addr, &server_name)
            .with_context(|| format!("connect Hysteria2 server {remote_addr}"))?
            .await
            .context("complete Hysteria2 QUIC handshake")?;
        let (mut h3_driver, mut h3_sender) =
            h3::client::new(h3_quinn::Connection::new(connection.clone()))
                .await
                .context("initialize Hysteria2 HTTP/3 client")?;
        let h3_driver_handle = tokio::spawn(async move {
            let error = h3_driver.wait_idle().await;
            log::info!("Hysteria2 HTTP/3 driver exited: {error:?}");
        });
        let udp_enabled = authenticate(&mut h3_sender, &config)
            .await
            .context("authenticate Hysteria2 connection")?;
        Ok(Self {
            endpoint,
            connection,
            h3_driver: h3_driver_handle,
            h3_sender,
            udp_enabled,
        })
    }

    pub async fn open_tcp(self, target: SocksTarget) -> Result<Hysteria2Stream> {
        let address = target.host_port();
        let (mut send, mut recv) = self
            .connection
            .open_bi()
            .await
            .with_context(|| format!("open Hysteria2 TCP stream to {address}"))?;
        let mut request = Vec::new();
        encode_varint(HY2_TCP_REQUEST_ID, &mut request)?;
        encode_varint(address.len() as u64, &mut request)?;
        request.extend_from_slice(address.as_bytes());
        encode_varint(0, &mut request)?;
        send.write_all(&request)
            .await
            .context("write Hysteria2 TCP request")?;
        read_tcp_response(&mut recv)
            .await
            .with_context(|| format!("open Hysteria2 destination {address}"))?;
        Ok(Hysteria2Stream {
            client: self,
            send,
            recv,
        })
    }

    pub fn send_datagram(&self, packet: Vec<u8>) -> Result<()> {
        self.connection
            .send_datagram(Bytes::from(packet))
            .context("send Hysteria2 UDP datagram")
    }

    pub fn max_datagram_size(&self) -> Option<usize> {
        self.connection.max_datagram_size()
    }

    pub async fn read_datagram(&self) -> Result<Vec<u8>> {
        Ok(self
            .connection
            .read_datagram()
            .await
            .context("read Hysteria2 UDP datagram")?
            .to_vec())
    }
}

impl Drop for Hysteria2Client {
    fn drop(&mut self) {
        let _ = &self.endpoint;
        let _ = &self.h3_sender;
        self.connection.close(VarInt::from_u32(0), b"client closed");
        self.h3_driver.abort();
    }
}

impl Hysteria2Stream {
    pub async fn connect(config: Hysteria2Config, target: SocksTarget) -> Result<Self> {
        Hysteria2Client::connect(config)
            .await?
            .open_tcp(target)
            .await
    }

    pub async fn read_payload(&mut self) -> Result<Option<Vec<u8>>> {
        let mut buffer = vec![0u8; 32 * 1024];
        let Some(read) = self
            .recv
            .read(&mut buffer)
            .await
            .context("read Hysteria2 TCP payload")?
        else {
            return Ok(None);
        };
        if read == 0 {
            return Ok(None);
        }
        buffer.truncate(read);
        Ok(Some(buffer))
    }

    pub async fn write_payload(&mut self, payload: &[u8]) -> Result<()> {
        self.send
            .write_all(payload)
            .await
            .context("write Hysteria2 TCP payload")
    }

    pub fn into_parts(self) -> (quinn::SendStream, quinn::RecvStream, Hysteria2Client) {
        (self.send, self.recv, self.client)
    }
}

async fn authenticate(
    sender: &mut h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>,
    config: &Hysteria2Config,
) -> Result<bool> {
    let request = http::Request::builder()
        .method(http::Method::POST)
        .uri(AUTH_URI)
        .header("Hysteria-Auth", config.password.trim())
        .header("Hysteria-CC-RX", client_cc_rx(config))
        .header("Hysteria-Padding", "")
        .body(())
        .context("build Hysteria2 auth request")?;
    let mut stream = sender
        .send_request(request)
        .await
        .context("send Hysteria2 auth request")?;
    stream
        .finish()
        .await
        .context("finish Hysteria2 auth request")?;
    let response = stream
        .recv_response()
        .await
        .context("read Hysteria2 auth response")?;
    ensure!(
        response.status().as_u16() == 233,
        "Hysteria2 authentication rejected with HTTP {}",
        response.status()
    );
    Ok(response
        .headers()
        .get("Hysteria-UDP")
        .and_then(|value| value.to_str().ok())
        .map(|value| value.eq_ignore_ascii_case("true"))
        .unwrap_or(false))
}

fn client_cc_rx(config: &Hysteria2Config) -> String {
    config
        .download_bandwidth
        .map(|mbps| mbps.saturating_mul(125_000).to_string())
        .unwrap_or_else(|| "0".to_string())
}

fn build_endpoint(config: &Hysteria2Config, bind_ipv6: bool) -> Result<Endpoint> {
    let mut transport_config = quinn::TransportConfig::default();
    let idle_timeout =
        IdleTimeout::try_from(DEFAULT_QUIC_IDLE_TIMEOUT).context("build Hysteria2 idle timeout")?;
    transport_config
        .stream_receive_window(VarInt::from_u32(HY2_STREAM_RECEIVE_WINDOW))
        .receive_window(VarInt::from_u32(HY2_CONN_RECEIVE_WINDOW))
        .send_window(u64::from(HY2_CONN_RECEIVE_WINDOW))
        .max_idle_timeout(Some(idle_timeout))
        .datagram_receive_buffer_size(Some(HY2_DATAGRAM_BUFFER_SIZE))
        .datagram_send_buffer_size(HY2_DATAGRAM_BUFFER_SIZE);
    let tls = build_tls_config(config.insecure);
    let quic_tls = quinn::crypto::rustls::QuicClientConfig::try_from(tls)
        .context("build Hysteria2 QUIC TLS config")?;
    let mut client_config = ClientConfig::new(Arc::new(quic_tls));
    client_config.transport_config(Arc::new(transport_config));
    let socket = bind_udp_socket(bind_ipv6)?;
    if let Some(obfs) = salamander_config(config)? {
        let socket = SalamanderUdpSocket::new(socket, obfs)
            .context("wrap Hysteria2 salamander UDP socket")?;
        let mut endpoint = Endpoint::new_with_abstract_socket(
            quinn::EndpointConfig::default(),
            None,
            Arc::new(socket),
            Arc::new(quinn::TokioRuntime),
        )
        .context("bind Hysteria2 salamander UDP endpoint")?;
        endpoint.set_default_client_config(client_config);
        return Ok(endpoint);
    }
    let mut endpoint = Endpoint::new(
        quinn::EndpointConfig::default(),
        None,
        socket,
        Arc::new(quinn::TokioRuntime),
    )
    .context("bind Hysteria2 UDP endpoint")?;
    endpoint.set_default_client_config(client_config);
    Ok(endpoint)
}

fn build_tls_config(insecure: bool) -> Arc<rustls::ClientConfig> {
    let mut config = if insecure {
        rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(InsecureVerifier))
            .with_no_client_auth()
    } else {
        let mut roots = RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        rustls::ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth()
    };
    config.alpn_protocols = vec![H3_ALPN.to_vec()];
    Arc::new(config)
}

fn salamander_config(config: &Hysteria2Config) -> Result<Option<SalamanderConfig>> {
    let Some(obfs) = config.obfs.as_deref() else {
        return Ok(None);
    };
    if obfs.is_empty() {
        return Ok(None);
    }
    ensure!(
        obfs.eq_ignore_ascii_case("salamander"),
        "Hysteria2 obfs must be salamander"
    );
    let password = config
        .obfs_password
        .as_deref()
        .filter(|value| !value.is_empty())
        .context("Hysteria2 salamander obfs password is required")?;
    Ok(Some(SalamanderConfig::new(password)?))
}

#[cfg(not(target_os = "android"))]
fn bind_udp_socket(bind_ipv6: bool) -> Result<std::net::UdpSocket> {
    let socket = if bind_ipv6 {
        std::net::UdpSocket::bind(SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), 0)).or_else(
            |_| std::net::UdpSocket::bind(SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 0)),
        )
    } else {
        std::net::UdpSocket::bind("0.0.0.0:0")
    }
    .context("bind Hysteria2 UDP socket")?;
    socket
        .set_nonblocking(true)
        .context("set Hysteria2 UDP socket nonblocking")?;
    Ok(socket)
}

#[cfg(target_os = "android")]
fn bind_udp_socket(bind_ipv6: bool) -> Result<std::net::UdpSocket> {
    let socket = if bind_ipv6 {
        std::net::UdpSocket::bind(SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), 0))
    } else {
        std::net::UdpSocket::bind("0.0.0.0:0")
    }
    .context("bind protected Hysteria2 UDP socket")?;
    protect_android_socket(socket.as_raw_fd())?;
    socket
        .set_nonblocking(true)
        .context("set protected Hysteria2 UDP socket nonblocking")?;
    Ok(socket)
}

async fn resolve_server_addr(host: &str, port: u16) -> Result<SocketAddr> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(SocketAddr::new(ip, port));
    }
    tokio::net::lookup_host((host, port))
        .await
        .with_context(|| format!("resolve Hysteria2 server {host}:{port}"))?
        .next()
        .with_context(|| format!("Hysteria2 server resolved to no address: {host}:{port}"))
}

async fn read_tcp_response(recv: &mut quinn::RecvStream) -> Result<()> {
    let status = recv
        .read_u8()
        .await
        .context("read Hysteria2 TCP response status")?;
    let message_len = read_varint(recv).await?;
    ensure!(
        message_len <= MAX_ADDRESS_LEN,
        "Hysteria2 TCP response message too long"
    );
    let mut message = vec![0u8; message_len as usize];
    if message_len > 0 {
        recv.read_exact(&mut message)
            .await
            .context("read Hysteria2 TCP response message")?;
    }
    let padding_len = read_varint(recv).await?;
    ensure!(
        padding_len <= MAX_PADDING_LEN,
        "Hysteria2 TCP response padding too long"
    );
    discard_exact(recv, padding_len as usize).await?;
    if status != 0 {
        bail!(
            "Hysteria2 TCP stream rejected: {}",
            String::from_utf8_lossy(&message)
        );
    }
    Ok(())
}

pub fn encode_varint(value: u64, output: &mut Vec<u8>) -> Result<()> {
    if value < (1 << 6) {
        output.push(value as u8);
    } else if value < (1 << 14) {
        output.extend_from_slice(&((value as u16) | 0x4000).to_be_bytes());
    } else if value < (1 << 30) {
        output.extend_from_slice(&((value as u32) | 0x8000_0000).to_be_bytes());
    } else if value < (1 << 62) {
        output.extend_from_slice(&(value | 0xc000_0000_0000_0000).to_be_bytes());
    } else {
        bail!("Hysteria2 varint value is too large: {value}");
    }
    Ok(())
}

pub async fn read_varint<R>(reader: &mut R) -> Result<u64>
where
    R: AsyncReadExt + Unpin,
{
    let first = reader.read_u8().await.context("read Hysteria2 varint")?;
    let len = 1usize << (first >> 6);
    let mut value = u64::from(first & 0x3f);
    for _ in 1..len {
        value = (value << 8) | u64::from(reader.read_u8().await.context("read Hysteria2 varint")?);
    }
    Ok(value)
}

pub fn read_varint_from_slice(bytes: &mut &[u8]) -> Result<u64> {
    ensure!(!bytes.is_empty(), "Hysteria2 varint is truncated");
    let first = bytes[0];
    let len = 1usize << (first >> 6);
    ensure!(bytes.len() >= len, "Hysteria2 varint is truncated");
    let mut value = u64::from(first & 0x3f);
    for byte in &bytes[1..len] {
        value = (value << 8) | u64::from(*byte);
    }
    *bytes = &bytes[len..];
    Ok(value)
}

async fn discard_exact<R>(reader: &mut R, length: usize) -> Result<()>
where
    R: AsyncReadExt + Unpin,
{
    let mut remaining = length;
    let mut buffer = [0u8; 1024];
    while remaining > 0 {
        let take = remaining.min(buffer.len());
        reader
            .read_exact(&mut buffer[..take])
            .await
            .context("discard Hysteria2 padding")?;
        remaining -= take;
    }
    Ok(())
}

fn salamander_xor(
    password: &[u8],
    salt: &[u8; SALAMANDER_SALT_LEN],
    input: &[u8],
    output: &mut [u8],
) {
    debug_assert_eq!(input.len(), output.len());
    let key = salamander_key(password, salt);
    for (index, (plain, cipher)) in output.iter_mut().zip(input).enumerate() {
        *plain = *cipher ^ key[index % SALAMANDER_KEY_LEN];
    }
}

fn salamander_key(password: &[u8], salt: &[u8; SALAMANDER_SALT_LEN]) -> [u8; SALAMANDER_KEY_LEN] {
    let mut key = [0u8; SALAMANDER_KEY_LEN];
    let mut hasher = Blake2bVar::new(SALAMANDER_KEY_LEN).expect("valid BLAKE2b output length");
    hasher.update(password);
    hasher.update(salt);
    hasher
        .finalize_variable(&mut key)
        .expect("valid BLAKE2b output buffer length");
    key
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
        .map_err(|error| anyhow::anyhow!("protect Android UDP socket fd {fd}: {error}"))?;
    ensure!(protected, "Android VPN socket protection returned false");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn varint_roundtrip() {
        for value in [0, 63, 64, 16_383, 16_384, 1_073_741_823] {
            let mut encoded = Vec::new();
            encode_varint(value, &mut encoded).unwrap();
            let decoded = read_varint_from_slice(&mut encoded.as_slice()).unwrap();
            assert_eq!(decoded, value);
        }
    }

    #[test]
    fn salamander_roundtrip() {
        let salt = [7u8; SALAMANDER_SALT_LEN];
        let payload = b"hello hysteria2";
        let mut encrypted = vec![0u8; payload.len()];
        let mut decrypted = vec![0u8; payload.len()];
        salamander_xor(b"secret", &salt, payload, &mut encrypted);
        assert_ne!(encrypted, payload);
        salamander_xor(b"secret", &salt, &encrypted, &mut decrypted);
        assert_eq!(decrypted, payload);
    }
}
