use super::config::AnyTlsConfig;
use super::protocol::{
    AnyTlsStream, CMD_FIN, CMD_PSH, MAX_FRAME_PAYLOAD_LEN, SocksTarget, write_frame,
};
use super::socks::write_socks_reply;
use anyhow::{Context, Result, bail, ensure};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex as StdMutex};
use tokio::io::AsyncReadExt;
use tokio::net::{TcpStream, UdpSocket};

pub(super) async fn relay_udp_associate(mut stream: TcpStream, config: AnyTlsConfig) -> Result<()> {
    let udp = Arc::new(
        UdpSocket::bind("127.0.0.1:0")
            .await
            .context("bind SOCKS UDP relay")?,
    );
    let bind_addr = udp.local_addr().context("read SOCKS UDP relay address")?;
    let initial = encode_uot_associate_request()?;
    let remote = match AnyTlsStream::connect_with_initial_without_synack(
        config,
        SocksTarget::Domain("sp.v2.udp-over-tcp.arpa".to_string(), 0),
        &initial,
    )
    .await
    {
        Ok(remote) => remote,
        Err(error) => {
            let _ = write_socks_reply(&mut stream, 0x05, bind_addr).await;
            return Err(error);
        }
    };
    write_socks_reply(&mut stream, 0x00, bind_addr).await?;

    let client_addr = Arc::new(StdMutex::new(None::<SocketAddr>));
    let writer = remote.writer.clone();
    let writer_for_uplink = writer.clone();
    let stream_id = remote.stream_id;
    let udp_for_uplink = udp.clone();
    let client_addr_for_uplink = client_addr.clone();
    let uplink = async move {
        let mut buffer = vec![0u8; 64 * 1024];
        loop {
            let (read, source) = udp_for_uplink
                .recv_from(&mut buffer)
                .await
                .context("read SOCKS UDP packet")?;
            *client_addr_for_uplink
                .lock()
                .expect("SOCKS UDP client address lock poisoned") = Some(source);
            let (target, payload) = parse_socks_udp_packet(&buffer[..read])?;
            let packet = encode_uot_packet(&target, payload)?;
            for chunk in packet.chunks(MAX_FRAME_PAYLOAD_LEN) {
                write_frame(&writer_for_uplink, CMD_PSH, stream_id, chunk).await?;
            }
        }
        #[allow(unreachable_code)]
        Ok::<(), anyhow::Error>(())
    };

    let udp_for_downlink = udp.clone();
    let client_addr_for_downlink = client_addr.clone();
    let downlink = async move {
        let mut remote = remote;
        while let Some(payload) = remote.read_payload().await? {
            let (source, body) = parse_uot_packet(&payload)?;
            let packet = encode_socks_udp_packet(&source, body)?;
            let target = *client_addr_for_downlink
                .lock()
                .expect("SOCKS UDP client address lock poisoned");
            if let Some(target) = target {
                udp_for_downlink
                    .send_to(&packet, target)
                    .await
                    .context("write SOCKS UDP packet")?;
            }
        }
        Ok::<(), anyhow::Error>(())
    };

    let control = async move {
        let mut buffer = [0u8; 1];
        loop {
            if stream
                .read(&mut buffer)
                .await
                .context("read SOCKS UDP control")?
                == 0
            {
                return Ok::<(), anyhow::Error>(());
            }
        }
    };

    tokio::select! {
        result = uplink => result?,
        result = downlink => result?,
        result = control => result?,
    };
    let _ = write_frame(&writer, CMD_FIN, stream_id, &[]).await;
    Ok(())
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
            ensure!(host.len() <= u8::MAX as usize, "domain too long");
            packet.push(0x03);
            packet.push(host.len() as u8);
            packet.extend_from_slice(host.as_bytes());
            packet.extend_from_slice(&port.to_be_bytes());
        }
    }
    Ok(())
}

fn encode_uot_associate_request() -> Result<Vec<u8>> {
    let mut packet = vec![0];
    write_socks_target(
        &mut packet,
        &SocksTarget::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)),
    )?;
    Ok(packet)
}

fn encode_uot_packet(target: &SocksTarget, payload: &[u8]) -> Result<Vec<u8>> {
    ensure!(payload.len() <= u16::MAX as usize, "UDP payload too large");
    let mut packet = Vec::with_capacity(32 + payload.len());
    write_uot_target(&mut packet, target)?;
    packet.extend_from_slice(&(payload.len() as u16).to_be_bytes());
    packet.extend_from_slice(payload);
    Ok(packet)
}

fn parse_uot_packet(packet: &[u8]) -> Result<(SocksTarget, &[u8])> {
    let (target, tail) = read_uot_target(packet, 0)?;
    ensure!(tail.len() >= 2, "UOT packet length is missing");
    let length = u16::from_be_bytes([tail[0], tail[1]]) as usize;
    ensure!(tail.len() >= 2 + length, "UOT packet payload is incomplete");
    Ok((target, &tail[2..2 + length]))
}

fn write_uot_target(packet: &mut Vec<u8>, target: &SocksTarget) -> Result<()> {
    match target {
        SocksTarget::Ip(addr) => match addr.ip() {
            IpAddr::V4(ip) => {
                packet.push(0x00);
                packet.extend_from_slice(&ip.octets());
                packet.extend_from_slice(&addr.port().to_be_bytes());
            }
            IpAddr::V6(ip) => {
                packet.push(0x01);
                packet.extend_from_slice(&ip.octets());
                packet.extend_from_slice(&addr.port().to_be_bytes());
            }
        },
        SocksTarget::Domain(host, port) => {
            ensure!(host.len() <= u8::MAX as usize, "UOT domain too long");
            packet.push(0x02);
            packet.push(host.len() as u8);
            packet.extend_from_slice(host.as_bytes());
            packet.extend_from_slice(&port.to_be_bytes());
        }
    }
    Ok(())
}

fn read_uot_target(packet: &[u8], offset: usize) -> Result<(SocksTarget, &[u8])> {
    ensure!(packet.len() > offset, "UOT address is missing");
    match packet[offset] {
        0x00 => {
            ensure!(
                packet.len() >= offset + 1 + 4 + 2,
                "UOT IPv4 address is incomplete"
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
        0x01 => {
            ensure!(
                packet.len() >= offset + 1 + 16 + 2,
                "UOT IPv6 address is incomplete"
            );
            let mut ip = [0u8; 16];
            ip.copy_from_slice(&packet[offset + 1..offset + 17]);
            let port = u16::from_be_bytes([packet[offset + 17], packet[offset + 18]]);
            Ok((
                SocksTarget::Ip(SocketAddr::new(IpAddr::V6(ip.into()), port)),
                &packet[offset + 19..],
            ))
        }
        0x02 => {
            ensure!(packet.len() > offset + 1, "UOT domain length is missing");
            let length = packet[offset + 1] as usize;
            ensure!(
                packet.len() >= offset + 2 + length + 2,
                "UOT domain address is incomplete"
            );
            let host = String::from_utf8(packet[offset + 2..offset + 2 + length].to_vec())
                .context("decode UOT domain")?;
            let port_offset = offset + 2 + length;
            let port = u16::from_be_bytes([packet[port_offset], packet[port_offset + 1]]);
            Ok((SocksTarget::Domain(host, port), &packet[port_offset + 2..]))
        }
        other => bail!("unsupported UOT address family: {other}"),
    }
}
