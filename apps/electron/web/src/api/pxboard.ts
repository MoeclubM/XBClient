import { httpRequest } from './http'
import { openExternal } from './system'
import { useAppStore, type SubscriptionState } from '../store'
import { subscriptionFetch } from './subscriptions'
import { toAppNode } from '../nodes'
import { formatTrafficBytes, formatUnixDate } from '../format'
import { saveSubscriptionCache } from '../store/persist'

export const isPxBoard = () => useAppStore.getState().buildConfig?.panel_type === 'pxboard'

export async function pxboardRequest<T>(path: string, body?: unknown, authData = useAppStore.getState().authData): Promise<T> {
  const config = useAppStore.getState().buildConfig!
  const headers: Record<string, string> = { 'User-Agent': config.user_agent }
  if (authData) headers.Authorization = 'Bearer ' + authData.replace(/^PxBoard /, '')
  const response = await httpRequest<{ data: T; error: { code: string; message: string } }>({
    method: body === undefined ? 'GET' : 'POST', url: config.default_api_url.replace(/\/$/, '') + '/api/v1' + path, headers, body,
  })
  if (!response.ok) throw new Error(response.body.error.message)
  return response.body.data
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
  let routing = { has_rules: false, rule_count: 0, proxy_group_count: 0, rule_provider_count: 0, rules_preview: [] as string[], rules: [] as string[], route_config_yaml: null as string | null }
  if (!blockReason) {
    const response = await subscriptionFetch(current.baseUrl.replace(/\/$/, '') + '/api/v1/extensions/subscriptions/configuration?format=aerion', 'aerion', 'Bearer ' + auth.replace(/^PxBoard /, ''))
    if (!response.ok || !response.routing || !response.nodes) throw new Error(response.error || 'Subscription routing missing')
    routing = { ...response.routing, rules: response.routing.rules ?? response.routing.rules_preview }
    nodes = response.nodes.map(toAppNode)
  }
  if (useAppStore.getState().authData !== auth) return null
  current.setProfile({ panelExtensions: plugins.filter(p => p.enabled).map(p => p.id) })
  current.setSubscribe({ subscribeUrl: '', nodes })
  current.setRouting({ hasRules: routing.has_rules, ruleCount: routing.rule_count, proxyGroupCount: routing.proxy_group_count, ruleProviderCount: routing.rule_provider_count, rulesPreview: routing.rules_preview, rules: routing.rules, routeConfigYaml: routing.route_config_yaml })
  const subscription: SubscriptionState = { summary: [formatTrafficBytes(used) + ' / ' + (account?.unlimited ? '∞' : formatTrafficBytes(total)), expiredAt ? formatUnixDate(expiredAt) : ''].filter(Boolean).join(' · '), blockReason, trafficUsedBytes: used, trafficTotalBytes: total, planName: '', expiredAt }
  current.setSubscriptionState(subscription)
  current.setSession({ baseUrl: current.baseUrl, authData: auth, email: user.email })
  await saveSubscriptionCache({ authData: auth, subscribeUrl: '', nodes, subscription, routing: useAppStore.getState().routing })
  return null
}
