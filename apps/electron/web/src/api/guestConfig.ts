import { enabled } from '../reward'
import { useAppStore } from '../store'
import { parseOAuthProviders } from './helpers'
import { xboardRequest } from './xboard'

interface GuestConfigBody {
  data?: {
    oauth_providers?: unknown
    is_invite_force?: number | boolean | string
    is_email_verify?: number | boolean | string
    is_captcha?: number | boolean | string
    captcha_type?: unknown
  }
  message?: string
}

export async function syncGuestAuthConfig(baseUrl: string): Promise<void> {
  const response = await xboardRequest<GuestConfigBody>('guest_config', { baseUrl })
  if (!response.ok) {
    if (response.body?.message) throw new Error(response.body.message)
    if (response.error) throw new Error(response.error)
    throw new Error('guest config failed response missing message or error')
  }
  if (!response.body?.data) throw new Error('guest config response missing data')

  const data = response.body.data
  useAppStore.getState().setAuthConfig({
    oauthProviders: parseOAuthProviders(data.oauth_providers),
    inviteForce: enabled(data.is_invite_force),
    registerEmailVerifyEnabled: enabled(data.is_email_verify),
    registerCaptchaEnabled: enabled(data.is_captcha),
    registerCaptchaType: typeof data.captcha_type === 'string' ? data.captcha_type : (() => { throw new Error('guest config captcha_type is required') })(),
  })
}
