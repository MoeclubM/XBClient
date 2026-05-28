import { publicErrorText } from '../format'
import { aerionNodeWithResolvedHost, rawNodeHost } from '../nodes'
import type { AppNode } from '../store'
import {
  autostartIsEnabled as electronAutostartIsEnabled,
  autostartSetEnabled as electronAutostartSetEnabled,
  invoke,
  onOAuthCallback as electronOnOAuthCallback,
  openExternal as electronOpenExternal,
  openInAppBrowser as openInAppBrowserDesktop,
  takeOAuthCallback as electronTakeOAuthCallback,
} from '../platform/electron'
import { isDesktopShell, isElectronShell } from '../platform/shell'

export interface RuntimeCapabilities {
  platform: string
  system_proxy: boolean
  oauth_callback: boolean
  autostart: boolean
  tray: boolean
  local_socks: boolean
  vpn: boolean
  tun_elevated?: boolean
  payment: boolean
}

export interface RuntimeConfig {
  app_name: string
  default_api_url: string
  user_agent: string
  oauth_callback_scheme: string
}

export async function runtimeCapabilities(): Promise<RuntimeCapabilities> {
  if (!isElectronShell()) throw new Error('runtimeCapabilities requires Electron shell')
  return window.electronAPI.getRuntimeCapabilities()
}

export async function runtimeConfig(): Promise<RuntimeConfig> {
  if (!isElectronShell()) throw new Error('runtimeConfig requires Electron shell')
  return window.electronAPI.getRuntimeConfig()
}

export async function takeOAuthCallback(): Promise<string | null> {
  if (!isDesktopShell()) throw new Error('OAuth callback requires supported Electron desktop shell')
  return electronTakeOAuthCallback()
}

export function onOAuthCallback(handler: (url: string) => void): () => void {
  if (!isDesktopShell()) throw new Error('OAuth callback requires supported Electron desktop shell')
  return electronOnOAuthCallback(handler)
}

export async function resolveNodeHost(dnsUrl: string, host: string, userAgent = ''): Promise<string> {
  return invoke('resolve_node_host', { dnsUrl, host, userAgent })
}

export async function resolveAppNode(node: AppNode, dnsUrl: string, userAgent = ''): Promise<unknown> {
  const resolvedHost = await resolveNodeHost(dnsUrl, rawNodeHost(node), userAgent)
  return aerionNodeWithResolvedHost(node, resolvedHost)
}

export async function systemProxySet(host: string, port: number): Promise<void> {
  if (!isDesktopShell()) throw new Error('systemProxySet requires supported Electron desktop shell')
  await invoke('system_proxy_set', { host, port })
}

export async function systemProxyClear(): Promise<void> {
  if (!isDesktopShell()) throw new Error('systemProxyClear requires supported Electron desktop shell')
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
  if (!isDesktopShell()) throw new Error('autostart requires supported Electron desktop shell')
  return electronAutostartIsEnabled()
}

export async function autostartSetEnabled(value: boolean, silent = false): Promise<void> {
  if (!isDesktopShell()) throw new Error('autostart requires supported Electron desktop shell')
  await electronAutostartSetEnabled(value, silent)
}

export async function openExternal(url: string): Promise<void> {
  if (!isElectronShell()) throw new Error('openExternal requires Electron shell')
  try {
    await electronOpenExternal(url)
  } catch (error) {
    throw new Error(publicErrorText(error, 'Unable to open link'))
  }
}

export async function openInAppBrowser(url: string, title = 'Browser'): Promise<void> {
  if (!isDesktopShell()) throw new Error('openInAppBrowser requires supported Electron desktop shell')
  try {
    await openInAppBrowserDesktop(url, title)
  } catch (error) {
    throw new Error(publicErrorText(error, 'Unable to open link'))
  }
}
