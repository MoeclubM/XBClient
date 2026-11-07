import { pxboardRequest } from '../pxboard'
import { openExternal } from '../system'
import { useAppStore, type OAuthProvider } from '../../store'
import { saveSession } from '../../store/persist'

export async function loadPxBoardProviders(): Promise<OAuthProvider[]> {
  const setup = await pxboardRequest<{ extensions: string[] }>('/setup', undefined, '')
  if (!setup.extensions.includes('oauth')) return []
  const providers = await pxboardRequest<Array<{ id: string; name: string }>>('/extensions/oauth/providers', undefined, '')
  return providers.map(provider => ({ driver: provider.id, label: provider.name }))
}

export async function loginPxBoard(signal: AbortSignal, providerId?: string): Promise<void> {
  const config = useAppStore.getState().buildConfig!
  const proof = Array.from(crypto.getRandomValues(new Uint8Array(32)), value => value.toString(16).padStart(2, '0')).join('')
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(proof))
  const challenge = Array.from(new Uint8Array(hash), value => value.toString(16).padStart(2, '0')).join('')
  const flow = await pxboardRequest<{ id: string; authorizationUrl: string; expiresAt: string; intervalSeconds: number }>('/auth/native', { name: config.app_name, challenge }, '')
  signal.throwIfAborted()
  const authorization = new URL(flow.authorizationUrl)
  if (providerId) authorization.searchParams.set('provider', providerId)
  await openExternal(authorization.toString())
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
    if (result.status !== 'approved' || !result.token) throw new Error('Invalid PxBoard authorization response')
    const authData = 'PxBoard ' + result.token
    useAppStore.getState().setSession({ baseUrl: config.default_api_url, authData, email: result.user.email })
    await saveSession({ authData, email: result.user.email, baseUrl: config.default_api_url, panelType: config.panel_type })
    return
  }
  throw new Error('Authorization expired. Please sign in again.')
}

