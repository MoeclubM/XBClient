use super::config::Hysteria2Config;
use super::protocol::{Hysteria2Client, SocksTarget, encode_varint, read_varint_from_slice};
use super::socks::write_socks_reply;
use anyhow::{Context, Result, bail, ensure};
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicU16, AtomicU32, Ordering};
use tokio::io::AsyncReadExt;
use tokio::net::{TcpStream, UdpSocket};

static NEXT_UDP_SESSION_ID: AtomicU32 = AtomicU32::new(1);
static NEXT_UDP_PACKET_ID: AtomicU16 = AtomicU16::new(1);

#[derive(Clone)]
struct UdpMessage {
    session_id: u32,
    packet_id: u16,
    fragment_id: u8,
    fragment_count: u8,
    address: String,
    payload: Vec<u8>,
}

struct UdpFragments {
    address: String,
    fragments: Vec<Option<Vec<u8>>>,
}

pub(super) async fn relay_udp_associate(
    mut stream: TcpStream,
    config: Hysteria2Config,
) -> Result<()> {
    let udp = UdpSocket::bind("127.0.0.1:0")
        .await
        .context("bind Hysteria2 SOCKS UDP relay")?;
    let bind_addr = udp.local_addr().context("read SOCKS UDP relay address")?;
    let client = match Hysteria2Client::connect(config).await {
        Ok(client) if client.udp_enabled => client,
        Ok(_) => {
            let _ = write_socks_reply(&mut stream, 0x07, bind_addr).await;
            bail!("Hysteria2 server does not enable UDP relay");
        }
        Err(error) => {
            let _ = write_socks_reply(&mut stream, 0x05, bind_addr).await;
            return Err(error);
        }
    };
    write_socks_reply(&mut stream, 0x00, bind_addr).await?;

    let session_id = NEXT_UDP_SESSION_ID.fetch_add(1, Ordering::Relaxed);
    let mut client_addr = None::<SocketAddr>;
    let mut fragments = HashMap::<(u32, u16), UdpFragments>::new();
    let mut udp_buffer = vec![0u8; 64 * 1024];
    let mut control_buffer = [0u8; 1];
    loop {
        tokio::select! {
            read = udp.recv_from(&mut udp_buffer) => {
                let (read, source) = read.context("read Hysteria2 SOCKS UDP packet")?;
                client_addr = Some(source);
                let (target, payload) = parse_socks_udp_packet(&udp_buffer[..read])?;
                let packet_id = NEXT_UDP_PACKET_ID.fetch_add(1, Ordering::Relaxed);
                for packet in encode_udp_messages(
                    session_id,
                    packet_id,
                    target.host_port(),
                    payload,
                    client.max_datagram_size(),
                )? {
                    client.send_datagram(packet)?;
                }
            }
            packet = client.read_datagram() => {
                let message = decode_udp_message(&packet?)?;
                if message.session_id != session_id {
                    continue;
                }
                let Some(message) = reassemble_udp_message(message, &mut fragments)? else {
                    continue;
                };
                let packet = encode_socks_udp_packet(&parse_host_port(&message.address)?, &message.payload)?;
                if let Some(target) = client_addr {
                    udp.send_to(&packet, target)
                        .await
                        .context("write Hysteria2 SOCKS UDP packet")?;
                }
            }
            read = stream.read(&mut control_buffer) => {
                if read.context("read Hysteria2 SOCKS UDP control")? == 0 {
                    return Ok(());
                }
            }
        }
    }
}

fn parse_socks_udp_packet(packet: &[u8]) -> Result<(SocksTarget, &[u8])> {
    ensure!(packet.len() >= 4, "SOCKS UDP packet too short");
    ensure!(
        packet[0] == 0 && packet[1] == 0,
        "invalid SOCKS UDP reserved field"
    );
    ensure!(packet[2] == 0, "SOCKS UDP fragmentation is not supported");
    read_socks_target_from_bytes(packet, 3)
}

