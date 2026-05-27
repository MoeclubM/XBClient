export interface ElectronAPIInvoke {
  <T = unknown>(cmd: string, args?: unknown): Promise<T>
}

export interface ElectronAPI {
  invoke: ElectronAPIInvoke
  getVersion: () => Promise<string>
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
