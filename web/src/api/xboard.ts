import { useAppStore } from '../store'
import { publicErrorText } from '../format'
import { invoke } from '../platform/electron'

export interface XboardResponse<T = unknown> {
  ok: boolean
  status: number
  body: T
  error?: string
}

interface ActionDef {
  method: 'GET' | 'POST'
  path: string | ((params: Record<string, unknown>) => string)
  auth: boolean
  query?: string[]
  requiredQuery?: string[]
}

const ACTIONS = {
  guest_config: { method: 'GET', path: '/api/v1/guest/comm/config', auth: false },
  send_email_verify: { method: 'POST', path: '/api/v1/passport/comm/sendEmailVerify', auth: false },
  login: { method: 'POST', path: '/api/v1/passport/auth/login', auth: false },
  register: { method: 'POST', path: '/api/v1/passport/auth/register', auth: false },
  forget_password: { method: 'POST', path: '/api/v1/passport/auth/forget', auth: false },
  token_login: { method: 'GET', path: '/api/v1/passport/auth/token2Login', auth: false, requiredQuery: ['verify'] },
  confirm_oauth_register: { method: 'POST', path: '/api/v1/passport/auth/oauth/confirm-register', auth: false },
  passport_quick_login_url: { method: 'POST', path: '/api/v1/passport/auth/getQuickLoginUrl', auth: false },
  login_with_mail_link: { method: 'POST', path: '/api/v1/passport/auth/loginWithMailLink', auth: false },
  user_info: { method: 'GET', path: '/api/v1/user/info', auth: true },
  user_subscribe: { method: 'GET', path: '/api/v1/user/getSubscribe', auth: true },
  check_login: { method: 'GET', path: '/api/v1/user/checkLogin', auth: true },
  user_stat: { method: 'GET', path: '/api/v1/user/getStat', auth: true },
  user_update: { method: 'POST', path: '/api/v1/user/update', auth: true },
  change_password: { method: 'POST', path: '/api/v1/user/changePassword', auth: true },
  reset_security: { method: 'GET', path: '/api/v1/user/resetSecurity', auth: true },
  transfer: { method: 'POST', path: '/api/v1/user/transfer', auth: true },
  quick_login_url: { method: 'POST', path: '/api/v1/user/getQuickLoginUrl', auth: true },
  user_config: { method: 'GET', path: '/api/v1/user/comm/config', auth: true },
  plan_fetch: { method: 'GET', path: '/api/v1/user/plan/fetch', auth: true },
  order_save: { method: 'POST', path: '/api/v1/user/order/save', auth: true },
  order_checkout: { method: 'POST', path: '/api/v1/user/order/checkout', auth: true },
  oauth_bindings: { method: 'GET', path: '/api/v1/user/oauth/bindings', auth: true },
  oauth_bind_prepare: { method: 'POST', path: (params) => `/api/v1/user/oauth/${pathParam(params, 'driver')}/bind`, auth: true },
  oauth_unbind: { method: 'POST', path: (params) => `/api/v1/user/oauth/${pathParam(params, 'driver')}/unbind`, auth: true },
  active_sessions: { method: 'GET', path: '/api/v1/user/getActiveSession', auth: true },
  remove_active_session: { method: 'POST', path: '/api/v1/user/removeActiveSession', auth: true },
  gift_card_check: { method: 'POST', path: '/api/v1/user/gift-card/check', auth: true },
  gift_card_redeem: { method: 'POST', path: '/api/v1/user/gift-card/redeem', auth: true },
  gift_card_history: { method: 'GET', path: '/api/v1/user/gift-card/history', auth: true, query: ['page', 'per_page'] },
  gift_card_detail: { method: 'GET', path: '/api/v1/user/gift-card/detail', auth: true, requiredQuery: ['id'] },
  gift_card_types: { method: 'GET', path: '/api/v1/user/gift-card/types', auth: true },
  invite_fetch: { method: 'GET', path: '/api/v1/user/invite/fetch', auth: true },
  invite_save: { method: 'GET', path: '/api/v1/user/invite/save', auth: true },
  invite_details: { method: 'GET', path: '/api/v1/user/invite/details', auth: true, query: ['current', 'page_size'] },
  tickets: { method: 'GET', path: '/api/v1/user/ticket/fetch', auth: true, query: ['id'] },
  ticket_save: { method: 'POST', path: '/api/v1/user/ticket/save', auth: true },
  ticket_reply: { method: 'POST', path: '/api/v1/user/ticket/reply', auth: true },
  ticket_close: { method: 'POST', path: '/api/v1/user/ticket/close', auth: true },
  ticket_withdraw: { method: 'POST', path: '/api/v1/user/ticket/withdraw', auth: true },
  notices: { method: 'GET', path: '/api/v1/user/notice/fetch', auth: true },
  coupon_check: { method: 'POST', path: '/api/v1/user/coupon/check', auth: true },
  traffic_logs: { method: 'GET', path: '/api/v1/user/stat/getTrafficLog', auth: true },
  telegram_bot: { method: 'GET', path: '/api/v1/user/telegram/getBotInfo', auth: true },
  knowledge: { method: 'GET', path: '/api/v1/user/knowledge/fetch', auth: true, query: ['id', 'language', 'keyword'] },
  stripe_public_key: { method: 'POST', path: '/api/v1/user/comm/getStripePublicKey', auth: true },
  admob_reward_config: { method: 'GET', path: '/api/v1/admob/user/config', auth: true },
  xbclient_plan_payment: { method: 'POST', path: '/api/v1/admob/user/plan-payment', auth: true },
  xbclient_reward_history: { method: 'GET', path: '/api/v1/admob/user/reward-history', auth: true },
  xbclient_reward_pending: { method: 'POST', path: '/api/v1/admob/user/reward-pending', auth: true },
  xbclient_nodes: { method: 'GET', path: '/api/v1/admob/user/nodes', auth: true },
} satisfies Record<string, ActionDef>

