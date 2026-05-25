import { Store } from '@tauri-apps/plugin-store'
import type { AppSettings } from '.'

const SESSION_FILE = 'session.json'
const SETTINGS_FILE = 'settings.json'

export interface PersistedSession {
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
  const authData = await store.get<string>('authData')
  const email = await store.get<string>('email')
  if (!authData) return null
  return { authData, email: email ?? '' }
}

export async function saveSession(session: PersistedSession): Promise<void> {
  const store = await sessionStore()
  await store.delete('baseUrl')
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
  const overseasDns = await store.get<string>('overseasDns')
  const directDns = await store.get<string>('directDns')
  const nodeTestTarget = await store.get<string>('nodeTestTarget')
  const vpnDnsMode = await store.get<string>('vpnDnsMode')
  const virtualDnsPool = await store.get<string>('virtualDnsPool')
  const vpnIpv6Enabled = await store.get<boolean>('vpnIpv6Enabled')
  const appRuleMode = await store.get<string>('appRuleMode')
  const excludedApps = await store.get<string>('excludedApps')
  const allowedApps = await store.get<string>('allowedApps')
  const themeMode = await store.get<string>('themeMode')
  const appLanguage = await store.get<string>('appLanguage')
  const result: Partial<AppSettings> = {}
  if (typeof autoApplyProxy === 'boolean') result.autoApplyProxy = autoApplyProxy
  if (typeof autostart === 'boolean') result.autostart = autostart
  if (typeof nodeDns === 'string' && nodeDns.trim()) result.nodeDns = nodeDns
  if (typeof overseasDns === 'string' && overseasDns.trim()) result.overseasDns = overseasDns
  if (typeof directDns === 'string' && directDns.trim()) result.directDns = directDns
  if (typeof nodeTestTarget === 'string' && nodeTestTarget.trim()) result.nodeTestTarget = nodeTestTarget
  if (vpnDnsMode === 'virtual' || vpnDnsMode === 'over_tcp' || vpnDnsMode === 'direct') result.vpnDnsMode = vpnDnsMode
  if (typeof virtualDnsPool === 'string' && virtualDnsPool.trim()) result.virtualDnsPool = virtualDnsPool
  if (typeof vpnIpv6Enabled === 'boolean') result.vpnIpv6Enabled = vpnIpv6Enabled
  if (appRuleMode === 'exclude' || appRuleMode === 'allow') result.appRuleMode = appRuleMode
  if (typeof excludedApps === 'string') result.excludedApps = excludedApps
  if (typeof allowedApps === 'string') result.allowedApps = allowedApps
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
  await store.set('overseasDns', settings.overseasDns)
  await store.set('directDns', settings.directDns)
  await store.set('nodeTestTarget', settings.nodeTestTarget)
  await store.set('vpnDnsMode', settings.vpnDnsMode)
  await store.set('virtualDnsPool', settings.virtualDnsPool)
  await store.set('vpnIpv6Enabled', settings.vpnIpv6Enabled)
  await store.set('appRuleMode', settings.appRuleMode)
  await store.set('excludedApps', settings.excludedApps)
  await store.set('allowedApps', settings.allowedApps)
  await store.set('themeMode', settings.themeMode)
  await store.set('appLanguage', settings.appLanguage)
  await store.save()
}
