import type { AppNode, AppSettings, RouteRoutingState, SubscriptionState } from '.'

const SESSION_KEY = 'xbclient.session.v1'
const SETTINGS_KEY = 'xbclient.settings.v1'
const SUBSCRIPTION_CACHE_KEY = 'xbclient.subscription-cache.v1'

export interface PersistedSession {
  authData: string
  email: string
}

export interface PersistedSubscriptionCache {
  authData: string
  subscribeUrl: string
  nodes: AppNode[]
  subscription: SubscriptionState
  routing: RouteRoutingState
}

function readJson<T>(key: string): T | null {
  const text = window.localStorage.getItem(key)
  if (!text) return null
  return JSON.parse(text) as T
}

function writeJson(key: string, value: unknown): void {
  window.localStorage.setItem(key, JSON.stringify(value))
}

export async function loadSession(): Promise<PersistedSession | null> {
  const raw = readJson<PersistedSession>(SESSION_KEY)
  if (!raw) return null
  if (!raw.authData.trim()) return null
  return raw
}

export async function saveSession(session: PersistedSession): Promise<void> {
  writeJson(SESSION_KEY, { authData: session.authData, email: session.email })
}

export async function clearSession(): Promise<void> {
  window.localStorage.removeItem(SESSION_KEY)
  window.localStorage.removeItem(SUBSCRIPTION_CACHE_KEY)
}

export async function loadSettings(): Promise<Partial<AppSettings>> {
  return readJson<AppSettings>(SETTINGS_KEY) ?? {}
}

export async function saveSettings(settings: AppSettings): Promise<void> {
  writeJson(SETTINGS_KEY, settings)
}

export async function loadSubscriptionCache(authData: string): Promise<PersistedSubscriptionCache | null> {
  const cache = readJson<PersistedSubscriptionCache>(SUBSCRIPTION_CACHE_KEY)
  if (!cache) return null
  if (cache.authData !== authData) return null
  return cache
}

export async function saveSubscriptionCache(cache: PersistedSubscriptionCache): Promise<void> {
  writeJson(SUBSCRIPTION_CACHE_KEY, cache)
}
