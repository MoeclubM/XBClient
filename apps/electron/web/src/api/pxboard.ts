import { httpRequest } from './http'
import { openExternal } from './system'
import { useAppStore, type NoticeItem, type RouteRoutingState, type SubscriptionState } from '../store'
import { toAppNode, type RawNode } from '../nodes'
import { formatTrafficBytes, formatUnixDate } from '../format'
import { saveSubscriptionCache } from '../store/persist'

export const isPxBoard = () => useAppStore.getState().buildConfig?.panel_type === 'pxboard'

export class PxBoardRpcError extends Error {
  code: string
  status: number
  constructor(message: string, code = '', status = 0) {
    super(message)
    this.name = 'PxBoardRpcError'
    this.code = code
    this.status = status
  }
}

export interface PxBoardBatchCall {
  key: string
  service: string
  method: string
  input?: Record<string, unknown>
}

export interface PxBoardBatchItem<T = any> {
  status: number
  data?: T
  error?: { code?: string; message?: string }
}

interface RpcEnvelope<T> {
  data?: T
  error?: { code?: string; message?: string }
}

export function pxboardBaseUrl(): string {
  const current = useAppStore.getState()
  const url = (current.baseUrl || current.buildConfig?.default_api_url || '').trim().replace(/\/$/, '')
  if (!url) throw new Error('PxBoard API URL is not configured')
  return url
}

export function pxboardPublicUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) return path
  const base = pxboardBaseUrl()
  return path.startsWith('/') ? `${base}${path}` : `${base}/${path}`
}

function buildHeaders(authData: string): Record<string, string> {
  const config = useAppStore.getState().buildConfig
  const headers: Record<string, string> = {}
  if (config?.user_agent) headers['User-Agent'] = config.user_agent
  if (authData) headers.Authorization = 'Bearer ' + authData.replace(/^PxBoard\s+/i, '')
  return headers
}

export async function pxboardRpc<T = any>(
  service: string,
  method: string,
  input: Record<string, unknown> = {},
  authData = useAppStore.getState().authData,
): Promise<T> {
  const response = await httpRequest<RpcEnvelope<T>>({
    method: 'POST',
    url: `${pxboardBaseUrl()}/rpc/v1/${encodeURIComponent(service)}/${method}`,
    headers: buildHeaders(authData),
    body: input,
  })
  if (!response.ok) {
    throw new PxBoardRpcError(
      response.body?.error?.message || response.error || `HTTP ${response.status}`,
      response.body?.error?.code || '',
      response.status,
    )
  }
  if (!response.body || !('data' in response.body)) {
    throw new PxBoardRpcError('Invalid RPC response envelope', 'invalid_response', response.status)
  }
  return response.body.data as T
}

export async function pxboardBatch(
  calls: PxBoardBatchCall[],
  authData = useAppStore.getState().authData,
): Promise<Record<string, PxBoardBatchItem>> {
  const response = await httpRequest<RpcEnvelope<Record<string, PxBoardBatchItem>>>({
    method: 'POST',
    url: `${pxboardBaseUrl()}/rpc/v1/$batch`,
    headers: buildHeaders(authData),
    body: {
      calls: calls.map((call) => ({
        key: call.key,
        service: call.service,
        method: call.method,
        input: call.input ?? {},
      })),
    },
  })
  if (!response.ok || !response.body?.data) {
    throw new PxBoardRpcError(
      response.body?.error?.message || response.error || `HTTP ${response.status}`,
      response.body?.error?.code || '',
      response.status,
    )
  }
  return response.body.data
}

export async function openPxBoardPortal(redirectPath = '/user'): Promise<void> {
  const result = await pxboardRpc<{ url: string }>('core', 'auth.browserLink', { redirectPath })
  await openExternal(pxboardPublicUrl(result.url))
}

