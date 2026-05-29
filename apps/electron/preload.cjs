const { contextBridge, ipcRenderer } = require('electron')

const SUPPORTED_DESKTOP_PLATFORMS = new Set(['win32', 'linux'])

contextBridge.exposeInMainWorld('electronAPI', {
  isSupportedDesktop: () => SUPPORTED_DESKTOP_PLATFORMS.has(process.platform),
  getDesktopPlatform: () => process.platform,
  invoke: (cmd, args) => ipcRenderer.invoke('electron-invoke', { cmd, args }),
  getVersion: () => ipcRenderer.invoke('electron-get-version'),
  getRuntimeConfig: () => ipcRenderer.invoke('electron-get-runtime-config'),
  getRuntimeCapabilities: () => ipcRenderer.invoke('electron-get-runtime-capabilities'),
  openExternal: (url) => ipcRenderer.invoke('electron-open-external', { url }),
  openInAppBrowser: (url, title) => ipcRenderer.invoke('electron-open-inapp-browser', { url, title }),
  takeOAuthCallback: () => ipcRenderer.invoke('electron-oauth-take-callback'),
  onOAuthCallback: (handler) => {
    const listener = (_, url) => handler(url)
    ipcRenderer.on('oauth-callback', listener)
    return () => ipcRenderer.removeListener('oauth-callback', listener)
  },
  onAeronEvent: (handler) => {
    const listener = (_, payload) => handler(payload)
    ipcRenderer.on('aerion-event', listener)
    return () => ipcRenderer.removeListener('aerion-event', listener)
  },
  autostartIsEnabled: () => ipcRenderer.invoke('electron-autostart-is-enabled'),
  autostartSetEnabled: (value, silent) => ipcRenderer.invoke('electron-autostart-set-enabled', { value, silent }),
  launchedSilent: () => ipcRenderer.sendSync('electron-launched-silent'),
  hideMainWindow: () => ipcRenderer.invoke('electron-hide-main-window'),
  windowMinimize: () => ipcRenderer.invoke('electron-window-minimize'),
  windowMaximize: () => ipcRenderer.invoke('electron-window-maximize'),
  windowClose: () => ipcRenderer.invoke('electron-window-close'),
  windowIsMaximized: () => ipcRenderer.invoke('electron-window-is-maximized'),
  onWindowMaximizedChanged: (handler) => {
    const listener = (_, maximized) => handler(Boolean(maximized))
    ipcRenderer.on('window-maximized-changed', listener)
    return () => ipcRenderer.removeListener('window-maximized-changed', listener)
  },
  reportVpnSession: (sessionId) => ipcRenderer.invoke('electron-report-vpn-session', { sessionId }),
  syncTrayState: (state) => ipcRenderer.invoke('electron-sync-tray-state', { state }),
  onTrayStateFromMain: (handler) => {
    const listener = (_, patch) => handler(patch)
    ipcRenderer.on('tray-state-from-main', listener)
    return () => ipcRenderer.removeListener('tray-state-from-main', listener)
  },
  onBackendReady: (handler) => {
    const listener = () => handler()
    ipcRenderer.on('backend-ready', listener)
    return () => ipcRenderer.removeListener('backend-ready', listener)
  },
  onBackendError: (handler) => {
    const listener = (_, message) => {
      if (typeof message !== 'string' || !message.trim()) throw new Error('backend-error message is required')
      handler(message)
    }
    ipcRenderer.on('backend-error', listener)
    return () => ipcRenderer.removeListener('backend-error', listener)
  },
})
