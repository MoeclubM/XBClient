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
  /** 当前 Electron 进程是否为受支持的桌面平台（Windows / Linux）。 */
  isSupportedDesktop: () => boolean
  getDesktopPlatform: () => string
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
  autostartSetEnabled: (value: boolean, silent?: boolean) => Promise<boolean>
  launchedSilent: () => boolean
  hideMainWindow: () => Promise<void>
  windowMinimize: () => Promise<boolean>
  windowMaximize: () => Promise<boolean>
  windowClose: () => Promise<boolean>
  windowIsMaximized: () => Promise<boolean>
  onWindowMaximizedChanged: (handler: (maximized: boolean) => void) => () => void
  reportVpnSession: (sessionId: number | null) => Promise<void>
  syncTrayState: (state: import('./electron-tray-sync').TrayStateSnapshot) => Promise<void>
  onTrayStateFromMain: (handler: (patch: import('./electron-tray-sync').TrayStatePushFromMain) => void) => () => void
  onBackendReady: (handler: () => void) => () => void
  onBackendError: (handler: (message: string) => void) => () => void
}

declare global {
  interface Window {
    electronAPI: ElectronAPI
  }
}
