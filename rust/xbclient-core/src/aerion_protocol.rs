use aerion::{
    ClientConfig, Hysteria2ClientConfig, MieruClientConfig, NaiveClientConfig,
    ShadowsocksClientConfig, TrojanClientConfig, VlessClientConfig, VmessClientConfig,
    run_client_listener, run_hysteria2_client_listener, run_mieru_client_listener,
    run_naive_client_listener, run_shadowsocks_client_listener, run_trojan_client_listener,
    run_vless_client_listener, run_vmess_client_listener,
};
use anyhow::Result;
use tokio::net::TcpListener;
use tokio::task::JoinHandle;

pub enum AerionProxyConfig {
    AnyTls(ClientConfig),
    Hysteria2(Hysteria2ClientConfig),
    Trojan(TrojanClientConfig),
    Vless(VlessClientConfig),
    Vmess(VmessClientConfig),
    Mieru(MieruClientConfig),
    Naive(NaiveClientConfig),
    Shadowsocks(ShadowsocksClientConfig),
}

pub fn spawn_aerion_listener(listener: TcpListener, config: AerionProxyConfig) -> JoinHandle<()> {
    tokio::spawn(async move {
        if let Err(error) = run_aerion_listener(listener, config).await {
            log::error!("Aerion SOCKS listener exited with error: {error:?}");
        }
    })
}

async fn run_aerion_listener(listener: TcpListener, config: AerionProxyConfig) -> Result<()> {
    match config {
        AerionProxyConfig::AnyTls(config) => run_client_listener(listener, config).await,
        AerionProxyConfig::Hysteria2(config) => {
            run_hysteria2_client_listener(listener, config).await
        }
        AerionProxyConfig::Trojan(config) => run_trojan_client_listener(listener, config).await,
        AerionProxyConfig::Vless(config) => run_vless_client_listener(listener, config).await,
        AerionProxyConfig::Vmess(config) => run_vmess_client_listener(listener, config).await,
        AerionProxyConfig::Mieru(config) => run_mieru_client_listener(listener, config).await,
        AerionProxyConfig::Naive(config) => run_naive_client_listener(listener, config).await,
        AerionProxyConfig::Shadowsocks(config) => {
            run_shadowsocks_client_listener(listener, config).await
        }
    }
}
