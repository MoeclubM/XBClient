use std::collections::VecDeque;
use std::fmt;
use std::io::{self, Read};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4};

pub use bytes::BufMut;

#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct UserKey {
    pub username: String,
    pub password: String,
}

impl UserKey {
    pub fn new(username: impl Into<String>, password: impl Into<String>) -> Self {
        Self {
            username: username.into(),
            password: password.into(),
        }
    }
}

impl fmt::Display for UserKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}:{}", percent_encode(&self.username), percent_encode(&self.password))
    }
}

fn percent_encode(value: &str) -> String {
    let mut encoded = String::new();
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'.' | b'_' | b'~') {
            encoded.push(byte as char);
        } else {
            encoded.push_str(&format!("%{byte:02X}"));
        }
    }
    encoded
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Version {
    V4 = 4,
    V5 = 5,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct AuthMethod(pub u8);

impl AuthMethod {
    #[allow(non_upper_case_globals)]
    pub const NoAuth: Self = Self(0x00);
    #[allow(non_upper_case_globals)]
    pub const UserPass: Self = Self(0x02);
    #[allow(non_upper_case_globals)]
    pub const NoAcceptable: Self = Self(0xff);
}

impl From<u8> for AuthMethod {
    fn from(value: u8) -> Self {
        Self(value)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Command {
    Connect = 0x01,
    Bind = 0x02,
    UdpAssociate = 0x03,
}

impl From<Command> for u8 {
    fn from(value: Command) -> Self {
        value as u8
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Reply(pub u8);

impl Reply {
    #[allow(non_upper_case_globals)]
    pub const Succeeded: Self = Self(0x00);
}

impl fmt::Display for Reply {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let text = match self.0 {
            0x00 => "succeeded",
            0x01 => "general SOCKS server failure",
            0x02 => "connection not allowed",
            0x03 => "network unreachable",
            0x04 => "host unreachable",
            0x05 => "connection refused",
            0x06 => "TTL expired",
            0x07 => "command not supported",
            0x08 => "address type not supported",
            code => return write!(f, "reply code 0x{code:02x}"),
        };
        write!(f, "{text}")
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AddressType {
    IPv4,
    Domain,
    IPv6,
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub enum Address {
    SocketAddress(SocketAddr),
    DomainAddress(String, u16),
}

impl Address {
    pub fn unspecified() -> Self {
        Self::SocketAddress(SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0)))
    }

    pub fn get_type(&self) -> AddressType {
        match self {
            Self::SocketAddress(SocketAddr::V4(_)) => AddressType::IPv4,
            Self::SocketAddress(SocketAddr::V6(_)) => AddressType::IPv6,
            Self::DomainAddress(_, _) => AddressType::Domain,
        }
    }
}

impl From<SocketAddr> for Address {
    fn from(value: SocketAddr) -> Self {
        Self::SocketAddress(value)
    }
}

impl From<(String, u16)> for Address {
    fn from((domain, port): (String, u16)) -> Self {
        Self::DomainAddress(domain, port)
    }
}

impl From<(&str, u16)> for Address {
    fn from((domain, port): (&str, u16)) -> Self {
        Self::DomainAddress(domain.to_string(), port)
    }
}

impl TryFrom<&Address> for SocketAddr {
    type Error = io::Error;

    fn try_from(value: &Address) -> Result<Self, Self::Error> {
        match value {
            Address::SocketAddress(addr) => Ok(*addr),
            Address::DomainAddress(domain, _) => Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("SOCKS response returned a domain address: {domain}"),
            )),
        }
    }
}

impl fmt::Display for Address {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::SocketAddress(addr) => write!(f, "{addr}"),
            Self::DomainAddress(domain, port) => write!(f, "{domain}:{port}"),
        }
    }
}

pub trait StreamOperation {
    fn retrieve_from_stream<R>(stream: &mut R) -> io::Result<Self>
    where
        R: Read,
        Self: Sized;

    fn write_to_buf<B: BufMut>(&self, buf: &mut B);

    fn len(&self) -> usize;

    fn write_to_stream<T>(&self, stream: &mut T) -> io::Result<()>
    where
        T: Extend<u8>,
    {
        let mut bytes = Vec::with_capacity(self.len());
        self.write_to_buf(&mut bytes);
        stream.extend(bytes);
        Ok(())
    }
}

#[cfg(feature = "tokio")]
#[async_trait::async_trait]
pub trait AsyncStreamOperation {
    async fn retrieve_from_async_stream<R>(stream: &mut R) -> io::Result<Self>
    where
        R: tokio::io::AsyncRead + Unpin + Send + ?Sized,
        Self: Sized;
}

impl StreamOperation for Address {
    fn retrieve_from_stream<R>(stream: &mut R) -> io::Result<Self>
    where
        R: Read,
    {
        let mut atyp = [0_u8; 1];
        stream.read_exact(&mut atyp)?;
        read_address(stream, atyp[0])
    }

    fn write_to_buf<B: BufMut>(&self, buf: &mut B) {
        match self {
            Self::SocketAddress(SocketAddr::V4(addr)) => {
                buf.put_u8(0x01);
                buf.put_slice(&addr.ip().octets());
                buf.put_u16(addr.port());
            }
            Self::DomainAddress(domain, port) => {
                buf.put_u8(0x03);
                buf.put_u8(domain.len() as u8);
                buf.put_slice(domain.as_bytes());
                buf.put_u16(*port);
            }
            Self::SocketAddress(SocketAddr::V6(addr)) => {
                buf.put_u8(0x04);
                buf.put_slice(&addr.ip().octets());
                buf.put_u16(addr.port());
            }
        }
    }

    fn len(&self) -> usize {
        match self {
            Self::SocketAddress(SocketAddr::V4(_)) => 1 + 4 + 2,
            Self::DomainAddress(domain, _) => 1 + 1 + domain.len() + 2,
            Self::SocketAddress(SocketAddr::V6(_)) => 1 + 16 + 2,
        }
    }
}

fn read_address<R: Read>(stream: &mut R, atyp: u8) -> io::Result<Address> {
    match atyp {
        0x01 => {
            let mut ip = [0_u8; 4];
            let mut port = [0_u8; 2];
            stream.read_exact(&mut ip)?;
            stream.read_exact(&mut port)?;
            Ok(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(ip)), u16::from_be_bytes(port)).into())
        }
        0x03 => {
            let mut len = [0_u8; 1];
            stream.read_exact(&mut len)?;
            let mut domain = vec![0_u8; len[0] as usize];
            let mut port = [0_u8; 2];
            stream.read_exact(&mut domain)?;
            stream.read_exact(&mut port)?;
            let domain = String::from_utf8(domain)
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
            Ok(Address::DomainAddress(domain, u16::from_be_bytes(port)))
        }
        0x04 => {
            let mut ip = [0_u8; 16];
            let mut port = [0_u8; 2];
            stream.read_exact(&mut ip)?;
            stream.read_exact(&mut port)?;
            Ok(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(ip)), u16::from_be_bytes(port)).into())
        }
        other => Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("unsupported SOCKS address type: 0x{other:02x}"),
        )),
    }
}

