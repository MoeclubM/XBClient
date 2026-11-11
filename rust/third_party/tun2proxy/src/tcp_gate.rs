use crate::{SocketQueue, create_tcp_stream, virtual_dns::VirtualDns};
use socks5_impl::protocol::{
    self, Address, AsyncStreamOperation, AuthMethod, Command, StreamOperation, UserKey, handshake,
    password_method,
};
use std::{
    collections::HashMap,
    io,
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr},
    pin::Pin,
    sync::{Arc, Mutex as StdMutex},
    task::{Context, Poll},
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf},
    net::TcpStream,
    sync::{Mutex, mpsc},
};
use tokio_util::sync::CancellationToken;

const READY_STREAM_TTL: Duration = Duration::from_secs(30);
const ABORTED_FLOW_TTL: Duration = Duration::from_secs(15);
const ACTIVE_FLOW_IDLE_TTL: Duration = Duration::from_secs(600);

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
struct FlowKey {
    src: SocketAddr,
    dst: SocketAddr,
}

struct PendingSyn {
    packet: Vec<u8>,
    seq: u32,
}

struct ReadyStream {
    stream: TcpStream,
    created_at: Instant,
}

struct FlowState {
    server_next_seq: u32,
    client_next_seq: u32,
    last_ack_to_client: u32,
    has_server_seq: bool,
    aborted: bool,
    updated_at: Instant,
}

#[derive(Default)]
struct GateInner {
    pending_syns: HashMap<FlowKey, PendingSyn>,
    ready_streams: HashMap<FlowKey, ReadyStream>,
    flows: HashMap<FlowKey, FlowState>,
    op_count: u64,
}

pub(crate) struct TcpGate {
    server_addr: SocketAddr,
    credentials: Option<UserKey>,
    dial_timeout: Duration,
    virtual_dns: Option<Arc<Mutex<VirtualDns>>>,
    socket_queue: Option<Arc<SocketQueue>>,
    to_dev_tx: mpsc::UnboundedSender<Vec<u8>>,
    to_stack_tx: mpsc::UnboundedSender<Vec<u8>>,
    inner: StdMutex<GateInner>,
}

pub(crate) struct StackDevice {
    rx: mpsc::UnboundedReceiver<Vec<u8>> ,
    to_dev_tx: mpsc::UnboundedSender<Vec<u8>>,
    gate: Arc<TcpGate>,
}

impl AsyncRead for StackDevice {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        match self.rx.poll_recv(cx) {
            Poll::Pending => Poll::Pending,
            Poll::Ready(None) => Poll::Ready(Ok(())),
            Poll::Ready(Some(pkt)) => {
                let len = pkt.len().min(buf.remaining());
                buf.put_slice(&pkt[..len]);
                Poll::Ready(Ok(()))
            }
        }
    }
}

impl AsyncWrite for StackDevice {
    fn poll_write(
        self: Pin<&mut Self>,
        _cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        if self.gate.observe_stack_egress(buf) {
            let _ = self.to_dev_tx.send(buf.to_vec());
        }
        Poll::Ready(Ok(buf.len()))
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }
}

