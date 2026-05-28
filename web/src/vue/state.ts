import { reactive } from 'vue'
import { syncGuestAuthConfig } from '../api/guestConfig'
import { autostartIsEnabled, autostartSetEnabled, runtimeCapabilities, runtimeConfig } from '../api/system'
import { applyDesktopConnection, isDesktopConnectionShell } from '../desktop/connection'
import { hideMainWindow, launchedSilent } from '../platform/electron'
import { isDesktopShell } from '../platform/shell'
import { useAppStore, type AppSettings } from '../store'
import { loadSession, loadSettings, saveSettings } from '../store/persist'
import { translate, type TranslationKey } from '../i18n'

type LegacyState = ReturnType<typeof useAppStore.getState>

export const appState = reactive(useAppStore.getState()) as LegacyState

useAppStore.subscribe((state) => {
  Object.assign(appState, state)
})

export function store() {
  return useAppStore.getState()
}

export function t(key: TranslationKey): string {
  return translate(key, appState.settings.appLanguage)
}

export async function persistSettings(patch: Partial<AppSettings>): Promise<void> {
  const next = { ...store().settings, ...patch }
  store().setSettings(patch)
  await saveSettings(next)
}

export async function bootstrapApp(): Promise<void> {
  const config = await runtimeConfig()
  store().setBuildConfig(config)

  const session = await loadSession()
  if (session) store().setSession({ ...session, baseUrl: config.default_api_url })

  const persisted = await loadSettings()
  if (Object.keys(persisted).length > 0) store().setSettings(persisted)

  const capabilities = await runtimeCapabilities()
  store().setCapabilities(capabilities)
  store().setProfile({ paymentEnabled: true })

  if (!capabilities.system_proxy) store().setSettings({ autoApplyProxy: false })
  if (capabilities.vpn) store().setSettings({ autoApplyProxy: false })
  if (capabilities.autostart && isDesktopShell()) {
    const autostart = await autostartIsEnabled()
    store().setSettings({ autostart })
    if (autostart) await autostartSetEnabled(true, store().settings.silentStart)
  } else {
    store().setSettings({ autostart: false })
  }

  if (isDesktopShell() && (launchedSilent() || store().settings.silentStart)) {
    await hideMainWindow().catch(() => {})
  }

  if (isDesktopConnectionShell() && store().authData) {
    const message = await applyDesktopConnection()
    if (message) console.error('desktop connection sync failed', message)
  }

  try {
    await syncGuestAuthConfig(config.default_api_url)
  } catch (error) {
    if (!session?.authData) throw error
    console.error('guest auth config sync failed', error)
  }
}

export function applyDocumentTheme(): void {
  const themeMode = appState.settings.themeMode
  const el = document.documentElement
  if (themeMode === 'light' || themeMode === 'dark') el.setAttribute('data-theme', themeMode)
  else el.removeAttribute('data-theme')
}

export function preventDesktopZoom(): () => void {
  const preventGesture = (event: Event) => event.preventDefault()
  const preventWheelZoom = (event: WheelEvent) => {
    if (event.ctrlKey) event.preventDefault()
  }
  const preventKeyZoom = (event: KeyboardEvent) => {
    if ((event.ctrlKey || event.metaKey) && ['+', '-', '=', '0'].includes(event.key)) event.preventDefault()
  }
  document.addEventListener('gesturestart', preventGesture)
  document.addEventListener('gesturechange', preventGesture)
  window.addEventListener('wheel', preventWheelZoom, { passive: false })
  window.addEventListener('keydown', preventKeyZoom)
  return () => {
    document.removeEventListener('gesturestart', preventGesture)
    document.removeEventListener('gesturechange', preventGesture)
    window.removeEventListener('wheel', preventWheelZoom)
    window.removeEventListener('keydown', preventKeyZoom)
  }
}
