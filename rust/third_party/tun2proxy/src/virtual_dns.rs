use crate::error::Result;
use hashlink::{LruCache, linked_hash_map::RawEntryMut};
use hickory_proto::{op::MessageType, rr::RecordType};
use std::{
    collections::HashMap,
    convert::TryInto,
    net::{IpAddr, Ipv4Addr, Ipv6Addr},
    time::{Duration, Instant},
};
use tproxy_config::IpCidr;

const MAPPING_TIMEOUT: u64 = 60; // Mapping timeout in seconds

struct NameCacheEntry {
    name: String,
    expiry: Instant,
}

/// A virtual DNS server which allocates IP addresses to clients.
/// The IP addresses are in the range of private IP addresses.
/// The DNS server is implemented as a LRU cache.
pub struct VirtualDns {
    trailing_dot: bool,
    ipv4: VirtualDnsPool,
    ipv6: VirtualDnsPool,
}

impl VirtualDns {
    pub fn new(ipv4_pool: IpCidr, ipv6_pool: IpCidr) -> Self {
        Self {
            trailing_dot: false,
            ipv4: VirtualDnsPool::new(ipv4_pool),
            ipv6: VirtualDnsPool::new(ipv6_pool),
        }
    }

    /// Returns the DNS response to send back to the client.
    pub fn generate_query(&mut self, data: &[u8]) -> Result<(Vec<u8>, String, IpAddr)> {
        use crate::dns;
        let mut message = dns::parse_data_to_dns_message(data, false)?;
        let query = message.queries.first().ok_or("DnsRequest no query body")?;
        let qname = query.name().to_string();
        let query_type = query.query_type();
        if query_type != RecordType::A && query_type != RecordType::AAAA {
            message.metadata.message_type = MessageType::Response;
            return Ok((message.to_vec()?, qname, IpAddr::V4(Ipv4Addr::UNSPECIFIED)));
        }
        let insert_name = self.insert_name(qname.clone());
        let ip = if query_type == RecordType::AAAA {
            self.ipv6.find_or_allocate_ip(insert_name)?
        } else {
            self.ipv4.find_or_allocate_ip(insert_name)?
        };
        let message = dns::build_dns_response(message, &qname, ip, 5)?;
        Ok((message.to_vec()?, qname, ip))
    }

    fn insert_name(&self, name: String) -> String {
        if name.ends_with('.') && !self.trailing_dot {
            String::from(name.trim_end_matches('.'))
        } else {
            name
        }
    }

    // This is to be called whenever we receive or send a packet on the socket
    // which connects the tun interface to the client, so existing IP address to name
    // mappings to not expire as long as the connection is active.
    pub fn touch_ip(&mut self, addr: &IpAddr) {
        if addr.is_ipv6() {
            self.ipv6.touch_ip(addr);
        } else {
            self.ipv4.touch_ip(addr);
        }
    }

    pub fn resolve_ip(&mut self, addr: &IpAddr) -> Option<&String> {
        if addr.is_ipv6() {
            self.ipv6.resolve_ip(addr)
        } else {
            self.ipv4.resolve_ip(addr)
        }
    }
}

struct VirtualDnsPool {
    lru_cache: LruCache<IpAddr, NameCacheEntry>,
    name_to_ip: HashMap<String, IpAddr>,
    network_addr: IpAddr,
    broadcast_addr: IpAddr,
    next_addr: IpAddr,
}

impl VirtualDnsPool {
    fn new(ip_pool: IpCidr) -> Self {
        Self {
            next_addr: ip_pool.first_address(),
            name_to_ip: HashMap::default(),
            network_addr: ip_pool.first_address(),
            broadcast_addr: ip_pool.last_address(),
            lru_cache: LruCache::new_unbounded(),
        }
    }

    fn increment_ip(addr: IpAddr) -> Result<IpAddr> {
        let mut ip_bytes = match addr as IpAddr {
            IpAddr::V4(ip) => Vec::<u8>::from(ip.octets()),
            IpAddr::V6(ip) => Vec::<u8>::from(ip.octets()),
        };

        // Traverse bytes from right to left and stop when we can add one.
        for j in 0..ip_bytes.len() {
            let i = ip_bytes.len() - 1 - j;
            if ip_bytes[i] != 255 {
                // We can add 1 without carry and are done.
                ip_bytes[i] += 1;
                break;
            } else {
                // Zero this byte and carry over to the next one.
                ip_bytes[i] = 0;
            }
        }
        let addr = if addr.is_ipv4() {
            let bytes: [u8; 4] = ip_bytes.as_slice().try_into()?;
            IpAddr::V4(Ipv4Addr::from(bytes))
        } else {
            let bytes: [u8; 16] = ip_bytes.as_slice().try_into()?;
            IpAddr::V6(Ipv6Addr::from(bytes))
        };
        Ok(addr)
    }

    // This is to be called whenever we receive or send a packet on the socket
    // which connects the tun interface to the client, so existing IP address to name
    // mappings to not expire as long as the connection is active.
    fn touch_ip(&mut self, addr: &IpAddr) {
        _ = self.lru_cache.get_mut(addr).map(|entry| {
            entry.expiry = Instant::now() + Duration::from_secs(MAPPING_TIMEOUT);
        });
    }

