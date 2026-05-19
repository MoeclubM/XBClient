import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { openExternal } from '../api/system'
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

interface GuestConfigBody {
  data?: {
    oauth_providers?: Array<{ driver?: string; label?: string }>
    is_invite_force?: number | boolean | string
    is_email_verify?: number | boolean | string
    is_captcha?: number | boolean | string
  }
  message?: string
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

function verifyToken(value: string): string {
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
  const setSettings = useAppStore((s) => s.setSettings)
  const oauthProviders = useAppStore((s) => s.oauthProviders)
  const inviteForce = useAppStore((s) => s.inviteForce)
  const registerEmailVerifyEnabled = useAppStore((s) => s.registerEmailVerifyEnabled)
  const registerCaptchaEnabled = useAppStore((s) => s.registerCaptchaEnabled)
  const setAuthConfig = useAppStore((s) => s.setAuthConfig)

  const [mode, setMode] = useState<AuthMode>('login')
  const [baseUrl, setBaseUrl] = useState('')
  const [apiUserAgent, setApiUserAgent] = useState(settings.apiUserAgent)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [inviteCode, setInviteCode] = useState('')
  const [emailCode, setEmailCode] = useState('')
  const [captcha, setCaptcha] = useState('')
  const [oauthVerify, setOauthVerify] = useState('')
  const [configLoaded, setConfigLoaded] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [configLoading, setConfigLoading] = useState(false)
  const [verifySending, setVerifySending] = useState(false)
  const [tokenLoading, setTokenLoading] = useState(false)

  async function persistSettings(patch: Partial<AppSettings>) {
    const next = { ...settings, ...patch }
    setSettings(patch)
    await saveSettings(next).catch((err) => console.error('Save settings failed', err))
  }

  async function saveApiUserAgent() {
    const value = apiUserAgent.trim()
    if (value === settings.apiUserAgent) return
    await persistSettings({ apiUserAgent: value })
  }

  async function loadGuestConfig(showSuccess: boolean) {
    setError('')
    if (!baseUrl.trim()) {
      setError('请先填写站点地址。')
      return
    }
    setConfigLoading(true)
    try {
      await saveApiUserAgent()
      const response = await xboardRequest<GuestConfigBody>('guest_config', {
        baseUrl,
        userAgent: apiUserAgent.trim(),
      })
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
      if (showSuccess) setMessage(t('site_config_loaded'))
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
      await saveApiUserAgent()
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
      const response = await xboardRequest<AuthBody>(mode, {
        baseUrl,
        params,
        userAgent: apiUserAgent.trim(),
      })
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
      const session = { baseUrl: baseUrl.trim(), authData, email: email.trim() }
      setSession(session)
      await saveSession(session)
      navigate('/home')
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
      await saveApiUserAgent()
      const token = captcha.trim()
      const params: Record<string, string> = { email: email.trim() }
      if (token) {
        params.recaptcha_data = token
        params.recaptcha_v3_token = token
        params.cf_turnstile_response = token
      }
      const response = await xboardRequest<{ message?: string }>('send_email_verify', {
        baseUrl,
        params,
        userAgent: apiUserAgent.trim(),
      })
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
      await saveApiUserAgent()
      const url = new URL(
        `/api/v1/passport/auth/oauth/${encodeURIComponent(provider.driver)}/redirect`,
        `${normalizeBaseUrl(baseUrl)}/`,
      )
      url.searchParams.set('scene', scene)
      url.searchParams.set('redirect', 'dashboard')
      url.searchParams.set('client', 'app')
      url.searchParams.set('app_scheme', 'secone')
      if (scene === 'register' && inviteCode.trim()) url.searchParams.set('invite_code', inviteCode.trim())
      await openExternal(url.toString())
      setMessage(t('oauth_external_notice'))
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  async function loginWithVerify() {
    setError('')
    setMessage('')
    setTokenLoading(true)
    try {
      await saveApiUserAgent()
      const response = await xboardRequest<AuthBody>('token_login', {
        baseUrl,
        params: { verify: verifyToken(oauthVerify) },
        userAgent: apiUserAgent.trim(),
      })
      if (!response.ok) {
        setError(response.body?.message ?? response.error ?? `HTTP ${response.status}`)
        return
      }
      const authData = response.body?.data?.auth_data
      if (!authData) {
        setError('Token 登录响应缺少 auth_data')
        return
      }
      const session = { baseUrl: baseUrl.trim(), authData, email: response.body?.data?.email ?? email.trim() }
      setSession(session)
      await saveSession(session)
      navigate('/home')
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setTokenLoading(false)
    }
  }

  return (
    <main className="flex min-h-full items-center justify-center bg-background-app p-6 pb-20 text-on-background transition-all-200">
      <form
        onSubmit={submit}
        className="w-full max-w-2xl space-y-5 rounded-2xl bg-surface-low p-6 shadow-xl border border-outline-variant/40 animate-content-size"
      >
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-outline-variant/30 pb-4">
          <div className="flex items-center gap-3">
            <img className="h-10 w-10 shrink-0 filter drop-shadow-[0_4px_10px_rgba(11,87,208,0.25)]" src="./logo.svg" alt="XBClient" />
            <div className="min-w-0">
              <h1 className="text-lg font-bold tracking-tight text-primary">
                SecOVPN {mode === 'login' ? t('login') : t('register')}
              </h1>
              <p className="text-[10px] text-on-surface-variant font-medium">Xboard Client</p>
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
                const nextMode = mode === 'login' ? 'register' : 'login'
                setMode(nextMode)
                setError('')
                setMessage('')
                if (nextMode === 'register' && baseUrl.trim()) void loadGuestConfig(false)
              }}
              className="rounded-lg bg-primary/10 px-3 py-1.5 text-xs font-semibold text-primary hover:bg-primary/20 active:scale-95 transition-all duration-150"
            >
              {mode === 'login' ? t('register_account') : t('back_to_login')}
            </button>
          </div>
        </div>

        <div className="grid gap-4 md:grid-cols-2">
          <label className="block md:col-span-2">
            <span className="mb-1 block text-xs font-semibold text-on-surface-variant">{t('site_url')}</span>
            <div className="flex gap-2">
              <input
                className="min-w-0 flex-1 rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50 focus:border-primary focus:ring-1 focus:ring-primary transition-all duration-150"
                placeholder={t('site_placeholder')}
                value={baseUrl}
                onChange={(e) => {
                  setBaseUrl(e.target.value)
                  setConfigLoaded(false)
                }}
                onBlur={() => {
                  if (baseUrl.trim() && !configLoaded) void loadGuestConfig(false)
                }}
                required
              />
              <button
                type="button"
                onClick={() => void loadGuestConfig(true)}
                disabled={configLoading}
                className="shrink-0 rounded-xl bg-primary/10 px-3 py-2 text-xs font-bold text-primary hover:bg-primary/20 disabled:opacity-50 border border-primary/20"
              >
                {configLoading ? t('refreshing') : t('load_site_config')}
              </button>
            </div>
          </label>

          <label className="block md:col-span-2">
            <span className="mb-1 block text-xs font-semibold text-on-surface-variant">{t('api_user_agent')}</span>
            <input
              className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50 focus:border-primary focus:ring-1 focus:ring-primary transition-all duration-150"
              placeholder={t('api_user_agent_placeholder')}
              value={apiUserAgent}
              onChange={(e) => setApiUserAgent(e.target.value)}
              onBlur={() => void saveApiUserAgent()}
            />
            <span className="mt-1 block text-[10px] text-on-surface-variant">{t('api_user_agent_desc')}</span>
          </label>

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
              <label className="block animate-slide-down">
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
                <label className="block animate-slide-down">
                  <span className="mb-1 block text-xs font-semibold text-on-surface-variant">{t('captcha_token')}</span>
                  <input
                    className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50 focus:border-primary focus:ring-1 focus:ring-primary transition-all duration-150"
                    value={captcha}
                    onChange={(e) => setCaptcha(e.target.value)}
                  />
                </label>
              )}

