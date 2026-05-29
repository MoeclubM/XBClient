export function assertElectronAPI() {
  if (!window.electronAPI) throw new Error('electronAPI is missing')
  return window.electronAPI
}

export async function invoke<T = unknown>(cmd: string, args?: unknown, timeoutMs = 60_000): Promise<T> {
  const call = assertElectronAPI().invoke<T>(cmd, args)
  if (!timeoutMs) return call
  return Promise.race([
    call,
    new Promise<T>((_, reject) => {
      setTimeout(() => reject(new Error('后端请求超时')), timeoutMs)
    }),
  ])
}

export function onBackendReady(handler: () => void): () => void {
  return assertElectronAPI().onBackendReady(handler)
}

export function onBackendError(handler: (message: string) => void): () => void {
  return assertElectronAPI().onBackendError(handler)
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

export async function openInAppBrowser(url: string, title: string): Promise<void> {
  await assertElectronAPI().openInAppBrowser(url, title)
}

export async function autostartIsEnabled(): Promise<boolean> {
  return assertElectronAPI().autostartIsEnabled()
}

export async function autostartSetEnabled(value: boolean, silent = false): Promise<void> {
  await assertElectronAPI().autostartSetEnabled(value, silent)
}

export function launchedSilent(): boolean {
  return assertElectronAPI().launchedSilent()
}

export async function hideMainWindow(): Promise<void> {
  await assertElectronAPI().hideMainWindow()
}

export async function minimizeWindow(): Promise<void> {
  await assertElectronAPI().windowMinimize()
}

export async function maximizeWindow(): Promise<boolean> {
  return assertElectronAPI().windowMaximize()
}

export async function closeWindow(): Promise<void> {
  await assertElectronAPI().windowClose()
}

export async function isWindowMaximized(): Promise<boolean> {
  return assertElectronAPI().windowIsMaximized()
}

export function onWindowMaximizedChanged(handler: (maximized: boolean) => void): () => void {
  return assertElectronAPI().onWindowMaximizedChanged(handler)
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

export type { TrayStatePushFromMain, TrayStateSnapshot } from './electron-tray-sync'

export async function syncTrayState(state: import('./electron-tray-sync').TrayStateSnapshot): Promise<void> {
  await assertElectronAPI().syncTrayState(state)
}

export function onTrayStateFromMain(
  handler: (patch: import('./electron-tray-sync').TrayStatePushFromMain) => void,
): () => void {
  return assertElectronAPI().onTrayStateFromMain(handler)
}
