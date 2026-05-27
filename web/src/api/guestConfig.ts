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
  }
  message?: string
}

export async function syncGuestAuthConfig(baseUrl?: string): Promise<void> {
  const url = baseUrl ?? useAppStore.getState().buildConfig?.default_api_url ?? useAppStore.getState().baseUrl
  if (!url) throw new Error('API base URL is not configured')

  const response = await xboardRequest<GuestConfigBody>('guest_config', { baseUrl: url })
  if (!response.ok) {
    throw new Error(response.body?.message ?? response.error ?? `HTTP ${response.status}`)
  }

  const data = response.body?.data ?? {}
  useAppStore.getState().setAuthConfig({
    oauthProviders: parseOAuthProviders(data.oauth_providers),
    inviteForce: enabled(data.is_invite_force),
    registerEmailVerifyEnabled: enabled(data.is_email_verify),
    registerCaptchaEnabled: enabled(data.is_captcha),
  })
}
