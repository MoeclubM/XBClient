import { invoke } from '@tauri-apps/api/core'
import { disable, enable, isEnabled } from '@tauri-apps/plugin-autostart'
import { openUrl } from '@tauri-apps/plugin-opener'

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
