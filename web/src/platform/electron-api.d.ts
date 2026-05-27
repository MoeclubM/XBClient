export interface ElectronRuntimeCapabilities {
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

export interface ElectronAPIInvoke {
  <T = unknown>(cmd: string, args?: unknown): Promise<T>
}

export interface ElectronAPI {
  invoke: ElectronAPIInvoke
  getVersion: () => Promise<string>
  getRuntimeConfig: () => Promise<{
    app_name: string
    default_api_url: string
    user_agent: string
    oauth_callback_scheme: string
  }>
  getRuntimeCapabilities: () => Promise<ElectronRuntimeCapabilities>
  openExternal: (url: string) => Promise<boolean>
  openInAppBrowser: (url: string, title?: string) => Promise<boolean>
  takeOAuthCallback: () => Promise<string | null>
  onOAuthCallback: (handler: (url: string) => void) => () => void
  onAeronEvent: (handler: (payload: string) => void) => () => void
  autostartIsEnabled: () => Promise<boolean>
  autostartSetEnabled: (value: boolean) => Promise<boolean>
  reportVpnSession: (sessionId: number | null) => Promise<void>
  onBackendReady: (handler: () => void) => () => void
  onBackendError: (handler: (message: string) => void) => () => void
}

declare global {
  interface Window {
    electronAPI: ElectronAPI
  }
}