#[cfg(feature = "tokio")]
#[async_trait::async_trait]
impl AsyncStreamOperation for Address {
    async fn retrieve_from_async_stream<R>(stream: &mut R) -> io::Result<Self>
    where
        R: tokio::io::AsyncRead + Unpin + Send + ?Sized,
    {
        use tokio::io::AsyncReadExt;

        let atyp = stream.read_u8().await?;
        match atyp {
            0x01 => {
                let mut ip = [0_u8; 4];
                stream.read_exact(&mut ip).await?;
                let port = stream.read_u16().await?;
                Ok(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(ip)), port).into())
            }
            0x03 => {
                let len = stream.read_u8().await?;
                let mut domain = vec![0_u8; len as usize];
                stream.read_exact(&mut domain).await?;
                let port = stream.read_u16().await?;
                let domain = String::from_utf8(domain)
                    .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
                Ok(Address::DomainAddress(domain, port))
            }
            0x04 => {
                let mut ip = [0_u8; 16];
                stream.read_exact(&mut ip).await?;
                let port = stream.read_u16().await?;
                Ok(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(ip)), port).into())
            }
            other => Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("unsupported SOCKS address type: 0x{other:02x}"),
            )),
        }
    }
}

pub mod handshake {
    use super::*;

    pub struct Request {
        methods: Vec<AuthMethod>,
    }

    impl Request {
        pub fn new(methods: Vec<AuthMethod>) -> Self {
            Self { methods }
        }
    }

    impl StreamOperation for Request {
        fn retrieve_from_stream<R>(_stream: &mut R) -> io::Result<Self>
        where
            R: Read,
        {
            Err(io::Error::new(io::ErrorKind::Unsupported, "SOCKS handshake request parsing is not used"))
        }

        fn write_to_buf<B: BufMut>(&self, buf: &mut B) {
            buf.put_u8(0x05);
            buf.put_u8(self.methods.len() as u8);
            for method in &self.methods {
                buf.put_u8(method.0);
            }
        }

        fn len(&self) -> usize {
            2 + self.methods.len()
        }
    }

    pub struct Response {
        pub method: AuthMethod,
    }

    impl StreamOperation for Response {
        fn retrieve_from_stream<R>(stream: &mut R) -> io::Result<Self>
        where
            R: Read,
        {
            let mut buf = [0_u8; 2];
            stream.read_exact(&mut buf)?;
            if buf[0] != 0x05 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid SOCKS version in handshake response"));
            }
            Ok(Self {
                method: AuthMethod::from(buf[1]),
            })
        }

        fn write_to_buf<B: BufMut>(&self, buf: &mut B) {
            buf.put_u8(0x05);
            buf.put_u8(self.method.0);
        }

