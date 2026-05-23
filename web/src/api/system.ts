import { invoke } from '@tauri-apps/api/core'
import { disable, enable, isEnabled } from '@tauri-apps/plugin-autostart'
import { openUrl } from '@tauri-apps/plugin-opener'
import { WebviewWindow } from '@tauri-apps/api/webviewWindow'
import { publicErrorText } from '../format'

export interface RuntimeCapabilities {
  platform: string
  system_proxy: boolean
  autostart: boolean
  tray: boolean
  local_socks: boolean
  vpn: boolean
  payment: boolean
  admob: boolean
}

export interface RuntimeConfig {
  app_name: string
  default_api_url: string
  user_agent: string
  oauth_callback_scheme: string
}

export interface RewardedAdRequest {
  adUnitId: string
  userId: string
  customData: string
}

export interface RewardedAdResult {
  earned: boolean
  rewardType: string
  rewardAmount: number
}

export async function runtimeCapabilities(): Promise<RuntimeCapabilities> {
  return invoke('runtime_capabilities')
}

export async function runtimeConfig(): Promise<RuntimeConfig> {
  return invoke('runtime_config')
}

export async function takeOAuthCallback(): Promise<string | null> {
  return invoke<string | null>('oauth_take_callback')
}

export async function resolveNodeHost(dnsUrl: string, host: string, userAgent = ''): Promise<string> {
  return invoke('resolve_node_host', { dnsUrl, host, userAgent })
}

export async function systemProxySet(host: string, port: number): Promise<void> {
  await invoke('system_proxy_set', { host, port })
}

export async function systemProxyClear(): Promise<void> {
  await invoke('system_proxy_clear')
}

export async function autostartIsEnabled(): Promise<boolean> {
  return isEnabled()
}

export async function autostartSetEnabled(value: boolean): Promise<void> {
  if (value) await enable()
  else await disable()
}

export async function openExternal(url: string): Promise<void> {
  try {
    await openUrl(url)
  } catch (error) {
    throw new Error(publicErrorText(error, '无法打开链接'))
  }
}

export async function openInAppBrowser(url: string, title = 'Browser'): Promise<void> {
  const capabilities = await runtimeCapabilities()
  if (capabilities.platform === 'android' || capabilities.platform === 'ios') {
    try {
      await openUrl(url, 'inAppBrowser')
    } catch (error) {
      throw new Error(publicErrorText(error, '无法打开链接'))
    }
    return
  }
  await new Promise<void>((resolve, reject) => {
    const label = `browser-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    const webview = new WebviewWindow(label, {
      url,
      title,
      width: 1024,
      height: 720,
      minWidth: 720,
      minHeight: 520,
      focus: true,
    })
    void webview.once('tauri://created', () => resolve())
    void webview.once('tauri://error', (event) => reject(new Error(publicErrorText(event.payload, '无法打开链接'))))
  })
}

export async function showRewardedAd(request: RewardedAdRequest): Promise<RewardedAdResult> {
  return invoke('admob_show_rewarded', { request })
}

export async function showAppOpenAd(adUnitId: string): Promise<{ shown: boolean }> {
  return invoke('admob_show_app_open', { request: { adUnitId } })
}

export interface AndroidVpnPayload {
  nodeJson: string
  nodesJson: string
  nodeIndex: number
  excludedApps: string
  allowedApps: string
  nodeDns: string
  overseasDns: string
  directDns: string
  dnsMode: string
  virtualDnsPool: string
  ipv6Enabled: boolean
}

export async function androidStartVpn(request: AndroidVpnPayload): Promise<unknown> {
  return invoke('android_start_vpn', { request })
}

export async function androidStopVpn(): Promise<unknown> {
  return invoke('android_stop_vpn')
}

export async function androidGetVpnState(): Promise<{ running: boolean; nodeIndex: number }> {
  return invoke('android_get_vpn_state')
}
