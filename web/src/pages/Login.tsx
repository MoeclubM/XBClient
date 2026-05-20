import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { openInAppBrowser, takeOAuthCallback } from '../api/system'
import { normalizeBaseUrl, xboardRequest } from '../api/xboard'
import { useAppStore, type AppSettings, type OAuthProvider } from '../store'
import { saveSession, saveSettings } from '../store/persist'
import { enabled } from '../reward'
import { useTranslation } from '../i18n'

type AuthMode = 'login' | 'register'

interface AuthBody {
  data?: {
    auth_data?: string
    token?: string
    subscribe_url?: string
    email?: string
  }
  message?: string
}

interface ConfirmOAuthBody {
  data?: string
  message?: string
}

interface GuestConfigBody {
  data?: {
    oauth_providers?: Array<{ driver?: string; label?: string }>
    is_invite_force?: number | boolean | string
    is_email_verify?: number | boolean | string
    is_captcha?: number | boolean | string
  }
  message?: string
}

interface OAuthConfirmState {
  token: string
  provider: string
  email: string
}

function parseOAuthProviders(value: unknown): OAuthProvider[] {
  if (!Array.isArray(value)) return []
  return value
    .map((item) => ({
      driver: String((item as { driver?: unknown }).driver ?? ''),
      label: String((item as { label?: unknown }).label ?? (item as { driver?: unknown }).driver ?? ''),
    }))
    .filter((item) => item.driver.trim())
}

function verifyFromCallback(value: string): string {
  const text = value.trim()
  const matched = /[?&](?:verify|token)=([^&]+)/.exec(text)
  if (matched) return decodeURIComponent(matched[1])
  return text
}