        fn len(&self) -> usize {
            2
        }
    }
}

pub mod password_method {
    use super::*;

    pub struct Request {
        username: String,
        password: String,
    }

    impl Request {
        pub fn new(username: &str, password: &str) -> Self {
            Self {
                username: username.to_string(),
                password: password.to_string(),
            }
        }
    }

    impl StreamOperation for Request {
        fn retrieve_from_stream<R>(_stream: &mut R) -> io::Result<Self>
        where
            R: Read,
        {
            Err(io::Error::new(io::ErrorKind::Unsupported, "SOCKS password request parsing is not used"))
        }

        fn write_to_buf<B: BufMut>(&self, buf: &mut B) {
            buf.put_u8(0x01);
            buf.put_u8(self.username.len() as u8);
            buf.put_slice(self.username.as_bytes());
            buf.put_u8(self.password.len() as u8);
            buf.put_slice(self.password.as_bytes());
        }

        fn len(&self) -> usize {
            3 + self.username.len() + self.password.len()
        }
    }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub struct Status(pub u8);

    impl Status {
        #[allow(non_upper_case_globals)]
        pub const Succeeded: Self = Self(0x00);
    }

    pub struct Response {
        pub status: Status,
    }

    impl StreamOperation for Response {
        fn retrieve_from_stream<R>(stream: &mut R) -> io::Result<Self>
        where
            R: Read,
        {
            let mut buf = [0_u8; 2];
            stream.read_exact(&mut buf)?;
            if buf[0] != 0x01 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid SOCKS password auth version"));
            }
            Ok(Self {
                status: Status(buf[1]),
            })
        }

        fn write_to_buf<B: BufMut>(&self, buf: &mut B) {
            buf.put_u8(0x01);
            buf.put_u8(self.status.0);
        }

        fn len(&self) -> usize {
            2
        }
    }
}

pub struct Request {
    command: Command,
    address: Address,
}

impl Request {
    pub fn new(command: Command, address: Address) -> Self {
        Self { command, address }
    }
}

impl StreamOperation for Request {
    fn retrieve_from_stream<R>(_stream: &mut R) -> io::Result<Self>
    where
        R: Read,
    {
        Err(io::Error::new(io::ErrorKind::Unsupported, "SOCKS request parsing is not used"))
    }

    fn write_to_buf<B: BufMut>(&self, buf: &mut B) {
        buf.put_u8(0x05);
        buf.put_u8(self.command.into());
        buf.put_u8(0x00);
        self.address.write_to_buf(buf);
    }

    fn len(&self) -> usize {
        3 + self.address.len()
    }
}

pub struct Response {
    pub reply: Reply,
    pub address: Address,
}

impl StreamOperation for Response {
    fn retrieve_from_stream<R>(stream: &mut R) -> io::Result<Self>
    where
        R: Read,
    {
        let mut header = [0_u8; 3];
        stream.read_exact(&mut header)?;
        if header[0] != 0x05 || header[2] != 0x00 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid SOCKS response header"));
        }
        let address = Address::retrieve_from_stream(stream)?;
        Ok(Self {
            reply: Reply(header[1]),
            address,
        })
    }

    fn write_to_buf<B: BufMut>(&self, buf: &mut B) {
        buf.put_u8(0x05);
        buf.put_u8(self.reply.0);
        buf.put_u8(0x00);
        self.address.write_to_buf(buf);
    }

    fn len(&self) -> usize {
        3 + self.address.len()
    }
}

pub struct UdpHeader {
    pub frag: u8,
    pub address: Address,
}

impl UdpHeader {
    pub fn new(frag: u8, address: Address) -> Self {
        Self { frag, address }
    }
}

impl StreamOperation for UdpHeader {
    fn retrieve_from_stream<R>(stream: &mut R) -> io::Result<Self>
    where
        R: Read,
    {
        let mut header = [0_u8; 3];
        stream.read_exact(&mut header)?;
        if header[0] != 0 || header[1] != 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid SOCKS UDP reserved field"));
        }
        let address = Address::retrieve_from_stream(stream)?;
        Ok(Self {
            frag: header[2],
            address,
        })
    }

    fn write_to_buf<B: BufMut>(&self, buf: &mut B) {
        buf.put_u16(0);
        buf.put_u8(self.frag);
        self.address.write_to_buf(buf);
    }

    fn len(&self) -> usize {
        3 + self.address.len()
    }
}

impl StreamOperation for VecDeque<u8> {
    fn retrieve_from_stream<R>(_stream: &mut R) -> io::Result<Self>
    where
        R: Read,
    {
        Err(io::Error::new(io::ErrorKind::Unsupported, "VecDeque parsing is not used"))
    }

    fn write_to_buf<B: BufMut>(&self, buf: &mut B) {
        for byte in self {
            buf.put_u8(*byte);
        }
    }

    fn len(&self) -> usize {
        VecDeque::len(self)
    }
}
