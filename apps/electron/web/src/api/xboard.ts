import { useAppStore } from '../store'
import { httpRequest, type HttpResponse } from './http'

export type XboardBody<T = unknown> = {
  data?: T
  message?: string
  status?: string
  total?: number
}

interface ActionDef {
  method: 'GET' | 'POST'
  path: string
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
  user_info: { method: 'GET', path: '/api/v1/user/info', auth: true },
  user_subscribe: { method: 'GET', path: '/api/v1/user/getSubscribe', auth: true },
  transfer: { method: 'POST', path: '/api/v1/user/transfer', auth: true },
  user_config: { method: 'GET', path: '/api/v1/user/comm/config', auth: true },
  plan_fetch: { method: 'GET', path: '/api/v1/user/plan/fetch', auth: true },
  order_save: { method: 'POST', path: '/api/v1/user/order/save', auth: true },
  order_checkout: { method: 'POST', path: '/api/v1/user/order/checkout', auth: true },
  invite_fetch: { method: 'GET', path: '/api/v1/user/invite/fetch', auth: true },
  invite_save: { method: 'GET', path: '/api/v1/user/invite/save', auth: true },
  invite_details: { method: 'GET', path: '/api/v1/user/invite/details', auth: true, query: ['current', 'page_size'] },
  tickets: { method: 'GET', path: '/api/v1/user/ticket/fetch', auth: true, query: ['id'] },
  ticket_save: { method: 'POST', path: '/api/v1/user/ticket/save', auth: true },
  ticket_reply: { method: 'POST', path: '/api/v1/user/ticket/reply', auth: true },
  ticket_close: { method: 'POST', path: '/api/v1/user/ticket/close', auth: true },
  notices: { method: 'GET', path: '/api/v1/user/notice/fetch', auth: true },
  traffic_logs: { method: 'GET', path: '/api/v1/user/stat/getTrafficLog', auth: true },
  admob_reward_config: { method: 'GET', path: '/api/v1/admob/user/config', auth: true },
  xbclient_plan_payment: { method: 'POST', path: '/api/v1/admob/user/plan-payment', auth: true },
  xbclient_nodes: { method: 'GET', path: '/api/v1/admob/user/nodes', auth: true },
} satisfies Record<string, ActionDef>

export interface XboardOptions {
  baseUrl: string
  authData?: string
  params?: Record<string, unknown>
}

export async function xboardRequest<T = unknown>(
  action: keyof typeof ACTIONS,
  options: XboardOptions,
): Promise<HttpResponse<T>> {
  const def = ACTIONS[action]
  const params = options.params ?? {}
  const url = withQuery(normalizeBaseUrl(options.baseUrl) + def.path, def, params)
  const headers: Record<string, string> = {}
  const userAgent = useAppStore.getState().buildConfig?.user_agent.trim()
  if (!userAgent) throw new Error('XBCLIENT_USER_AGENT is required in build config')
  headers['User-Agent'] = userAgent
  if (action === 'xbclient_nodes') headers['X-XBClient-Protocols'] = 'sudoku'
  if (def.auth) {
    if (!options.authData) throw new Error(`action ${action} requires auth`)
    headers.Authorization = options.authData
  }
  return httpRequest<T>({ method: def.method, url, headers, body: def.method === 'GET' ? undefined : params })
}


export function normalizeBaseUrl(value: string): string {
  const v = value.trim().replace(/\/+$/, '')
  if (/^https?:\/\//i.test(v)) return v
  return `https://${v}`
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