fn read_socks_target_from_bytes(packet: &[u8], offset: usize) -> Result<(SocksTarget, &[u8])> {
    ensure!(packet.len() > offset, "SOCKS UDP address is missing");
    match packet[offset] {
        0x01 => {
            ensure!(
                packet.len() >= offset + 1 + 4 + 2,
                "SOCKS UDP IPv4 address is incomplete"
            );
            let ip = Ipv4Addr::new(
                packet[offset + 1],
                packet[offset + 2],
                packet[offset + 3],
                packet[offset + 4],
            );
            let port = u16::from_be_bytes([packet[offset + 5], packet[offset + 6]]);
            Ok((
                SocksTarget::Ip(SocketAddr::new(IpAddr::V4(ip), port)),
                &packet[offset + 7..],
            ))
        }
        0x03 => {
            ensure!(
                packet.len() > offset + 1,
                "SOCKS UDP domain length is missing"
            );
            let length = packet[offset + 1] as usize;
            ensure!(
                packet.len() >= offset + 2 + length + 2,
                "SOCKS UDP domain address is incomplete"
            );
            let host = String::from_utf8(packet[offset + 2..offset + 2 + length].to_vec())
                .context("decode SOCKS UDP domain")?;
            let port_offset = offset + 2 + length;
            let port = u16::from_be_bytes([packet[port_offset], packet[port_offset + 1]]);
            Ok((SocksTarget::Domain(host, port), &packet[port_offset + 2..]))
        }
        0x04 => {
            ensure!(
                packet.len() >= offset + 1 + 16 + 2,
                "SOCKS UDP IPv6 address is incomplete"
            );
            let mut ip = [0u8; 16];
            ip.copy_from_slice(&packet[offset + 1..offset + 17]);
            let port = u16::from_be_bytes([packet[offset + 17], packet[offset + 18]]);
            Ok((
                SocksTarget::Ip(SocketAddr::new(IpAddr::V6(ip.into()), port)),
                &packet[offset + 19..],
            ))
        }
        other => bail!("unsupported SOCKS UDP address type: {other}"),
    }
}

fn encode_socks_udp_packet(source: &SocksTarget, payload: &[u8]) -> Result<Vec<u8>> {
    let mut packet = vec![0, 0, 0];
    write_socks_target(&mut packet, source)?;
    packet.extend_from_slice(payload);
    Ok(packet)
}

fn write_socks_target(packet: &mut Vec<u8>, target: &SocksTarget) -> Result<()> {
    match target {
        SocksTarget::Ip(addr) => match addr.ip() {
            IpAddr::V4(ip) => {
                packet.push(0x01);
                packet.extend_from_slice(&ip.octets());
                packet.extend_from_slice(&addr.port().to_be_bytes());
            }
            IpAddr::V6(ip) => {
                packet.push(0x04);
                packet.extend_from_slice(&ip.octets());
                packet.extend_from_slice(&addr.port().to_be_bytes());
            }
        },
        SocksTarget::Domain(host, port) => {
            ensure!(host.len() <= u8::MAX as usize, "SOCKS domain too long");
            packet.push(0x03);
            packet.push(host.len() as u8);
            packet.extend_from_slice(host.as_bytes());
            packet.extend_from_slice(&port.to_be_bytes());
        }
    }
    Ok(())
}

fn encode_udp_message(message: &UdpMessage) -> Result<Vec<u8>> {
    let address = message.address.as_bytes();
    let mut encoded = Vec::with_capacity(8 + address.len() + message.payload.len() + 8);
    encoded.extend_from_slice(&message.session_id.to_be_bytes());
    encoded.extend_from_slice(&message.packet_id.to_be_bytes());
    encoded.push(message.fragment_id);
    encoded.push(message.fragment_count);
    encode_varint(address.len() as u64, &mut encoded)?;
    encoded.extend_from_slice(address);
    encoded.extend_from_slice(&message.payload);
    Ok(encoded)
}

