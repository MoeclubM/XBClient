use super::config::AnyTlsConfig;
use super::protocol::{
    AnyTlsStream, CMD_FIN, CMD_PSH, MAX_FRAME_PAYLOAD_LEN, SocksTarget, write_frame,
};
use anyhow::{Context, Result, bail, ensure};
use once_cell::sync::Lazy;
use serde_json::json;
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, tcp::OwnedWriteHalf};
use tokio::sync::oneshot;
use tokio::task::JoinHandle;

static NEXT_SESSION_ID: AtomicU64 = AtomicU64::new(1);
static SOCKS_SESSIONS: Lazy<StdMutex<HashMap<u64, SocksSession>>> =
    Lazy::new(|| StdMutex::new(HashMap::new()));

struct SocksSession {
    shutdown: oneshot::Sender<()>,
    clients: Arc<StdMutex<Vec<JoinHandle<()>>>>,
}

enum SocksRequest {
    Connect(SocksTarget),
    UdpAssociate,
}

pub(crate) async fn start_socks(config: AnyTlsConfig, listen: String) -> Result<(u64, SocketAddr)> {
    let listener = TcpListener::bind(&listen)
        .await
        .with_context(|| format!("bind SOCKS listener on {listen}"))?;
    let local_addr = listener
        .local_addr()
        .context("read SOCKS listener address")?;
    let session_id = NEXT_SESSION_ID.fetch_add(1, Ordering::SeqCst);
    let clients = Arc::new(StdMutex::new(Vec::new()));
    let (shutdown_tx, shutdown_rx) = oneshot::channel();
    SOCKS_SESSIONS
        .lock()
        .expect("socks session map lock poisoned")
        .insert(
            session_id,
            SocksSession {
                shutdown: shutdown_tx,
                clients: clients.clone(),
            },
        );
    tokio::spawn(run_socks_server(
        session_id,
        listener,
        config,
        shutdown_rx,
        clients,
    ));
    Ok((session_id, local_addr))
}

pub(crate) async fn stop_socks(session_id: u64) -> Result<String> {
    let session = SOCKS_SESSIONS
        .lock()
        .expect("socks session map lock poisoned")
        .remove(&session_id)
        .with_context(|| format!("SOCKS session not found: {session_id}"))?;
    let _ = session.shutdown.send(());
    for client in session
        .clients
        .lock()
        .expect("socks client list lock poisoned")
        .drain(..)
    {
        client.abort();
    }
    Ok(json!({"ok": true, "session_id": session_id}).to_string())
}

async fn run_socks_server(
    session_id: u64,
    listener: TcpListener,
    config: AnyTlsConfig,
    mut shutdown_rx: oneshot::Receiver<()>,
    clients: Arc<StdMutex<Vec<JoinHandle<()>>>>,
) {
    loop {
        tokio::select! {
            _ = &mut shutdown_rx => break,
            accepted = listener.accept() => {
                match accepted {
                    Ok((stream, _)) => {
                        let config = config.clone();
                        let client = tokio::spawn(async move {
                            if let Err(error) = handle_socks_client(stream, config).await {
                                log::error!("SOCKS client error: {error:?}");
                            }
                        });
                        let mut clients = clients.lock().expect("socks client list lock poisoned");
                        clients.retain(|client| !client.is_finished());
                        clients.push(client);
                    }
                    Err(error) => {
                        log::error!("SOCKS listener error: {error:?}");
                        break;
                    },
                }
            }
        }
    }
    SOCKS_SESSIONS
        .lock()
        .expect("socks session map lock poisoned")
        .remove(&session_id);
    for client in clients
        .lock()
        .expect("socks client list lock poisoned")
        .drain(..)
    {
        client.abort();
    }
}

async fn handle_socks_client(mut stream: TcpStream, config: AnyTlsConfig) -> Result<()> {
    match read_socks_request(&mut stream).await? {
        SocksRequest::Connect(target) => {
            let remote = match AnyTlsStream::connect(config, target).await {
                Ok(remote) => remote,
                Err(error) => {
                    let _ = write_socks_reply(
                        &mut stream,
                        0x05,
                        SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0),
                    )
                    .await;
                    return Err(error);
                }
            };
            write_socks_reply(
                &mut stream,
                0x00,
                SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0),
            )
            .await?;
            relay(stream, remote).await
        }
        SocksRequest::UdpAssociate => super::socks_udp::relay_udp_associate(stream, config).await,
    }
}