              {registerEmailVerifyEnabled && (
                <label className="block animate-slide-down md:col-span-2">
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

        <section className="space-y-3 rounded-2xl bg-surface p-4 border border-outline-variant/30">
          <div className="flex items-center justify-between gap-3">
            <h2 className="text-xs font-extrabold uppercase tracking-wider text-on-surface-variant">{t('auth_options')}</h2>
            {!configLoaded && (
              <span className="text-[10px] font-semibold text-on-surface-variant">{t('load_site_config')}</span>
            )}
          </div>

          {oauthProviders.length > 0 ? (
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
          ) : (
            <p className="text-xs text-on-surface-variant">站点配置加载后会显示可用 OAuth 登录方式。</p>
          )}

          <div className="flex gap-2">
            <input
              className="min-w-0 flex-1 rounded-xl bg-surface-low px-3 py-2 text-sm outline-none border border-outline-variant/50 focus:border-primary focus:ring-1 focus:ring-primary transition-all duration-150"
              placeholder={t('oauth_verify')}
              value={oauthVerify}
              onChange={(e) => setOauthVerify(e.target.value)}
            />
            <button
              type="button"
              onClick={() => void loginWithVerify()}
              disabled={tokenLoading}
              className="shrink-0 rounded-xl bg-primary px-3 py-2 text-xs font-bold text-white shadow-sm hover:bg-primary/95 disabled:opacity-50"
            >
              {tokenLoading ? t('action_connecting') : t('token_login')}
            </button>
          </div>
        </section>

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