export function Login() {
  const navigate = useNavigate()
  const t = useTranslation()
  const setSession = useAppStore((s) => s.setSession)
  const settings = useAppStore((s) => s.settings)
  const buildConfig = useAppStore((s) => s.buildConfig)
  const capabilities = useAppStore((s) => s.capabilities)
  const setSettings = useAppStore((s) => s.setSettings)
  const oauthProviders = useAppStore((s) => s.oauthProviders)
  const inviteForce = useAppStore((s) => s.inviteForce)
  const registerEmailVerifyEnabled = useAppStore((s) => s.registerEmailVerifyEnabled)
  const registerCaptchaEnabled = useAppStore((s) => s.registerCaptchaEnabled)
  const setAuthConfig = useAppStore((s) => s.setAuthConfig)
  const baseUrl = buildConfig?.default_api_url ?? ''
  const appName = buildConfig?.app_name ?? ''
  const oauthCallbackSupported = capabilities?.platform === 'android'

  const [mode, setMode] = useState<AuthMode>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [inviteCode, setInviteCode] = useState('')
  const [emailCode, setEmailCode] = useState('')
  const [captcha, setCaptcha] = useState('')
  const [oauthConfirm, setOauthConfirm] = useState<OAuthConfirmState | null>(null)
  const [configLoaded, setConfigLoaded] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [configLoading, setConfigLoading] = useState(false)
  const [verifySending, setVerifySending] = useState(false)
  const [tokenLoading, setTokenLoading] = useState(false)

  useEffect(() => {
    if (baseUrl) void loadGuestConfig(false)
  }, [baseUrl])

  useEffect(() => {
    if (capabilities?.platform !== 'android') return
    let active = true

    async function checkCallback() {
      const callbackUrl = await takeOAuthCallback()
      if (active && callbackUrl) await handleOAuthCallback(callbackUrl)
    }

    void checkCallback().catch((err) => setError(err instanceof Error ? err.message : String(err)))
    const onFocus = () => void checkCallback().catch((err) => setError(err instanceof Error ? err.message : String(err)))
    const onVisible = () => {
      if (!document.hidden) onFocus()
    }
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      active = false
      window.removeEventListener('focus', onFocus)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [capabilities?.platform, baseUrl])

  async function persistSettings(patch: Partial<AppSettings>) {
    const next = { ...settings, ...patch }
    setSettings(patch)
    await saveSettings(next).catch((err) => console.error('Save settings failed', err))
  }

  async function loadGuestConfig(showSuccess: boolean) {
    setError('')
    if (!baseUrl) {
      setError('构建配置缺少默认服务地址。')
      return
    }
    setConfigLoading(true)
    try {
      const response = await xboardRequest<GuestConfigBody>('guest_config', { baseUrl })
      if (!response.ok) {
        setError(response.body?.message ?? response.error ?? `HTTP ${response.status}`)
        return
      }
      const data = response.body?.data ?? {}
      setAuthConfig({
        oauthProviders: parseOAuthProviders(data.oauth_providers),
        inviteForce: enabled(data.is_invite_force),
        registerEmailVerifyEnabled: enabled(data.is_email_verify),
        registerCaptchaEnabled: enabled(data.is_captcha),
      })
      setConfigLoaded(true)
      if (showSuccess) setMessage('服务配置已加载。')
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setConfigLoading(false)
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError('')
    setMessage('')
    setLoading(true)
    try {
      const params: Record<string, string> = { email: email.trim(), password }
      if (mode === 'register') {
        if (inviteCode.trim()) params.invite_code = inviteCode.trim()
        if (emailCode.trim()) params.email_code = emailCode.trim()
        const token = captcha.trim()
        if (token) {
          params.recaptcha_data = token
          params.recaptcha_v3_token = token
          params.cf_turnstile_response = token
        }
      }
      const response = await xboardRequest<AuthBody>(mode, { baseUrl, params })
      if (!response.ok) {
        setError(response.body?.message ?? response.error ?? `HTTP ${response.status}`)
        return
      }
      const authData = response.body?.data?.auth_data
      if (!authData) {
        if (mode === 'register') {
          setMode('login')
          setMessage(response.body?.message ?? '注册完成，请登录。')
          return
        }
        setError('登录响应缺少 auth_data')
        return
      }
      await finishLogin(authData, email.trim())
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  async function sendEmailVerify() {
    setError('')
    setMessage('')
    setVerifySending(true)
    try {
      const token = captcha.trim()
      const params: Record<string, string> = { email: email.trim() }
      if (token) {
        params.recaptcha_data = token
        params.recaptcha_v3_token = token
        params.cf_turnstile_response = token
      }
      const response = await xboardRequest<{ message?: string }>('send_email_verify', { baseUrl, params })
      if (!response.ok) {
        setError(response.body?.message ?? response.error ?? `HTTP ${response.status}`)
        return
      }
      setMessage(response.body?.message ?? t('email_verify_sent'))
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setVerifySending(false)
    }
  }

  async function openOAuth(provider: OAuthProvider, scene: AuthMode) {
    setError('')
    setMessage('')
    try {
      if (!buildConfig) throw new Error('构建配置尚未加载。')
      const url = new URL(
        `/api/v1/passport/auth/oauth/${encodeURIComponent(provider.driver)}/redirect`,
        `${normalizeBaseUrl(baseUrl)}/`,
      )
      url.searchParams.set('scene', scene)
      url.searchParams.set('redirect', 'dashboard')
      url.searchParams.set('client', 'app')
      url.searchParams.set('app_scheme', buildConfig.oauth_callback_scheme)
      if (scene === 'register' && inviteCode.trim()) url.searchParams.set('invite_code', inviteCode.trim())
      await openInAppBrowser(url.toString(), `${provider.label || provider.driver} OAuth`)
      setMessage('已打开 OAuth 页面，等待应用链接自动回调。')
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  async function handleOAuthCallback(callbackUrl: string) {
    setError('')
    setMessage('')
    const uri = new URL(callbackUrl)
    const oauthError = uri.searchParams.get('oauth_error') ?? ''
    if (oauthError) {
      setError(`OAuth 失败：${oauthError}`)
      return
    }
    const success = uri.searchParams.get('oauth_success') ?? ''
    if (success) {
      setMessage(success)
      return
    }
    const confirmToken = uri.searchParams.get('oauth_confirm_token') ?? ''
    if (confirmToken) {
      setMode('register')
      setOauthConfirm({
        token: confirmToken,
        provider: uri.searchParams.get('oauth_provider') ?? '',
        email: uri.searchParams.get('oauth_email') ?? '',
      })
      setMessage('请确认 OAuth 注册。')
      return
    }
    const verify = uri.searchParams.get('verify') || uri.searchParams.get('token') || ''
    if (verify) await loginWithVerify(verify)
  }

  async function confirmOAuthRegister() {
    if (!oauthConfirm) return
    setError('')
    setMessage('')
    setTokenLoading(true)
    try {
      const response = await xboardRequest<ConfirmOAuthBody>('confirm_oauth_register', {
        baseUrl,
        params: { token: oauthConfirm.token },
      })
      if (!response.ok) {
        setError(response.body?.message ?? response.error ?? `HTTP ${response.status}`)
        return
      }
      const verify = verifyFromCallback(response.body?.data ?? '')
      if (!verify) {
        setError('OAuth 注册确认响应缺少 verify。')
        return
      }
      setOauthConfirm(null)
      await loginWithVerify(verify)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setTokenLoading(false)
    }
  }

  async function loginWithVerify(verify: string) {
    setTokenLoading(true)
    try {
      const response = await xboardRequest<AuthBody>('token_login', {
        baseUrl,
        params: { verify: verifyFromCallback(verify) },
      })
      if (!response.ok) {
        setError(response.body?.message ?? response.error ?? `HTTP ${response.status}`)
        return
      }
      const authData = response.body?.data?.auth_data
      if (!authData) {
        setError('OAuth 登录响应缺少 auth_data')
        return
      }
      await finishLogin(authData, response.body?.data?.email ?? email.trim())
    } finally {
      setTokenLoading(false)
    }
  }

  async function finishLogin(authData: string, accountEmail: string) {
    const session = { baseUrl, authData, email: accountEmail }
    setSession(session)
    await saveSession({ authData, email: accountEmail })
    navigate('/home')
  }

  return (
    <main className="flex min-h-full items-center justify-center bg-background-app p-6 pb-20 text-on-background transition-all-200">
      <form
        onSubmit={submit}
        className="w-full max-w-2xl space-y-5 rounded-2xl bg-surface-low p-6 shadow-xl border border-outline-variant/40"
      >
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-outline-variant/30 pb-4">
          <div className="flex items-center gap-3">
            <img className="h-10 w-10 shrink-0 filter drop-shadow-[0_4px_10px_rgba(11,87,208,0.25)]" src="./logo.png" alt={appName || 'Logo'} />
            <div className="min-w-0">
              <h1 className="text-lg font-bold tracking-tight text-primary">
                {appName} {mode === 'login' ? t('login') : t('register')}
              </h1>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <select
              value={settings.appLanguage}
              onChange={(e) => void persistSettings({ appLanguage: e.target.value as AppSettings['appLanguage'] })}
              className="rounded-lg bg-surface px-2 py-1.5 text-[11px] font-semibold outline-none border border-outline-variant/50"
            >
              <option value="system">🌐 System</option>
              <option value="zh-CN">中文</option>
              <option value="en">English</option>
              <option value="ja">日本語</option>
              <option value="ru">Русский</option>
              <option value="fa">فارسی</option>
            </select>
            <select
              value={settings.themeMode}
              onChange={(e) => void persistSettings({ themeMode: e.target.value as AppSettings['themeMode'] })}
              className="rounded-lg bg-surface px-2 py-1.5 text-[11px] font-semibold outline-none border border-outline-variant/50"
            >
              <option value="system">🎨 {t('theme_system')}</option>
              <option value="light">☀️ {t('theme_light')}</option>
              <option value="dark">🌙 {t('theme_dark')}</option>
            </select>
            <button
              type="button"
              onClick={() => {
                setMode(mode === 'login' ? 'register' : 'login')
                setError('')
                setMessage('')
              }}
              className="rounded-lg bg-primary/10 px-3 py-1.5 text-xs font-semibold text-primary hover:bg-primary/20 active:scale-95 transition-all duration-150"
            >
              {mode === 'login' ? t('register_account') : t('back_to_login')}
            </button>
          </div>
        </div>


        <div className="grid gap-4 md:grid-cols-2">
          <label className="block">
            <span className="mb-1 block text-xs font-semibold text-on-surface-variant">{t('email')}</span>
            <input
              className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50 focus:border-primary focus:ring-1 focus:ring-primary transition-all duration-150"
              type="email"
              placeholder="name@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </label>

          <label className="block">
            <span className="mb-1 block text-xs font-semibold text-on-surface-variant">{t('password')}</span>
            <input
              className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50 focus:border-primary focus:ring-1 focus:ring-primary transition-all duration-150"
              type="password"
              placeholder="••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </label>

          {mode === 'register' && (
            <>
              <label className="block">
                <span className="mb-1 block text-xs font-semibold text-on-surface-variant">
                  {t('invite_code')}{inviteForce ? ' *' : ''}
                </span>
                <input
                  className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50 focus:border-primary focus:ring-1 focus:ring-primary transition-all duration-150"
                  placeholder="Code"
                  value={inviteCode}
                  onChange={(e) => setInviteCode(e.target.value)}
                  required={inviteForce}
                />
              </label>

              {registerCaptchaEnabled && (
                <label className="block">
                  <span className="mb-1 block text-xs font-semibold text-on-surface-variant">{t('captcha_token')}</span>
                  <input
                    className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50 focus:border-primary focus:ring-1 focus:ring-primary transition-all duration-150"
                    value={captcha}
                    onChange={(e) => setCaptcha(e.target.value)}
                  />
                </label>
              )}

              {registerEmailVerifyEnabled && (
                <label className="block md:col-span-2">
                  <span className="mb-1 block text-xs font-semibold text-on-surface-variant">{t('email_code')}</span>
                  <div className="flex gap-2">
                    <input
                      className="min-w-0 flex-1 rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50 focus:border-primary focus:ring-1 focus:ring-primary transition-all duration-150"
                      value={emailCode}
                      onChange={(e) => setEmailCode(e.target.value)}
                      required
                    />
                    <button
                      type="button"
                      onClick={() => void sendEmailVerify()}
                      disabled={verifySending}
                      className="shrink-0 rounded-xl bg-primary/10 px-3 py-2 text-xs font-bold text-primary hover:bg-primary/20 disabled:opacity-50 border border-primary/20"
                    >
                      {verifySending ? t('refreshing') : t('send_email_verify')}
                    </button>
                  </div>
                </label>
              )}
            </>
          )}
        </div>

        {(oauthCallbackSupported || oauthConfirm) && (
          <section className="space-y-3 rounded-2xl bg-surface p-4 border border-outline-variant/30">
            <div className="flex items-center justify-between gap-3">
              <h2 className="text-xs font-extrabold uppercase tracking-wider text-on-surface-variant">{t('auth_options')}</h2>
              {!configLoaded && (
                <button
                  type="button"
                  onClick={() => void loadGuestConfig(true)}
                  disabled={configLoading}
                  className="text-[10px] font-semibold text-primary disabled:opacity-50"
                >
                  {configLoading ? t('refreshing') : '重新同步'}
                </button>
              )}
            </div>

            {oauthCallbackSupported && oauthProviders.length > 0 ? (
              <div className="grid gap-2 sm:grid-cols-2">
                {oauthProviders.map((provider) => (
                  <button
                    key={provider.driver}
                    type="button"
                    onClick={() => void openOAuth(provider, mode)}
                    className="rounded-xl bg-primary/10 px-3 py-2 text-xs font-bold text-primary hover:bg-primary/20 active:scale-95 border border-primary/20"
                  >
                    {mode === 'login' ? t('oauth_login') : t('oauth_register')} · {provider.label || provider.driver}
                  </button>
                ))}
              </div>
            ) : oauthCallbackSupported ? (
              <p className="text-xs text-on-surface-variant">服务配置同步后会显示可用 OAuth 登录方式。</p>
            ) : (
              <p className="text-xs text-on-surface-variant">当前平台未接入应用链接回调，OAuth 入口已关闭。</p>
            )}

            {oauthConfirm && (
              <div className="rounded-2xl bg-primary/10 p-3 text-xs text-primary border border-primary/20 space-y-3">
                <p className="font-semibold">
                  确认使用 {oauthConfirm.provider || 'OAuth'} 注册{oauthConfirm.email ? `：${oauthConfirm.email}` : ''}
                </p>
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => void confirmOAuthRegister()}
                    disabled={tokenLoading}
                    className="rounded-xl bg-primary px-3 py-2 text-xs font-bold text-white disabled:opacity-50"
                  >
                    {tokenLoading ? t('action_connecting') : '确认注册'}
                  </button>
                  <button
                    type="button"
                    onClick={() => setOauthConfirm(null)}
                    className="rounded-xl bg-surface px-3 py-2 text-xs font-bold text-on-surface-variant border border-outline-variant/30"
                  >
                    取消
                  </button>
                </div>
              </div>
            )}
          </section>
        )}

        {message && (
          <p className="rounded-lg bg-emerald-500/10 p-2.5 text-xs font-medium text-emerald-500 border border-emerald-500/20">
            {message}
          </p>
        )}
        {error && (
          <p className="rounded-lg bg-rose-500/10 p-2.5 text-xs font-medium text-rose-500 border border-rose-500/20 break-words">
            {error}
          </p>
        )}

        <button
          type="submit"
          disabled={loading}
          className="w-full rounded-xl bg-primary px-4 py-3 font-semibold text-white shadow-md shadow-primary/20 hover:bg-primary/95 hover:shadow-primary/30 active:scale-95 disabled:opacity-50 transition-all duration-150 cursor-pointer"
        >
          {loading ? t('action_connecting') : mode === 'login' ? t('login') : t('register')}
        </button>
      </form>
    </main>
  )
}