async fn read_socks_request(stream: &mut TcpStream) -> Result<SocksRequest> {
    let mut header = [0u8; 2];
    stream
        .read_exact(&mut header)
        .await
        .context("read SOCKS greeting")?;
    ensure!(header[0] == 0x05, "only SOCKS5 is supported");
    let mut methods = vec![0u8; header[1] as usize];
    stream
        .read_exact(&mut methods)
        .await
        .context("read SOCKS methods")?;
    stream
        .write_all(&[0x05, 0x00])
        .await
        .context("write SOCKS method response")?;

    let mut request = [0u8; 4];
    stream
        .read_exact(&mut request)
        .await
        .context("read SOCKS connect request")?;
    ensure!(request[0] == 0x05, "invalid SOCKS version");
    let target = match request[3] {
        0x01 => {
            let mut ip = [0u8; 4];
            stream
                .read_exact(&mut ip)
                .await
                .context("read SOCKS IPv4 target")?;
            let port = read_port(stream).await?;
            SocksTarget::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(ip)), port))
        }
        0x03 => {
            let mut length = [0u8; 1];
            stream
                .read_exact(&mut length)
                .await
                .context("read SOCKS domain length")?;
            let mut host = vec![0u8; length[0] as usize];
            stream
                .read_exact(&mut host)
                .await
                .context("read SOCKS domain target")?;
            let port = read_port(stream).await?;
            SocksTarget::Domain(
                String::from_utf8(host).context("decode SOCKS domain")?,
                port,
            )
        }
        0x04 => {
            let mut ip = [0u8; 16];
            stream
                .read_exact(&mut ip)
                .await
                .context("read SOCKS IPv6 target")?;
            let port = read_port(stream).await?;
            SocksTarget::Ip(SocketAddr::new(IpAddr::V6(ip.into()), port))
        }
        other => bail!("unsupported SOCKS address type: {other}"),
    };
    match request[1] {
        0x01 => Ok(SocksRequest::Connect(target)),
        0x03 => Ok(SocksRequest::UdpAssociate),
        other => bail!("unsupported SOCKS command: {other}"),
    }
}

async fn read_port(stream: &mut TcpStream) -> Result<u16> {
    let mut port = [0u8; 2];
    stream
        .read_exact(&mut port)
        .await
        .context("read SOCKS target port")?;
    Ok(u16::from_be_bytes(port))
}

pub(super) async fn write_socks_reply(
    stream: &mut TcpStream,
    code: u8,
    bind: SocketAddr,
) -> Result<()> {
    let mut response = vec![0x05, code, 0x00];
    match bind {
        SocketAddr::V4(addr) => {
            response.push(0x01);
            response.extend_from_slice(&addr.ip().octets());
            response.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            response.push(0x04);
            response.extend_from_slice(&addr.ip().octets());
            response.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    stream
        .write_all(&response)
        .await
        .context("write SOCKS reply")
}

async fn relay(stream: TcpStream, remote: AnyTlsStream) -> Result<()> {
    let stream_id = remote.stream_id;
    let writer = remote.writer.clone();
    let (mut local_reader, local_writer) = stream.into_split();
    let uplink = async move {
        let mut buffer = vec![0u8; 32 * 1024];
        loop {
            let read = local_reader
                .read(&mut buffer)
                .await
                .context("read local SOCKS payload")?;
            if read == 0 {
                write_frame(&writer, CMD_FIN, stream_id, &[]).await?;
                return Ok::<(), anyhow::Error>(());
            }
            for chunk in buffer[..read].chunks(MAX_FRAME_PAYLOAD_LEN) {
                write_frame(&writer, CMD_PSH, stream_id, chunk).await?;
            }
        }
    };
    let downlink = write_remote_payloads(remote, local_writer);
    tokio::try_join!(uplink, downlink)?;
    Ok(())
}

async fn write_remote_payloads(
    mut remote: AnyTlsStream,
    mut local_writer: OwnedWriteHalf,
) -> Result<()> {
    while let Some(payload) = remote.read_payload().await? {
        local_writer
            .write_all(&payload)
            .await
            .context("write local SOCKS payload")?;
    }
    local_writer
        .shutdown()
        .await
        .context("shutdown local SOCKS writer")?;
    Ok(())
}