pub(crate) fn attach<D>(
    device: D,
    mtu: u16,
    server_addr: SocketAddr,
    credentials: Option<UserKey>,
    tcp_timeout_secs: u64,
    virtual_dns: Option<Arc<Mutex<VirtualDns>>>,
    socket_queue: Option<Arc<SocketQueue>>,
    shutdown_token: CancellationToken,
) -> (StackDevice, Arc<TcpGate>)
where
    D: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let (to_stack_tx, to_stack_rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let (to_dev_tx, mut to_dev_rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let dial_secs = tcp_timeout_secs.clamp(5, 25);
    let gate = Arc::new(TcpGate {
        server_addr,
        credentials,
        dial_timeout: Duration::from_secs(dial_secs),
        virtual_dns,
        socket_queue,
        to_dev_tx: to_dev_tx.clone(),
        to_stack_tx,
        inner: StdMutex::new(GateInner::default()),
    });

    let (mut dev_rx, mut dev_tx) = tokio::io::split(device);
    let reader_gate = gate.clone();
    let reader_shutdown = shutdown_token.clone();
    let buf_len = (mtu as usize).max(65536);
    tokio::spawn(async move {
        let mut buf = vec![0u8; buf_len];
        loop {
            let n = tokio::select! {
                _ = reader_shutdown.cancelled() => break,
                res = dev_rx.read(&mut buf) => match res {
                    Ok(0) | Err(_) => break,
                    Ok(n) => n,
                }
            };
            reader_gate.handle_device_ingress(&buf[..n], &reader_shutdown);
        }
    });

    let writer_shutdown = shutdown_token;
    tokio::spawn(async move {
        loop {
            let pkt = tokio::select! {
                _ = writer_shutdown.cancelled() => break,
                pkt = to_dev_rx.recv() => match pkt {
                    Some(pkt) => pkt,
                    None => break,
                }
            };
            if dev_tx.write(&pkt).await.is_err() {
                break;
            }
        }
    });

    let stack_device = StackDevice {
        rx: to_stack_rx,
        to_dev_tx,
        gate: gate.clone(),
    };
    (stack_device, gate)
}

impl TcpGate {
    pub(crate) fn take_ready_stream(&self, src: SocketAddr, dst: SocketAddr) -> Option<TcpStream> {
        let mut inner = self.inner.lock().expect("tcp gate lock poisoned");
        inner
            .ready_streams
            .remove(&FlowKey { src, dst })
            .map(|entry| entry.stream)
    }

    pub(crate) fn abort_with_rst(&self, src: SocketAddr, dst: SocketAddr) {
        let key = FlowKey { src, dst };
        let rst_params = {
            let mut inner = self.inner.lock().expect("tcp gate lock poisoned");
            inner.ready_streams.remove(&key);
            if let Some(flow) = inner.flows.get_mut(&key) {
                flow.aborted = true;
                flow.updated_at = Instant::now();
                flow.has_server_seq.then_some((
                    flow.server_next_seq,
                    if flow.last_ack_to_client != 0 {
                        flow.last_ack_to_client
                    } else {
                        flow.client_next_seq
                    },
                ))
            } else {
                inner.flows.insert(
                    key,
                    FlowState {
                        server_next_seq: 0,
                        client_next_seq: 0,
                        last_ack_to_client: 0,
                        has_server_seq: false,
                        aborted: true,
                        updated_at: Instant::now(),
                    },
                );
                None
            }
        };
        if let Some((seq, ack)) = rst_params
            && let Some(pkt) = build_tcp_rst_packet(dst, src, seq, ack)
        {
            let _ = self.to_dev_tx.send(pkt);
        }
    }

    fn handle_device_ingress(self: &Arc<Self>, pkt: &[u8], shutdown_token: &CancellationToken) {
        let Some(parsed) = parse_tcp_packet(pkt) else {
            let _ = self.to_stack_tx.send(pkt.to_vec());
            return;
        };
        let key = FlowKey {
            src: parsed.src,
            dst: parsed.dst,
        };
        let is_initial_syn = (parsed.flags & 0x17) == 0x02;
        if is_initial_syn {
            let should_dial = {
                let mut inner = self.inner.lock().expect("tcp gate lock poisoned");
                inner.op_count = inner.op_count.wrapping_add(1);
                if (inner.op_count & 0xff) == 0 {
                    prune_stale(&mut inner);
                }
                if inner.flows.get(&key).is_some_and(|flow| flow.aborted) {
                    inner.flows.remove(&key);
                    inner.ready_streams.remove(&key);
                }
                if inner.ready_streams.contains_key(&key) || inner.flows.contains_key(&key) {
                    let _ = self.to_stack_tx.send(pkt.to_vec());
                    false
                } else if let Some(pending) = inner.pending_syns.get_mut(&key) {
                    pending.packet = pkt.to_vec();
                    pending.seq = parsed.seq;
                    false
                } else {
                    inner.pending_syns.insert(
                        key,
                        PendingSyn {
                            packet: pkt.to_vec(),
                            seq: parsed.seq,
                        },
                    );
                    true
                }
            };
            if should_dial {
                let gate = self.clone();
                let shutdown = shutdown_token.clone();
                tokio::spawn(async move {
                    tokio::select! {
                        _ = shutdown.cancelled() => {
                            let mut inner = gate.inner.lock().expect("tcp gate lock poisoned");
                            inner.pending_syns.remove(&key);
                        }
                        _ = gate.dial_and_resolve_syn(key) => {}
                    }
                });
            }
            return;
        }

        {
            let mut inner = self.inner.lock().expect("tcp gate lock poisoned");
            if let Some(flow) = inner.flows.get_mut(&key) {
                if flow.aborted {
                    return;
                }
                let mut advance = parsed.payload_len as u32;
                if (parsed.flags & 0x01) != 0 {
                    advance = advance.wrapping_add(1);
                }
                let end_seq = parsed.seq.wrapping_add(advance);
                if seq_gt(end_seq, flow.client_next_seq) {
                    flow.client_next_seq = end_seq;
                }
                flow.updated_at = Instant::now();
                if (parsed.flags & 0x04) != 0 {
                    inner.flows.remove(&key);
                    inner.ready_streams.remove(&key);
                }
            }
        }
        let _ = self.to_stack_tx.send(pkt.to_vec());
    }

    fn observe_stack_egress(&self, pkt: &[u8]) -> bool {
        let Some(parsed) = parse_tcp_packet(pkt) else {
            return true;
        };
        let key = FlowKey {
            src: parsed.dst,
            dst: parsed.src,
        };
        let mut inner = self.inner.lock().expect("tcp gate lock poisoned");
        if let Some(flow) = inner.flows.get_mut(&key) {
            if flow.aborted {
                return false;
            }
            let mut advance = parsed.payload_len as u32;
            if (parsed.flags & 0x02) != 0 {
                advance = advance.wrapping_add(1);
            }
            if (parsed.flags & 0x01) != 0 {
                advance = advance.wrapping_add(1);
            }
            let end_seq = parsed.seq.wrapping_add(advance);
            if !flow.has_server_seq || seq_gt(end_seq, flow.server_next_seq) {
                flow.server_next_seq = end_seq;
                flow.has_server_seq = true;
            }
            if (parsed.flags & 0x10) != 0 {
                flow.last_ack_to_client = parsed.ack;
            }
            flow.updated_at = Instant::now();
            if (parsed.flags & 0x04) != 0 {
                inner.flows.remove(&key);
            }
        }
        true
    }

    async fn dial_and_resolve_syn(&self, key: FlowKey) {
        let domain_name = if let Some(virtual_dns) = &self.virtual_dns {
            let mut vdns = virtual_dns.lock().await;
            vdns.touch_ip(&key.dst.ip());
            vdns.resolve_ip(&key.dst.ip()).cloned()
        } else {
            None
        };

        let outcome = tokio::time::timeout(
            self.dial_timeout,
            dial_socks5_connect(
                &self.socket_queue,
                self.server_addr,
                key,
                domain_name,
                self.credentials.as_ref(),
            ),
        )
        .await;

        let pending = {
            let mut inner = self.inner.lock().expect("tcp gate lock poisoned");
            prune_stale(&mut inner);
            inner.pending_syns.remove(&key)
        };
        let Some(pending) = pending else {
            return;
        };

        match outcome {
            Ok(Ok(SocksDialOutcome::Connected(stream))) => {
                let client_next = pending.seq.wrapping_add(1);
                {
                    let mut inner = self.inner.lock().expect("tcp gate lock poisoned");
                    inner.ready_streams.insert(
                        key,
                        ReadyStream {
                            stream,
                            created_at: Instant::now(),
                        },
                    );
                    inner.flows.insert(
                        key,
                        FlowState {
                            server_next_seq: 0,
                            client_next_seq: client_next,
                            last_ack_to_client: client_next,
                            has_server_seq: false,
                            aborted: false,
                            updated_at: Instant::now(),
                        },
                    );
                }
                let _ = self.to_stack_tx.send(pending.packet);
            }
            Ok(Ok(SocksDialOutcome::Reply(code))) => match code {
                0x05 | 0x02 | 0x07 => {
                    if let Some(rst) =
                        build_tcp_rst_packet(key.dst, key.src, 0, pending.seq.wrapping_add(1))
                    {
                        let _ = self.to_dev_tx.send(rst);
                    }
                }
                0x03 => {
                    if let Some(icmp) = build_icmp_unreachable_packet(&pending.packet, false) {
                        let _ = self.to_dev_tx.send(icmp);
                    }
                }
                0x04 | 0x08 => {
                    if let Some(icmp) = build_icmp_unreachable_packet(&pending.packet, true) {
                        let _ = self.to_dev_tx.send(icmp);
                    }
                }
                _ => {}
            },
            Ok(Err(err)) if err.kind() == io::ErrorKind::ConnectionRefused => {
                if let Some(rst) =
                    build_tcp_rst_packet(key.dst, key.src, 0, pending.seq.wrapping_add(1))
                {
                    let _ = self.to_dev_tx.send(rst);
                }
            }
            Ok(Err(_)) | Err(_) => {}
        }
    }
}

enum SocksDialOutcome {
    Connected(TcpStream),
    Reply(u8),
}

async fn dial_socks5_connect(
    socket_queue: &Option<Arc<SocketQueue>>,
    server_addr: SocketAddr,
    key: FlowKey,
    domain_name: Option<String>,
    credentials: Option<&UserKey>,
) -> io::Result<SocksDialOutcome> {
    let mut server = create_tcp_stream(socket_queue, server_addr).await?;
    let _ = server.set_nodelay(true);

    let mut methods = vec![AuthMethod::NoAuth];
    if credentials.is_some() {
        methods.push(AuthMethod::UserPass);
    }
    let mut out = Vec::with_capacity(64);
    handshake::Request::new(methods).write_to_buf(&mut out);
    server.write_all(&out).await?;

    let hs = handshake::Response::retrieve_from_async_stream(&mut server).await?;
    match hs.method {
        AuthMethod::NoAuth => {}
        AuthMethod::UserPass => {
            let creds = credentials.ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "SOCKS5 server requires credentials",
                )
            })?;
            const SESSION_INFO_MARKER: &str = "+info";
            let username = if let Some(base) = creds.username.strip_suffix(SESSION_INFO_MARKER) {
                format!("{base}|tcp|{}|{}", key.src.ip(), key.src.port())
            } else {
                creds.username.clone()
            };
            out.clear();
            password_method::Request::new(username, &creds.password).write_to_buf(&mut out);
            server.write_all(&out).await?;
            let auth_resp =
                password_method::Response::retrieve_from_async_stream(&mut server).await?;
            if auth_resp.status != password_method::Status::Succeeded {
                return Ok(SocksDialOutcome::Reply(0x02));
            }
        }
        _ => return Ok(SocksDialOutcome::Reply(0x02)),
    }

    let target = match domain_name {
        Some(domain) => Address::DomainAddress(domain, key.dst.port()),
        None => Address::SocketAddress(key.dst),
    };
    out.clear();
    protocol::Request::new(Command::Connect, target).write_to_buf(&mut out);
    server.write_all(&out).await?;

    let resp = protocol::Response::retrieve_from_async_stream(&mut server).await?;
    if resp.reply == protocol::Reply::Succeeded {
        Ok(SocksDialOutcome::Connected(server))
    } else {
        Ok(SocksDialOutcome::Reply(resp.reply.0))
    }
}

