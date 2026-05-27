import { publicErrorText } from '../format'
import {
  autostartIsEnabled as electronAutostartIsEnabled,
  autostartSetEnabled as electronAutostartSetEnabled,
  invoke,
  onOAuthCallback as electronOnOAuthCallback,
  openExternal as electronOpenExternal,
  openInAppBrowser as openInAppBrowserDesktop,
  takeOAuthCallback as electronTakeOAuthCallback,
} from '../platform/electron'

export interface RuntimeCapabilities {
  platform: string
  system_proxy: boolean
  oauth_callback: boolean
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

export async function runtimeCapabilities(): Promise<RuntimeCapabilities> {
  return invoke('runtime_capabilities')
}

export async function runtimeConfig(): Promise<RuntimeConfig> {
  return invoke('runtime_config')
}

export async function takeOAuthCallback(): Promise<string | null> {
  return electronTakeOAuthCallback()
}

export function onOAuthCallback(handler: (url: string) => void): () => void {
  return electronOnOAuthCallback(handler)
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

export function parseSocksAddr(addr: string): { host: string; port: number } {
  const idx = addr.lastIndexOf(':')
  if (idx <= 0) throw new Error(`Invalid SOCKS address: ${addr}`)
  const port = Number(addr.slice(idx + 1))
  if (!Number.isFinite(port) || port <= 0) throw new Error(`Invalid SOCKS port: ${addr}`)
  return { host: addr.slice(0, idx), port }
}

export async function autostartIsEnabled(): Promise<boolean> {
  return electronAutostartIsEnabled()
}

export async function autostartSetEnabled(value: boolean): Promise<void> {
  await electronAutostartSetEnabled(value)
}

export async function openExternal(url: string): Promise<void> {
  try {
    await electronOpenExternal(url)
  } catch (error) {
    throw new Error(publicErrorText(error, 'Unable to open link'))
  }
}

export async function openInAppBrowser(url: string, title = 'Browser'): Promise<void> {
  try {
    await openInAppBrowserDesktop(url, title)
  } catch (error) {
    throw new Error(publicErrorText(error, 'Unable to open link'))
  }
}
