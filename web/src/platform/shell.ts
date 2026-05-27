export function isDesktopShell(): boolean {
  return typeof window !== 'undefined' && Boolean(window.electronAPI)
}
