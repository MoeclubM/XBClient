import type { AppSettings } from '.'

const SESSION_KEY = 'xbclient.session.v1'
const SETTINGS_KEY = 'xbclient.settings.v1'

export interface PersistedSession {
  authData: string
  email: string
}

function readJson<T>(key: string): T | null {
  const text = window.localStorage.getItem(key)
  if (!text) return null
  try {
    return JSON.parse(text) as T
  } catch {
    return null
  }
}

function writeJson(key: string, value: unknown): void {
  window.localStorage.setItem(key, JSON.stringify(value))
}

export async function loadSession(): Promise<PersistedSession | null> {
  const raw = readJson<Partial<PersistedSession>>(SESSION_KEY)
  const authData = typeof raw?.authData === 'string' ? raw.authData : ''
  if (!authData.trim()) return null
  const email = typeof raw?.email === 'string' ? raw.email : ''
  return { authData, email }
}

export async function saveSession(session: PersistedSession): Promise<void> {
  writeJson(SESSION_KEY, { authData: session.authData, email: session.email })
}

export async function clearSession(): Promise<void> {
  window.localStorage.removeItem(SESSION_KEY)
}

export async function loadSettings(): Promise<Partial<AppSettings>> {
  const raw = readJson<Partial<AppSettings>>(SETTINGS_KEY) ?? {}
  const result: Partial<AppSettings> = {}
  if (typeof raw.autoApplyProxy === 'boolean') result.autoApplyProxy = raw.autoApplyProxy
  if (typeof raw.autostart === 'boolean') result.autostart = raw.autostart
  if (typeof raw.nodeDns === 'string' && raw.nodeDns.trim()) result.nodeDns = raw.nodeDns
  if (typeof raw.overseasDns === 'string' && raw.overseasDns.trim()) result.overseasDns = raw.overseasDns
  if (typeof raw.directDns === 'string' && raw.directDns.trim()) result.directDns = raw.directDns
  if (typeof raw.nodeTestTarget === 'string' && raw.nodeTestTarget.trim()) result.nodeTestTarget = raw.nodeTestTarget
  if (raw.vpnDnsMode === 'virtual' || raw.vpnDnsMode === 'over_tcp' || raw.vpnDnsMode === 'direct') result.vpnDnsMode = raw.vpnDnsMode
  if (typeof raw.virtualDnsPool === 'string' && raw.virtualDnsPool.trim()) result.virtualDnsPool = raw.virtualDnsPool
  if (typeof raw.vpnIpv6Enabled === 'boolean') result.vpnIpv6Enabled = raw.vpnIpv6Enabled
  if (raw.appRuleMode === 'exclude' || raw.appRuleMode === 'allow') result.appRuleMode = raw.appRuleMode
  if (typeof raw.excludedApps === 'string') result.excludedApps = raw.excludedApps
  if (typeof raw.allowedApps === 'string') result.allowedApps = raw.allowedApps
  if (raw.themeMode === 'system' || raw.themeMode === 'light' || raw.themeMode === 'dark') result.themeMode = raw.themeMode
  if (
    raw.appLanguage === 'system' ||
    raw.appLanguage === 'zh-CN' ||
    raw.appLanguage === 'en' ||
    raw.appLanguage === 'ja' ||
    raw.appLanguage === 'ru' ||
    raw.appLanguage === 'fa'
  ) result.appLanguage = raw.appLanguage
  return result
}

export async function saveSettings(settings: AppSettings): Promise<void> {
  writeJson(SETTINGS_KEY, settings)
}