interface ClientProfileAccount {
  exists?: boolean
  status?: string
  effective?: boolean
  unlimited?: boolean
  usedGiB?: number
  quotaGiB?: number
  usedBytes?: number
  quotaBytes?: number
  expiresAt?: string
  expiredAtUnix?: number
}

interface ClientProfileResponse {
  revision: string
  unchanged?: boolean
  blockReason?: string
  account?: ClientProfileAccount
  nodes?: RawNode[]
  routing?: {
    hasRules?: boolean
    ruleCount?: number
    proxyGroupCount?: number
    ruleProviderCount?: number
    rules?: string[]
    rulesPreview?: string[]
    routeConfigYaml?: string | null
  }
  links?: Array<{ shareUrl?: string }>
}

let cachedRevisionAuth = ''
let cachedRevision = ''

export function resetPxBoardProfileRevision(): void {
  cachedRevisionAuth = ''
  cachedRevision = ''
}

function normalizeBlockReason(raw?: string): SubscriptionState['blockReason'] {
  if (raw === 'no_plan' || raw === 'expired' || raw === 'traffic_exceeded' || raw === 'suspended') {
    return raw
  }
  return raw ? 'suspended' : ''
}

export async function syncPxBoard(): Promise<string | null> {
  const current = useAppStore.getState()
  const auth = current.authData
  if (!auth) return null
  const baseUrl = pxboardBaseUrl()
  const useKnownRevision =
    cachedRevisionAuth === auth &&
    Boolean(cachedRevision) &&
    (current.nodes.length > 0 || Boolean(current.subscription.blockReason))
  const batch = await pxboardBatch(
    [
      { key: 'me', service: 'core', method: 'account.me' },
      { key: 'plugins', service: 'core', method: 'plugins.list' },
      {
        key: 'profile',
        service: 'subscriptions',
        method: 'client.profile',
        input: useKnownRevision ? { knownRevision: cachedRevision } : {},
      },
      { key: 'wallet', service: 'commerce', method: 'wallet.get' },
      { key: 'announcements', service: 'support', method: 'announcements.list' },
      { key: 'affiliate', service: 'affiliate', method: 'summary.get' },
    ],
    auth,
  )
  if (batch.me?.status !== 200 || !batch.me.data) {
    throw new PxBoardRpcError(
      batch.me?.error?.message || '无法读取账户信息',
      batch.me?.error?.code || '',
      batch.me?.status || 500,
    )
  }
  if (useAppStore.getState().authData !== auth) return null

  const user = batch.me.data as { id: string; email: string; name?: string }
  const plugins = Array.isArray(batch.plugins?.data)
    ? (batch.plugins.data as Array<{ id: string; enabled: boolean }>)
    : []
  const enabledExtensions = plugins.filter((p) => p.enabled).map((p) => p.id)

  const wallet = batch.wallet?.status === 200 ? (batch.wallet.data as { balance?: number; currencyName?: string; currencySymbol?: string }) : undefined
  const affiliate = batch.affiliate?.status === 200 ? (batch.affiliate.data as { availableAmount?: number; commissionPercent?: number }) : undefined
  current.setProfile({
    panelExtensions: enabledExtensions,
    paymentEnabled: enabledExtensions.includes('commerce'),
    ...(wallet
      ? {
          balance: Number(wallet.balance ?? 0),
          currencySymbol: String(wallet.currencySymbol ?? ''),
          currencyUnit: String(wallet.currencyName ?? ''),
        }
      : {}),
    ...(affiliate
      ? {
          commissionBalance: Number(affiliate.availableAmount ?? 0),
          inviteCommissionBalance: Number(affiliate.availableAmount ?? 0),
          inviteCommissionRate: Number(affiliate.commissionPercent ?? 0),
        }
      : {}),
  })

  if (batch.announcements?.status === 200 && Array.isArray(batch.announcements.data)) {
    const notices: NoticeItem[] = (batch.announcements.data as Array<Record<string, any>>)
      .filter((row) => row.published !== false)
      .map((row, idx) => ({
        id: idx + 1,
        title: String(row.title ?? ''),
        content: String(row.content ?? ''),
        createdAt: row.createdAt ? Math.floor(Date.parse(String(row.createdAt)) / 1000) || 0 : 0,
      }))
    current.setNotices(notices)
  }

  let syncWarning: string | null = null
  let nodes = current.nodes
  let routing: RouteRoutingState = current.routing
  let subscribeUrl = current.subscribeUrl
  let subscription: SubscriptionState = current.subscription

  if (batch.profile?.status === 200 && batch.profile.data) {
    const profile = batch.profile.data as ClientProfileResponse
    const acc = profile.account ?? {}
    const used = Number(acc.usedBytes ?? Math.round(Number(acc.usedGiB ?? 0) * 1073741824))
    const total = Number(acc.quotaBytes ?? Math.round(Number(acc.quotaGiB ?? 0) * 1073741824))
    const expiredAt = Number(
      acc.expiredAtUnix ?? (acc.expiresAt ? Math.floor(Date.parse(acc.expiresAt) / 1000) || 0 : 0),
    )
    const blockReason = normalizeBlockReason(profile.blockReason)
    const summaryParts: string[] = []
    if (acc.exists !== false) {
      summaryParts.push(`${formatTrafficBytes(used)} / ${acc.unlimited ? '∞' : formatTrafficBytes(total)}`)
      if (expiredAt > 0) summaryParts.push(formatUnixDate(expiredAt))
    }
    subscription = {
      summary: summaryParts.join(' · '),
      blockReason,
      trafficUsedBytes: used,
      trafficTotalBytes: total,
      planName: '',
      expiredAt,
    }
    const firstShare = Array.isArray(profile.links) ? profile.links.find((l) => l.shareUrl)?.shareUrl : ''
    subscribeUrl = firstShare ? pxboardPublicUrl(firstShare) : ''

    if (!profile.unchanged) {
      if (blockReason) {
        nodes = []
        routing = {
          hasRules: false,
          ruleCount: 0,
          proxyGroupCount: 0,
          ruleProviderCount: 0,
          rulesPreview: [],
          rules: [],
          routeConfigYaml: null,
        }
      } else {
        const rawNodes = Array.isArray(profile.nodes) ? profile.nodes : []
        const parsedNodes: ReturnType<typeof toAppNode>[] = []
        for (const raw of rawNodes) {
          try {
            parsedNodes.push(toAppNode(raw))
          } catch (err) {
            syncWarning = err instanceof Error ? err.message : String(err)
          }
        }
        nodes = parsedNodes
        const r = profile.routing ?? {}
        const rules = Array.isArray(r.rules) ? r.rules.map(String) : []
        const preview = Array.isArray(r.rulesPreview) ? r.rulesPreview.map(String) : rules.slice(0, 20)
        const yamlText = typeof r.routeConfigYaml === 'string' && r.routeConfigYaml.trim() ? r.routeConfigYaml : null
        routing = {
          hasRules: Boolean(r.hasRules ?? rules.length > 0),
          ruleCount: Number(r.ruleCount ?? rules.length),
          proxyGroupCount: Number(r.proxyGroupCount ?? 0),
          ruleProviderCount: Number(r.ruleProviderCount ?? 0),
          rulesPreview: preview,
          rules,
          routeConfigYaml: yamlText,
        }
      }
      cachedRevisionAuth = auth
      cachedRevision = String(profile.revision ?? '')
    }
  } else if (batch.profile && batch.profile.status !== 404) {
    syncWarning = batch.profile.error?.message || `订阅同步异常（HTTP ${batch.profile.status}）`
  }

  current.setSession({ baseUrl, authData: auth, email: user.email })
  current.setSubscribe({ subscribeUrl, nodes })
  current.setRouting(routing)
  current.setSubscriptionState(subscription)
  await saveSubscriptionCache({ authData: auth, subscribeUrl, nodes, subscription, routing })
  return syncWarning
}