fn encode_udp_messages(
    session_id: u32,
    packet_id: u16,
    address: String,
    payload: &[u8],
    max_datagram_size: Option<usize>,
) -> Result<Vec<Vec<u8>>> {
    let max_datagram_size = max_datagram_size.unwrap_or(usize::MAX);
    let header_len = encoded_udp_header_len(&address)?;
    if header_len + payload.len() <= max_datagram_size {
        return Ok(vec![encode_udp_message(&UdpMessage {
            session_id,
            packet_id,
            fragment_id: 0,
            fragment_count: 1,
            address,
            payload: payload.to_vec(),
        })?]);
    }
    ensure!(
        header_len < max_datagram_size,
        "Hysteria2 UDP datagram header exceeds peer limit"
    );
    let fragment_payload_len = max_datagram_size - header_len;
    let fragment_count = payload.len().div_ceil(fragment_payload_len);
    ensure!(
        fragment_count <= u8::MAX as usize,
        "Hysteria2 UDP packet requires too many fragments"
    );
    let mut packets = Vec::with_capacity(fragment_count);
    for (index, chunk) in payload.chunks(fragment_payload_len).enumerate() {
        packets.push(encode_udp_message(&UdpMessage {
            session_id,
            packet_id,
            fragment_id: index as u8,
            fragment_count: fragment_count as u8,
            address: address.clone(),
            payload: chunk.to_vec(),
        })?);
    }
    Ok(packets)
}

fn encoded_udp_header_len(address: &str) -> Result<usize> {
    let mut varint = Vec::new();
    encode_varint(address.len() as u64, &mut varint)?;
    Ok(8 + varint.len() + address.len())
}

fn decode_udp_message(mut bytes: &[u8]) -> Result<UdpMessage> {
    ensure!(bytes.len() >= 8, "Hysteria2 UDP datagram is too short");
    let session_id = u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
    let packet_id = u16::from_be_bytes([bytes[4], bytes[5]]);
    let fragment_id = bytes[6];
    let fragment_count = bytes[7];
    bytes = &bytes[8..];
    let address_len = read_varint_from_slice(&mut bytes)?;
    ensure!(address_len > 0, "Hysteria2 UDP address is required");
    ensure!(address_len <= 2048, "Hysteria2 UDP address is too long");
    ensure!(
        bytes.len() >= address_len as usize,
        "Hysteria2 UDP address is truncated"
    );
    let (address, payload) = bytes.split_at(address_len as usize);
    Ok(UdpMessage {
        session_id,
        packet_id,
        fragment_id,
        fragment_count,
        address: std::str::from_utf8(address)
            .context("decode Hysteria2 UDP address")?
            .to_string(),
        payload: payload.to_vec(),
    })
}

fn reassemble_udp_message(
    message: UdpMessage,
    fragments: &mut HashMap<(u32, u16), UdpFragments>,
) -> Result<Option<UdpMessage>> {
    ensure!(
        message.fragment_count > 0,
        "Hysteria2 UDP fragment_count must be positive"
    );
    if message.fragment_count <= 1 {
        return Ok(Some(message));
    }
    ensure!(
        message.fragment_id < message.fragment_count,
        "invalid Hysteria2 UDP fragment id"
    );
    let key = (message.session_id, message.packet_id);
    let entry = fragments.entry(key).or_insert_with(|| UdpFragments {
        address: message.address.clone(),
        fragments: vec![None; message.fragment_count as usize],
    });
    ensure!(
        entry.address == message.address,
        "Hysteria2 UDP fragment address changed"
    );
    ensure!(
        entry.fragments.len() == message.fragment_count as usize,
        "Hysteria2 UDP fragment count changed"
    );
    entry.fragments[message.fragment_id as usize] = Some(message.payload);
    if entry.fragments.iter().any(Option::is_none) {
        return Ok(None);
    }
    let entry = fragments
        .remove(&key)
        .context("remove complete Hysteria2 UDP fragments")?;
    let payload = entry
        .fragments
        .into_iter()
        .flat_map(|fragment| fragment.unwrap_or_default())
        .collect();
    Ok(Some(UdpMessage {
        session_id: key.0,
        packet_id: key.1,
        fragment_id: 0,
        fragment_count: 1,
        address: entry.address,
        payload,
    }))
}

fn parse_host_port(value: &str) -> Result<SocksTarget> {
    if let Ok(addr) = value.parse::<SocketAddr>() {
        return Ok(SocksTarget::Ip(addr));
    }
    let (host, port) = value
        .rsplit_once(':')
        .with_context(|| format!("parse Hysteria2 UDP source address: {value}"))?;
    Ok(SocksTarget::Domain(
        host.trim_matches(['[', ']']).to_string(),
        port.parse::<u16>()
            .with_context(|| format!("parse Hysteria2 UDP source port: {value}"))?,
    ))
}