fn prune_stale(inner: &mut GateInner) {
    let now = Instant::now();
    inner
        .ready_streams
        .retain(|_, entry| now.duration_since(entry.created_at) < READY_STREAM_TTL);
    inner.flows.retain(|_, flow| {
        let ttl = if flow.aborted {
            ABORTED_FLOW_TTL
        } else {
            ACTIVE_FLOW_IDLE_TTL
        };
        now.duration_since(flow.updated_at) < ttl
    });
}

#[inline]
fn seq_gt(a: u32, b: u32) -> bool {
    (a.wrapping_sub(b) as i32) > 0
}

struct ParsedTcp {
    src: SocketAddr,
    dst: SocketAddr,
    seq: u32,
    ack: u32,
    flags: u8,
    payload_len: usize,
}

fn parse_tcp_packet(pkt: &[u8]) -> Option<ParsedTcp> {
    let first = *pkt.first()?;
    let (src_ip, dst_ip, l4) = match first >> 4 {
        4 => {
            if pkt.len() < 20 {
                return None;
            }
            let ihl = ((first & 0x0f) as usize) * 4;
            let total_len = u16::from_be_bytes([pkt[2], pkt[3]]) as usize;
            if ihl < 20 || total_len < ihl || pkt.len() < total_len {
                return None;
            }
            let frag = u16::from_be_bytes([pkt[6], pkt[7]]);
            if pkt[9] != 6 || (frag & 0x3fff) != 0 {
                return None;
            }
            let src = IpAddr::V4(Ipv4Addr::new(pkt[12], pkt[13], pkt[14], pkt[15]));
            let dst = IpAddr::V4(Ipv4Addr::new(pkt[16], pkt[17], pkt[18], pkt[19]));
            (src, dst, &pkt[ihl..total_len])
        }
        6 => {
            if pkt.len() < 40 {
                return None;
            }
            let payload_len = u16::from_be_bytes([pkt[4], pkt[5]]) as usize;
            let total_len = 40 + payload_len;
            if pkt.len() < total_len || pkt[6] != 6 {
                return None;
            }
            let src = IpAddr::V6(Ipv6Addr::from(<[u8; 16]>::try_from(&pkt[8..24]).ok()?));
            let dst = IpAddr::V6(Ipv6Addr::from(<[u8; 16]>::try_from(&pkt[24..40]).ok()?));
            (src, dst, &pkt[40..total_len])
        }
        _ => return None,
    };
    if l4.len() < 20 {
        return None;
    }
    let data_offset = ((l4[12] >> 4) as usize) * 4;
    if data_offset < 20 || l4.len() < data_offset {
        return None;
    }
    let src_port = u16::from_be_bytes([l4[0], l4[1]]);
    let dst_port = u16::from_be_bytes([l4[2], l4[3]]);
    let seq = u32::from_be_bytes([l4[4], l4[5], l4[6], l4[7]]);
    let ack = u32::from_be_bytes([l4[8], l4[9], l4[10], l4[11]]);
    let flags = l4[13];
    Some(ParsedTcp {
        src: SocketAddr::new(src_ip, src_port),
        dst: SocketAddr::new(dst_ip, dst_port),
        seq,
        ack,
        flags,
        payload_len: l4.len() - data_offset,
    })
}

