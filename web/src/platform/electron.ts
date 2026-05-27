export function assertElectronAPI() {
  if (!window.electronAPI) throw new Error('electronAPI is missing')
  return window.electronAPI
}

export async function invoke<T = unknown>(cmd: string, args?: unknown): Promise<T> {
  return assertElectronAPI().invoke<T>(cmd, args)
}

export function onAeronEvent(handler: (payload: string) => void): () => void {
  return assertElectronAPI().onAeronEvent(handler)
}

export async function getVersion(): Promise<string> {
  return assertElectronAPI().getVersion()
}

export async function openExternal(url: string): Promise<void> {
  await assertElectronAPI().openExternal(url)
}

export async function openInAppBrowser(url: string, title = 'Browser'): Promise<void> {
  await assertElectronAPI().openInAppBrowser(url, title)
}

export async function autostartIsEnabled(): Promise<boolean> {
  return assertElectronAPI().autostartIsEnabled()
}

export async function autostartSetEnabled(value: boolean): Promise<void> {
  await assertElectronAPI().autostartSetEnabled(value)
}

export async function takeOAuthCallback(): Promise<string | null> {
  return assertElectronAPI().takeOAuthCallback()
}

export function onOAuthCallback(handler: (url: string) => void): () => void {
  return assertElectronAPI().onOAuthCallback(handler)
}

export async function reportVpnSession(sessionId: number | null): Promise<void> {
  await assertElectronAPI().reportVpnSession(sessionId)
}

