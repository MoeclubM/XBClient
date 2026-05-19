import { invoke } from '@tauri-apps/api/core'
import { disable, enable, isEnabled } from '@tauri-apps/plugin-autostart'
import { openUrl } from '@tauri-apps/plugin-opener'
import { WebviewWindow } from '@tauri-apps/api/webviewWindow'

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
  await openUrl(url)
}

export async function openInAppBrowser(url: string, title = 'SecOVPN'): Promise<void> {
  const capabilities = await runtimeCapabilities()
  if (capabilities.platform === 'android' || capabilities.platform === 'ios') {
    await openUrl(url, 'inAppBrowser')
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
    void webview.once('tauri://error', (event) => reject(new Error(String(event.payload))))
  })
}

export async function showRewardedAd(request: RewardedAdRequest): Promise<RewardedAdResult> {
  return invoke('admob_show_rewarded', { request })
}

export async function showAppOpenAd(adUnitId: string): Promise<{ shown: boolean }> {
  return invoke('admob_show_app_open', { request: { adUnitId } })
}