pub(crate) fn build_tcp_rst_packet(
    src: SocketAddr,
    dst: SocketAddr,
    seq: u32,
    ack: u32,
) -> Option<Vec<u8>> {
    let mut tcp = [0u8; 20];
    tcp[0..2].copy_from_slice(&src.port().to_be_bytes());
    tcp[2..4].copy_from_slice(&dst.port().to_be_bytes());
    tcp[4..8].copy_from_slice(&seq.to_be_bytes());
    tcp[8..12].copy_from_slice(&ack.to_be_bytes());
    tcp[12] = 0x50;
    tcp[13] = 0x14; // RST | ACK

    match (src, dst) {
        (SocketAddr::V4(s4), SocketAddr::V4(d4)) => {
            let mut pseudo = [0u8; 12];
            pseudo[0..4].copy_from_slice(&s4.ip().octets());
            pseudo[4..8].copy_from_slice(&d4.ip().octets());
            pseudo[9] = 6;
            pseudo[10..12].copy_from_slice(&20u16.to_be_bytes());
            let tcp_csum = internet_checksum(&[&pseudo, &tcp]);
            tcp[16..18].copy_from_slice(&tcp_csum.to_be_bytes());

            let mut ip = [0u8; 20];
            ip[0] = 0x45;
            ip[2..4].copy_from_slice(&40u16.to_be_bytes());
            ip[6..8].copy_from_slice(&0x4000u16.to_be_bytes());
            ip[8] = 64;
            ip[9] = 6;
            ip[12..16].copy_from_slice(&s4.ip().octets());
            ip[16..20].copy_from_slice(&d4.ip().octets());
            let ip_csum = internet_checksum(&[&ip]);
            ip[10..12].copy_from_slice(&ip_csum.to_be_bytes());

            let mut pkt = Vec::with_capacity(40);
            pkt.extend_from_slice(&ip);
            pkt.extend_from_slice(&tcp);
            Some(pkt)
        }
        (SocketAddr::V6(s6), SocketAddr::V6(d6)) => {
            let mut pseudo = [0u8; 40];
            pseudo[0..16].copy_from_slice(&s6.ip().octets());
            pseudo[16..32].copy_from_slice(&d6.ip().octets());
            pseudo[32..36].copy_from_slice(&20u32.to_be_bytes());
            pseudo[39] = 6;
            let tcp_csum = internet_checksum(&[&pseudo, &tcp]);
            tcp[16..18].copy_from_slice(&tcp_csum.to_be_bytes());

            let mut ip6 = [0u8; 40];
            ip6[0] = 0x60;
            ip6[4..6].copy_from_slice(&20u16.to_be_bytes());
            ip6[6] = 6;
            ip6[7] = 64;
            ip6[8..24].copy_from_slice(&s6.ip().octets());
            ip6[24..40].copy_from_slice(&d6.ip().octets());

            let mut pkt = Vec::with_capacity(60);
            pkt.extend_from_slice(&ip6);
            pkt.extend_from_slice(&tcp);
            Some(pkt)
        }
        _ => None,
    }
}

