import {
  pxboardBaseUrl,
  pxboardBatch,
  pxboardPublicUrl,
  pxboardRpc,
  resetPxBoardProfileRevision,
} from '../pxboard'
import { openExternal } from '../system'
import { useAppStore, type OAuthProvider } from '../../store'
import { saveSession } from '../../store/persist'

export interface PxBoardAuthField {
  key: string
  label: string
  type?: string
  required?: boolean
  default?: unknown
  hint?: string
}

export interface PxBoardRegistrationProvider {
  key: string
  title: string
  path: string
  procedure?: string
  fields: PxBoardAuthField[]
}

export interface PxBoardChallengePolicy {
  enabled: boolean
  provider?: string
  siteKey?: string
  login?: boolean
  register?: boolean
  reset?: boolean
}

export interface PxBoardAuthMeta {
  name: string
  extensions: string[]
  registration: PxBoardRegistrationProvider[]
  passwordReset: boolean
  challenge: PxBoardChallengePolicy
  providers: OAuthProvider[]
}

export async function loadPxBoardAuthMeta(): Promise<PxBoardAuthMeta> {
  const batch = await pxboardBatch(
    [
      { key: 'setup', service: 'core', method: 'setup.get' },
      { key: 'challenge', service: 'core', method: 'auth.challenge' },
    ],
    '',
  )
  if (batch.setup?.status !== 200 || !batch.setup.data) {
    throw new Error(batch.setup?.error?.message || '无法读取面板配置')
  }
  const setup = batch.setup.data as {
    name?: string
    extensions?: string[]
    registration?: PxBoardRegistrationProvider[]
    passwordReset?: boolean
  }
  const extensions = Array.isArray(setup.extensions) ? setup.extensions : []
  const challenge: PxBoardChallengePolicy =
    batch.challenge?.status === 200 && batch.challenge.data
      ? (batch.challenge.data as PxBoardChallengePolicy)
      : { enabled: false }
  let providers: OAuthProvider[] = []
  if (extensions.includes('oauth')) {
    const rows = await pxboardRpc<Array<{ id: string; name: string }>>('oauth', 'providers.list', {}, '')
    providers = (rows || []).map((row) => ({ driver: row.id, label: row.name }))
  }
  return {
    name: setup.name || useAppStore.getState().buildConfig?.app_name || 'PxBoard',
    extensions,
    registration: Array.isArray(setup.registration) ? setup.registration : [],
    passwordReset: Boolean(setup.passwordReset),
    challenge,
    providers,
  }
}

async function finalizePxBoardSession(token: string, email: string): Promise<void> {
  const config = useAppStore.getState().buildConfig!
  const baseUrl = pxboardBaseUrl()
  const authData = 'PxBoard ' + token
  resetPxBoardProfileRevision()
  useAppStore.getState().setSession({ baseUrl, authData, email })
  await saveSession({ authData, email, baseUrl, panelType: config.panel_type })
}

export async function loginPxBoardPassword(params: {
  email: string
  password: string
  code?: string
}): Promise<void> {
  const payload: Record<string, unknown> = {
    email: params.email.trim(),
    password: params.password,
  }
  if (params.code?.trim()) payload.code = params.code.trim()
  const result = await pxboardRpc<{ token: string; email: string }>('core', 'auth.login', payload, '')
  if (!result.token) throw new Error('登录响应缺少会话令牌')
  await finalizePxBoardSession(result.token, result.email || params.email.trim())
}

export async function registerPxBoardAccount(
  provider: PxBoardRegistrationProvider,
  values: Record<string, unknown>,
): Promise<string> {
  const target = provider.path || `${provider.key}/${provider.procedure || 'register'}`
  const slash = target.indexOf('/')
  const service = slash > 0 ? target.slice(0, slash) : 'registration'
  const method = slash > 0 ? target.slice(slash + 1).replaceAll('/', '.') : 'register'
  const result = await pxboardRpc<{ message?: string }>(service, method, values, '')
  return result?.message || '注册成功，请登录'
}

export async function requestPxBoardPasswordReset(email: string): Promise<void> {
  await pxboardRpc('core', 'auth.resetRequest', { email: email.trim() }, '')
}

export async function confirmPxBoardPasswordReset(token: string, password: string): Promise<void> {
  await pxboardRpc('core', 'auth.resetPassword', { token: token.trim(), password }, '')
}

export async function loginPxBoard(signal: AbortSignal, providerId?: string): Promise<void> {
  const config = useAppStore.getState().buildConfig!
  const proof = Array.from(crypto.getRandomValues(new Uint8Array(32)), (value) => value.toString(16).padStart(2, '0')).join('')
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(proof))
  const challenge = Array.from(new Uint8Array(hash), (value) => value.toString(16).padStart(2, '0')).join('')
  const flow = await pxboardRpc<{ id: string; authorizationUrl: string; expiresAt: string; intervalSeconds: number }>(
    'core',
    'auth.authorizeCreate',
    { name: config.app_name, challenge },
    '',
  )
  signal.throwIfAborted()
  const authorization = new URL(pxboardPublicUrl(flow.authorizationUrl))
  if (providerId) authorization.searchParams.set('provider', providerId)
  await openExternal(authorization.toString())
  while (Date.now() < Date.parse(flow.expiresAt)) {
    await new Promise<void>((resolve, reject) => {
      signal.throwIfAborted()
      const abort = () => {
        clearTimeout(timer)
        reject(signal.reason)
      }
      const timer = setTimeout(() => {
        signal.removeEventListener('abort', abort)
        resolve()
      }, Math.max(1, flow.intervalSeconds) * 1000)
      signal.addEventListener('abort', abort, { once: true })
    })
    const result = await pxboardRpc<{ status: string; token?: string; user?: { email: string } }>(
      'core',
      'auth.authorizeExchange',
      { id: flow.id, proof },
      '',
    )
    signal.throwIfAborted()
    if (result.status === 'pending') continue
    if (result.status !== 'approved' || !result.token || !result.user?.email) {
      throw new Error('无效的授权结果')
    }
    await finalizePxBoardSession(result.token, result.user.email)
    return
  }
  throw new Error('登录授权已超时，请重试')
}

