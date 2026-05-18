import { invoke } from '@tauri-apps/api/core'

export interface XboardResponse<T = unknown> {
  ok: boolean
  status: number
  body: T
  error?: string
}

interface ActionDef {
  method: 'GET' | 'POST'
  path: string
  auth: boolean
}

const ACTIONS: Record<string, ActionDef> = {
  guest_config: { method: 'GET', path: '/api/v1/guest/comm/config', auth: false },
  login: { method: 'POST', path: '/api/v1/passport/auth/login', auth: false },
  register: { method: 'POST', path: '/api/v1/passport/auth/register', auth: false },
  user_info: { method: 'GET', path: '/api/v1/user/info', auth: true },
  user_subscribe: { method: 'GET', path: '/api/v1/user/getSubscribe', auth: true },
  user_config: { method: 'GET', path: '/api/v1/user/comm/config', auth: true },
  user_stat: { method: 'GET', path: '/api/v1/user/getStat', auth: true },
  plan_fetch: { method: 'GET', path: '/api/v1/user/plan/fetch', auth: true },
  order_save: { method: 'POST', path: '/api/v1/user/order/save', auth: true },
  order_checkout: { method: 'POST', path: '/api/v1/user/order/checkout', auth: true },
  nodes: { method: 'GET', path: '/api/v1/user/server/fetch', auth: true },
  xbclient_plan_payment: { method: 'POST', path: '/api/v1/admob/user/plan-payment', auth: true },
  xbclient_nodes: { method: 'GET', path: '/api/v1/admob/user/nodes', auth: true },
  notices: { method: 'GET', path: '/api/v1/user/notice/fetch', auth: true },
  invite_fetch: { method: 'GET', path: '/api/v1/user/invite/fetch', auth: true },
  invite_save: { method: 'GET', path: '/api/v1/user/invite/save', auth: true },
  invite_details: { method: 'GET', path: '/api/v1/user/invite/details', auth: true },
  passport_quick_login_url: { method: 'POST', path: '/api/v1/passport/auth/getQuickLoginUrl', auth: false },
  quick_login_url: { method: 'POST', path: '/api/v1/user/getQuickLoginUrl', auth: true },
}

export interface XboardOptions {
  baseUrl: string
  authData?: string
  params?: Record<string, unknown>
  userAgent?: string
}

export async function xboardRequest<T = unknown>(
  action: keyof typeof ACTIONS,
  options: XboardOptions,
): Promise<XboardResponse<T>> {
  const def = ACTIONS[action]
  if (!def) throw new Error(`unknown action: ${action}`)
  const url = normalizeBaseUrl(options.baseUrl) + def.path
  const headers: Record<string, string> = {
    'User-Agent': options.userAgent ?? 'SecOneApp',
  }
  if (def.auth) {
    if (!options.authData) throw new Error(`action ${action} requires auth`)
    headers.Authorization = options.authData
  }
  return invoke<XboardResponse<T>>('xboard_request', {
    request: {
      method: def.method,
      url,
      headers,
      body: def.method === 'GET' ? undefined : options.params ?? {},
    },
  })
}

export interface SubscriptionResult {
  ok: boolean
  status: number
  format?: string
  flag?: string
  subscription_userinfo?: string | null
  nodes?: unknown[]
  error?: string
  body?: string
}

export async function subscriptionFetch(
  url: string,
  flag: 'meta' | 'sing-box' = 'meta',
): Promise<SubscriptionResult> {
  return invoke<SubscriptionResult>('subscription_fetch', { url, flag })
}

export interface TestNodeResult {
  ok: boolean
  latency_ms?: number
  first_latency_ms?: number
  target_host?: string
  target_port?: number
  target_tls?: boolean
  error?: string
}

export interface TestNodeRequest {
  node: unknown
  target_host?: string
  target_port?: number
  target_tls?: boolean
  timeout_ms?: number
}

export async function aerionTestNode(request: TestNodeRequest): Promise<TestNodeResult> {
  return invoke<TestNodeResult>('aerion_test_node', { request })
}

export interface SocksHandle {
  ok: boolean
  session_id: number
  socks_addr: string
}

export async function aerionStartSocks(node: unknown): Promise<SocksHandle> {
  return invoke<SocksHandle>('aerion_start_socks', { node })
}

export async function aerionStop(sessionId: number): Promise<{ ok: boolean; session_id: number }> {
  return invoke('aerion_stop', { sessionId })
}

function normalizeBaseUrl(value: string): string {
  const v = value.trim().replace(/\/+$/, '')
  if (/^https?:\/\//i.test(v)) return v
  return `https://${v}`
}