pub(crate) fn build_icmp_unreachable_packet(
    orig_pkt: &[u8],
    host_unreachable: bool,
) -> Option<Vec<u8>> {
    let first = *orig_pkt.first()?;
    match first >> 4 {
        4 => {
            if orig_pkt.len() < 20 {
                return None;
            }
            let ihl = ((first & 0x0f) as usize) * 4;
            if ihl < 20 || orig_pkt.len() < ihl {
                return None;
            }
            let quote_len = orig_pkt.len().min(ihl + 28);
            let quoted = &orig_pkt[..quote_len];
            let mut icmp_hdr = [0u8; 8];
            icmp_hdr[0] = 3; // Destination Unreachable
            icmp_hdr[1] = if host_unreachable { 1 } else { 0 };
            let icmp_csum = internet_checksum(&[&icmp_hdr, quoted]);
            icmp_hdr[2..4].copy_from_slice(&icmp_csum.to_be_bytes());

            let total_len = (20 + 8 + quote_len) as u16;
            let mut ip = [0u8; 20];
            ip[0] = 0x45;
            ip[2..4].copy_from_slice(&total_len.to_be_bytes());
            ip[8] = 64;
            ip[9] = 1; // ICMPv4
            ip[12..16].copy_from_slice(&orig_pkt[16..20]);
            ip[16..20].copy_from_slice(&orig_pkt[12..16]);
            let ip_csum = internet_checksum(&[&ip]);
            ip[10..12].copy_from_slice(&ip_csum.to_be_bytes());

            let mut pkt = Vec::with_capacity(total_len as usize);
            pkt.extend_from_slice(&ip);
            pkt.extend_from_slice(&icmp_hdr);
            pkt.extend_from_slice(quoted);
            Some(pkt)
        }
        6 => {
            if orig_pkt.len() < 40 {
                return None;
            }
            let quote_len = orig_pkt.len().min(1280 - 48);
            let quoted = &orig_pkt[..quote_len];
            let icmp_len = (8 + quote_len) as u32;
            let mut icmp_hdr = [0u8; 8];
            icmp_hdr[0] = 1; // Destination Unreachable
            icmp_hdr[1] = if host_unreachable { 3 } else { 0 };

            let mut pseudo = [0u8; 40];
            pseudo[0..16].copy_from_slice(&orig_pkt[24..40]);
            pseudo[16..32].copy_from_slice(&orig_pkt[8..24]);
            pseudo[32..36].copy_from_slice(&icmp_len.to_be_bytes());
            pseudo[39] = 58; // ICMPv6
            let icmp_csum = internet_checksum(&[&pseudo, &icmp_hdr, quoted]);
            icmp_hdr[2..4].copy_from_slice(&icmp_csum.to_be_bytes());

            let mut ip6 = [0u8; 40];
            ip6[0] = 0x60;
            ip6[4..6].copy_from_slice(&(icmp_len as u16).to_be_bytes());
            ip6[6] = 58;
            ip6[7] = 64;
            ip6[8..24].copy_from_slice(&orig_pkt[24..40]);
            ip6[24..40].copy_from_slice(&orig_pkt[8..24]);

            let mut pkt = Vec::with_capacity(40 + icmp_len as usize);
            pkt.extend_from_slice(&ip6);
            pkt.extend_from_slice(&icmp_hdr);
            pkt.extend_from_slice(quoted);
            Some(pkt)
        }
        _ => None,
    }
}

fn internet_checksum(chunks: &[&[u8]]) -> u16 {
    let mut sum: u32 = 0;
    let mut trailing: Option<u8> = None;
    for chunk in chunks {
        let mut slice = *chunk;
        if let Some(prev) = trailing.take() {
            if let Some((&first, rest)) = slice.split_first() {
                sum += u16::from_be_bytes([prev, first]) as u32;
                slice = rest;
            } else {
                trailing = Some(prev);
                continue;
            }
        }
        let mut iter = slice.chunks_exact(2);
        for pair in &mut iter {
            sum += u16::from_be_bytes([pair[0], pair[1]]) as u32;
        }
        if let Some(&rem) = iter.remainder().first() {
            trailing = Some(rem);
        }
    }
    if let Some(last) = trailing {
        sum += u16::from_be_bytes([last, 0]) as u32;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}
