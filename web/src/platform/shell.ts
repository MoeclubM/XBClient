/** Preload 注入了 `window.electronAPI`（仅 Electron 壳，不含 Android WebView / 移动浏览器）。 */
export function isElectronShell(): boolean {
  return typeof window !== 'undefined' && Boolean(window.electronAPI)
}

/** Electron 桌面端：当前仅 Windows / Linux；macOS 预留，移动端不使用 Electron。 */
export function isDesktopShell(): boolean {
  if (!isElectronShell()) return false
  return window.electronAPI.isSupportedDesktop()
}

/** 移动浏览器或 WebView（Android 等）；不会加载 electronAPI。 */
export function isMobileShell(): boolean {
  if (typeof navigator === 'undefined') return false
  return /Android|iPhone|iPad|iPod/i.test(navigator.userAgent)
}
