import { useAppStore, type OAuthProvider } from '../../store'
import { normalizeBaseUrl, xboardRequest, type XboardBody } from '../xboard'
import { failureText } from '../helpers'
import { openExternal } from '../system'

export async function openXboardOAuth(provider: OAuthProvider, scene: 'login' | 'register', inviteCode: string): Promise<void> {
  const config = useAppStore.getState().buildConfig!
  const url = new URL('/api/v1/passport/auth/oauth/' + encodeURIComponent(provider.driver) + '/redirect', normalizeBaseUrl(config.default_api_url))
  url.searchParams.set('scene', scene)
  url.searchParams.set('redirect', 'dashboard')
  url.searchParams.set('client', 'app')
  url.searchParams.set('app_scheme', config.oauth_callback_scheme)
  if (scene === 'register' && inviteCode.trim()) url.searchParams.set('invite_code', inviteCode.trim())
  await openExternal(url.toString())
}

type XboardCallback =
  | { type: 'message'; message: string }
  | { type: 'confirm'; confirm: { token: string; provider: string; email: string } }
  | { type: 'login'; verify: string }

export function parseXboardCallback(value: string): XboardCallback {
  const uri = new URL(value)
  const config = useAppStore.getState().buildConfig!
  if (uri.protocol !== config.oauth_callback_scheme + ':' || uri.hostname !== 'oauth') throw new Error('Invalid XBoard OAuth callback')
  const error = uri.searchParams.get('oauth_error')
  if (error) throw new Error(error)
  const message = uri.searchParams.get('oauth_success')
  if (message) return { type: 'message', message }
  const token = uri.searchParams.get('oauth_confirm_token')
  if (token) {
    const provider = uri.searchParams.get('oauth_provider')
    const email = uri.searchParams.get('oauth_email')
    if (!provider || !email) throw new Error('OAuth confirm callback missing provider or email')
    return { type: 'confirm', confirm: { token, provider, email } }
  }
  const verify = uri.searchParams.get('verify')
  if (!verify) throw new Error('OAuth callback missing verify')
  return { type: 'login', verify }
}

export async function loginXboardWithVerify(verify: string): Promise<{ authData: string; email: string }> {
  const baseUrl = useAppStore.getState().buildConfig!.default_api_url
  const response = await xboardRequest<XboardBody<{ auth_data: string }>>('token_login', { baseUrl, params: { verify } })
  if (!response.ok) throw new Error(failureText(response))
  const authData = response.body.data?.auth_data
  if (!authData) throw new Error('XBoard OAuth login response missing auth_data')
  const user = await xboardRequest<XboardBody<{ email: string }>>('user_info', { baseUrl, authData })
  if (!user.ok) throw new Error(failureText(user))
  if (!user.body.data?.email) throw new Error('XBoard user response missing email')
  return { authData, email: user.body.data.email }
}

export async function confirmXboardOAuth(token: string): Promise<{ authData: string; email: string }> {
  const response = await xboardRequest<XboardBody<string>>('confirm_oauth_register', {
    baseUrl: useAppStore.getState().buildConfig!.default_api_url, params: { token },
  })
  if (!response.ok) throw new Error(failureText(response))
  if (!response.body.data) throw new Error('OAuth confirm response missing data')
  const matched = /[?&]verify=([^&]+)/.exec(response.body.data)
  const verify = matched ? decodeURIComponent(matched[1]) : ''
  if (!verify) throw new Error('OAuth confirm response missing verify')
  return loginXboardWithVerify(verify)
}
