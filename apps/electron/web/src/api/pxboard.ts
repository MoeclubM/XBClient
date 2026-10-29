import { invoke } from '../platform/electron'
import { openExternal } from './system'
import { useAppStore, type SubscriptionState } from '../store'
import { subscriptionFetch } from './xboard'
import { toAppNode, type RawNode } from '../nodes'
import { formatTrafficBytes, formatUnixDate } from '../format'
import { saveSession, saveSubscriptionCache } from '../store/persist'

export const isPxBoard = () => useAppStore.getState().buildConfig?.panel_type === 'pxboard'

export async function pxboardRequest<T>(path: string, body?: unknown, authData = useAppStore.getState().authData): Promise<T> {
  const config = useAppStore.getState().buildConfig!
  const headers: Record<string, string> = { 'User-Agent': config.user_agent }
  if (authData) headers.Authorization = 'Bearer ' + authData.replace(/^PxBoard /, '')
  const response = await invoke<{ ok: boolean; body: { data: T; message: string } }>('xboard_request', {
    request: { method: body === undefined ? 'GET' : 'POST', url: config.default_api_url.replace(/\/$/, '') + '/api/v1' + path, headers, body },
  })
  if (!response.ok) throw new Error(response.body.message)
  return response.body.data
}

export async function loginPxBoard(signal: AbortSignal): Promise<void> {
  const config = useAppStore.getState().buildConfig!
  const proof = Array.from(crypto.getRandomValues(new Uint8Array(32)), value => value.toString(16).padStart(2, '0')).join('')
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(proof))
  const challenge = Array.from(new Uint8Array(hash), value => value.toString(16).padStart(2, '0')).join('')
  const flow = await pxboardRequest<{ id: string; authorizationUrl: string; expiresAt: string; intervalSeconds: number }>('/auth/native', { name: config.app_name, challenge }, '')
  await openExternal(flow.authorizationUrl)
  while (Date.now() < Date.parse(flow.expiresAt)) {
    await new Promise<void>((resolve, reject) => {
      signal.throwIfAborted()
      const abort = () => { clearTimeout(timer); reject(signal.reason) }
      const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, flow.intervalSeconds * 1000)
      signal.addEventListener('abort', abort, { once: true })
    })
    const result = await pxboardRequest<{ status: string; token: string; user: { email: string } }>(`/auth/native/${flow.id}/token`, { proof }, '')
    signal.throwIfAborted()
    if (result.status === 'pending') continue
    const authData = 'PxBoard ' + result.token
    useAppStore.getState().setSession({ baseUrl: config.default_api_url, authData, email: result.user.email })
    await saveSession({ authData, email: result.user.email, baseUrl: config.default_api_url, panelType: config.panel_type })
    return
  }
  throw new Error('Authorization expired. Please sign in again.')
}

export async function openPxBoardPortal(redirectPath = '/user'): Promise<void> {
  const result = await pxboardRequest<{ url: string }>('/auth/browser-link', { redirectPath })
  await openExternal(result.url)
}

interface AccessAccount {
  status: string; effective: boolean; unlimited: boolean; usedGiB: number; quotaGiB: number; expiresAt: string
}

export async function syncPxBoard(): Promise<string | null> {
  const current = useAppStore.getState()
  const auth = current.authData
  const [user, accounts, plugins] = await Promise.all([
    pxboardRequest<{ email: string }>('/me'),
    pxboardRequest<AccessAccount[]>('/extensions/access/accounts?mine=1'),
    pxboardRequest<Array<{ id: string; enabled: boolean }>>('/plugins'),
  ])
  const account = accounts[0]
  const used = (account?.usedGiB ?? 0) * 1073741824
  const total = (account?.quotaGiB ?? 0) * 1073741824
  const expiredAt = account?.expiresAt ? Date.parse(account.expiresAt) / 1000 : 0
  const blockReason = !account ? 'no_plan' : account.status !== 'active' ? 'suspended' : expiredAt && expiredAt <= Date.now() / 1000 ? 'expired' : !account.unlimited && used >= total ? 'traffic_exceeded' : ''
  let nodes: ReturnType<typeof toAppNode>[] = []
  let routing = { has_rules: false, rule_count: 0, proxy_group_count: 0, rule_provider_count: 0, rules_preview: [] as string[], route_config_yaml: null as string | null }
  if (!blockReason) {
    const config = await pxboardRequest<{ nodes: RawNode[] }>('/extensions/subscriptions/configuration?format=aerion')
    nodes = config.nodes.map(toAppNode)
    const response = await subscriptionFetch(current.baseUrl.replace(/\/$/, '') + '/api/v1/extensions/subscriptions/configuration?format=clash', 'meta', 'Bearer ' + auth.replace(/^PxBoard /, ''))
    if (!response.ok || !response.routing) throw new Error(response.error || 'Subscription routing missing')
    routing = response.routing
  }
  if (useAppStore.getState().authData !== auth) return null
  current.setProfile({ panelExtensions: plugins.filter(p => p.enabled).map(p => p.id) })
  current.setSubscribe({ subscribeUrl: '', nodes })
  current.setRouting({ hasRules: routing.has_rules, ruleCount: routing.rule_count, proxyGroupCount: routing.proxy_group_count, ruleProviderCount: routing.rule_provider_count, rulesPreview: routing.rules_preview, routeConfigYaml: routing.route_config_yaml })
  const subscription: SubscriptionState = { summary: [formatTrafficBytes(used) + ' / ' + (account?.unlimited ? '∞' : formatTrafficBytes(total)), expiredAt ? formatUnixDate(expiredAt) : ''].filter(Boolean).join(' · '), blockReason, trafficUsedBytes: used, trafficTotalBytes: total, planName: '', expiredAt }
  current.setSubscriptionState(subscription)
  current.setSession({ baseUrl: current.baseUrl, authData: auth, email: user.email })
  await saveSubscriptionCache({ authData: auth, subscribeUrl: '', nodes, subscription, routing: useAppStore.getState().routing })
  return null
}