export interface XboardOptions {
  baseUrl: string
  authData?: string
  params?: Record<string, unknown>
}

export async function xboardRequest<T = unknown>(
  action: keyof typeof ACTIONS | 'anytls_nodes',
  options: XboardOptions,
): Promise<XboardResponse<T>> {
  if (action === 'anytls_nodes') {
    const params = options.params ?? {}
    const result = await subscriptionFetch(
      paramValue(params, 'subscribe_url', true),
      subscriptionFlag(paramValue(params, 'flag', true)),
    )
    return {
      ok: result.ok,
      status: result.status,
      body: result as T,
      error: result.error,
    }
  }
  const def = ACTIONS[action]
  const params = options.params ?? {}
  const url = withQuery(normalizeBaseUrl(options.baseUrl) + pathValue(def, params), def, params)
  const headers: Record<string, string> = {}
  const userAgent = useAppStore.getState().buildConfig?.user_agent.trim()
  if (!userAgent) throw new Error('XBCLIENT_USER_AGENT is required in build config')
  headers['User-Agent'] = userAgent
  if (def.auth) {
    if (!options.authData) throw new Error(`action ${action} requires auth`)
    headers.Authorization = options.authData
  }
  try {
    return await invoke<XboardResponse<T>>('xboard_request', {
      request: {
        method: def.method,
        url,
        headers,
        body: def.method === 'GET' ? undefined : params,
      },
    })
  } catch (error) {
    throw new Error(publicErrorText(error, 'Request failed'))
  }
}

export interface SubscriptionRouting {
  has_rules: boolean
  rule_count: number
  proxy_group_count: number
  rule_provider_count: number
  rules_preview: string[]
  route_config_yaml: string | null
}

export interface SubscriptionResult {
  ok: boolean
  status: number
  format?: string
  flag?: string
  subscription_userinfo?: string | null
  routing?: SubscriptionRouting
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
  target_host: string
  target_port: number
  target_tls: boolean
  timeout_ms: number
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

export interface RouteStartRequest {
  config_yaml: string
  geoip_dir?: string
  global_proxy?: string
  selected_proxy?: string
  selected_node?: unknown
}

export async function aerionStartRoute(request: RouteStartRequest): Promise<SocksHandle & { rule_count?: number; outbound_tags?: string[] }> {
  return invoke('aerion_start_route', request)
}

export async function aerionStopRoute(sessionId: number): Promise<{ ok: boolean; session_id: number }> {
  return invoke('aerion_stop_route', { sessionId })
}

export async function aerionStop(sessionId: number): Promise<{ ok: boolean; session_id: number }> {
  return invoke('aerion_stop', { sessionId })
}

export interface VpnHandle {
  ok: boolean
  session_id: number
  mtu?: number
  dns?: string
  dns_addr?: string
  virtual_dns_pool?: string
}

export interface VpnStartRequest {
  node: unknown
  mtu: number
  dns: string
  dns_addr: string
  virtual_dns_pool: string
  ipv6: boolean
}

export async function aerionStartVpn(request: VpnStartRequest): Promise<VpnHandle> {
  return invoke<VpnHandle>('aerion_start_vpn', request)
}

export async function aerionStopVpn(sessionId: number): Promise<{ ok: boolean; session_id: number }> {
  return invoke('aerion_stop_vpn', { sessionId })
}

export function normalizeBaseUrl(value: string): string {
  const v = value.trim().replace(/\/+$/, '')
  if (/^https?:\/\//i.test(v)) return v
  return `https://${v}`
}

function pathValue(def: ActionDef, params: Record<string, unknown>): string {
  return typeof def.path === 'function' ? def.path(params) : def.path
}

function pathParam(params: Record<string, unknown>, key: string): string {
  return encodeURIComponent(paramValue(params, key, true))
}

function withQuery(url: string, def: ActionDef, params: Record<string, unknown>): string {
  const query = new URLSearchParams()
  for (const key of def.requiredQuery ?? []) query.set(key, paramValue(params, key, true))
  for (const key of def.query ?? []) {
    const value = paramValue(params, key, false)
    if (value) query.set(key, value)
  }
  const text = query.toString()
  return text ? `${url}?${text}` : url
}

function paramValue(params: Record<string, unknown>, key: string, required: boolean): string {
  const value = params[key]
  const text = value === undefined || value === null ? '' : String(value)
  if (required && !text) throw new Error(`${key} is required`)
  return text
}

function subscriptionFlag(value: string): 'meta' | 'sing-box' {
  if (value === 'meta' || value === 'sing-box') return value
  throw new Error(`unsupported subscription flag: ${value}`)
}