    fn resolve_ip(&mut self, addr: &IpAddr) -> Option<&String> {
        self.lru_cache.get(addr).map(|entry| &entry.name)
    }

    fn find_or_allocate_ip(&mut self, insert_name: String) -> Result<IpAddr> {
        let now = Instant::now();

        // Iterate through all entries of the LRU cache and remove those that have expired.
        loop {
            let (ip, entry) = match self.lru_cache.iter().next() {
                None => break,
                Some((ip, entry)) => (ip, entry),
            };

            // The entry has expired.
            if now > entry.expiry {
                let name = entry.name.clone();
                self.lru_cache.remove(&ip.clone());
                self.name_to_ip.remove(&name);
                continue; // There might be another expired entry after this one.
            }

            break; // The entry has not expired and all following entries are newer.
        }

        // Return the IP if it is stored inside our LRU cache.
        if let Some(ip) = self.name_to_ip.get(&insert_name) {
            let ip = *ip;
            self.touch_ip(&ip);
            return Ok(ip);
        }

        // Otherwise, store name and IP pair inside the LRU cache.
        let started_at = self.next_addr;

        loop {
            if let RawEntryMut::Vacant(vacant) =
                self.lru_cache.raw_entry_mut().from_key(&self.next_addr)
            {
                let expiry = Instant::now() + Duration::from_secs(MAPPING_TIMEOUT);
                let name0 = insert_name.clone();
                vacant.insert(
                    self.next_addr,
                    NameCacheEntry {
                        name: insert_name,
                        expiry,
                    },
                );
                self.name_to_ip.insert(name0, self.next_addr);
                return Ok(self.next_addr);
            }
            self.next_addr = Self::increment_ip(self.next_addr)?;
            if self.next_addr == self.broadcast_addr {
                // Wrap around.
                self.next_addr = self.network_addr;
            }
            if self.next_addr == started_at {
                return Err("Virtual IP space for DNS exhausted".into());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::VirtualDns;
    use hickory_proto::{
        op::{Message, MessageType, OpCode, Query},
        rr::{Name, RData, RecordType},
    };
    use std::{net::IpAddr, str::FromStr};
    use tproxy_config::IpCidr;

    fn query(name: &str, record_type: RecordType) -> Vec<u8> {
        let mut message = Message::new(0, MessageType::Query, OpCode::Query);
        message.add_query(Query::query(Name::from_str(name).unwrap(), record_type));
        message.to_vec().unwrap()
    }

    fn virtual_dns() -> VirtualDns {
        VirtualDns::new(
            IpCidr::from_str("198.18.0.0/15").unwrap(),
            IpCidr::from_str("fdfe:dcba:9877::/64").unwrap(),
        )
    }

    #[test]
    fn a_query_allocates_ipv4_fake_ip() {
        let mut dns = virtual_dns();

        let (response, qname, ip) = dns
            .generate_query(&query("example.com.", RecordType::A))
            .unwrap();
        let response = Message::from_vec(&response).unwrap();

        assert_eq!(qname, "example.com.");
        assert!(ip.is_ipv4());
        assert!(matches!(response.answers[0].data, RData::A(_)));
        assert_eq!(dns.resolve_ip(&ip), Some(&"example.com".to_string()));
    }

    #[test]
    fn aaaa_query_allocates_ipv6_fake_ip() {
        let mut dns = virtual_dns();

        let (response, qname, ip) = dns
            .generate_query(&query("example.com.", RecordType::AAAA))
            .unwrap();
        let response = Message::from_vec(&response).unwrap();

        assert_eq!(qname, "example.com.");
        assert!(ip.is_ipv6());
        assert!(matches!(response.answers[0].data, RData::AAAA(_)));
        assert_eq!(dns.resolve_ip(&ip), Some(&"example.com".to_string()));
    }

    #[test]
    fn same_name_keeps_separate_ipv4_and_ipv6_mappings() {
        let mut dns = virtual_dns();

        let (_, _, ipv4) = dns
            .generate_query(&query("example.com.", RecordType::A))
            .unwrap();
        let (_, _, ipv6) = dns
            .generate_query(&query("example.com.", RecordType::AAAA))
            .unwrap();

        assert!(ipv4.is_ipv4());
        assert!(ipv6.is_ipv6());
        assert_eq!(dns.resolve_ip(&ipv4), Some(&"example.com".to_string()));
        assert_eq!(dns.resolve_ip(&ipv6), Some(&"example.com".to_string()));
    }

    #[test]
    fn non_address_query_returns_empty_response() {
        let mut dns = virtual_dns();

        let (response, qname, ip) = dns
            .generate_query(&query("example.com.", RecordType::MX))
            .unwrap();
        let response = Message::from_vec(&response).unwrap();

        assert_eq!(qname, "example.com.");
        assert_eq!(ip, IpAddr::from([0, 0, 0, 0]));
        assert!(response.answers.is_empty());
    }
}
