import { Store } from '@tauri-apps/plugin-store'
import type { AppSettings } from '.'

const SESSION_FILE = 'session.json'
const SETTINGS_FILE = 'settings.json'

export interface PersistedSession {
  baseUrl: string
  authData: string
  email: string
}

const sessionStore = (() => {
  let promise: Promise<Store> | null = null
  return () => (promise ??= Store.load(SESSION_FILE))
})()

const settingsStore = (() => {
  let promise: Promise<Store> | null = null
  return () => (promise ??= Store.load(SETTINGS_FILE))
})()

export async function loadSession(): Promise<PersistedSession | null> {
  const store = await sessionStore()
  const baseUrl = await store.get<string>('baseUrl')
  const authData = await store.get<string>('authData')
  const email = await store.get<string>('email')
  if (!baseUrl || !authData) return null
  return { baseUrl, authData, email: email ?? '' }
}

export async function saveSession(session: PersistedSession): Promise<void> {
  const store = await sessionStore()
  await store.set('baseUrl', session.baseUrl)
  await store.set('authData', session.authData)
  await store.set('email', session.email)
  await store.save()
}

export async function clearSession(): Promise<void> {
  const store = await sessionStore()
  await store.delete('baseUrl')
  await store.delete('authData')
  await store.delete('email')
  await store.save()
}

export async function loadSettings(): Promise<Partial<AppSettings>> {
  const store = await settingsStore()
  const autoApplyProxy = await store.get<boolean>('autoApplyProxy')
  const autostart = await store.get<boolean>('autostart')
  const nodeDns = await store.get<string>('nodeDns')
  const nodeTestTarget = await store.get<string>('nodeTestTarget')
  const apiUserAgent = await store.get<string>('apiUserAgent')
  const themeMode = await store.get<string>('themeMode')
  const appLanguage = await store.get<string>('appLanguage')
  const result: Partial<AppSettings> = {}
  if (typeof autoApplyProxy === 'boolean') result.autoApplyProxy = autoApplyProxy
  if (typeof autostart === 'boolean') result.autostart = autostart
  if (typeof nodeDns === 'string' && nodeDns.trim()) result.nodeDns = nodeDns
  if (typeof nodeTestTarget === 'string' && nodeTestTarget.trim()) result.nodeTestTarget = nodeTestTarget
  if (typeof apiUserAgent === 'string') result.apiUserAgent = apiUserAgent
  if (themeMode === 'system' || themeMode === 'light' || themeMode === 'dark') result.themeMode = themeMode
  if (appLanguage === 'system' || appLanguage === 'zh-CN' || appLanguage === 'en' || appLanguage === 'ja' || appLanguage === 'ru' || appLanguage === 'fa') {
    result.appLanguage = appLanguage
  }
  return result
}

export async function saveSettings(settings: AppSettings): Promise<void> {
  const store = await settingsStore()
  await store.set('autoApplyProxy', settings.autoApplyProxy)
  await store.set('autostart', settings.autostart)
  await store.set('nodeDns', settings.nodeDns)
  await store.set('nodeTestTarget', settings.nodeTestTarget)
  await store.set('apiUserAgent', settings.apiUserAgent)
  await store.set('themeMode', settings.themeMode)
  await store.set('appLanguage', settings.appLanguage)
  await store.save()
}
