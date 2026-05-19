import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { xboardRequest } from '../api/xboard'
import { useAppStore } from '../store'
import { saveSession, saveSettings } from '../store/persist'
import { useTranslation } from '../i18n'

type AuthMode = 'login' | 'register'

export function Login() {
  const navigate = useNavigate()
  const t = useTranslation()
  const setSession = useAppStore((s) => s.setSession)
  const settings = useAppStore((s) => s.settings)
  const setSettings = useAppStore((s) => s.setSettings)

  const [mode, setMode] = useState<AuthMode>('login')
  const [baseUrl, setBaseUrl] = useState('')
  const [apiUserAgent, setApiUserAgent] = useState(settings.apiUserAgent)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [inviteCode, setInviteCode] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function saveApiUserAgent() {
    const value = apiUserAgent.trim()
    if (value === settings.apiUserAgent) return
    const next = { ...settings, apiUserAgent: value }
    setSettings({ apiUserAgent: value })
    await saveSettings(next).catch((err) => console.error('Save settings failed', err))
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError('')
    setMessage('')
    setLoading(true)
    try {
      await saveApiUserAgent()
      const params: Record<string, string> = { email, password }
      if (mode === 'register' && inviteCode.trim()) params.invite_code = inviteCode.trim()
      const response = await xboardRequest<{ data?: { auth_data?: string; token?: string }; message?: string }>(
        mode,
        { baseUrl, params, userAgent: apiUserAgent.trim() },
      )
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
      setSession({ baseUrl, authData, email })
      await saveSession({ baseUrl, authData, email })
      navigate('/home')
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="flex min-h-full items-center justify-center bg-background-app p-6 pb-20 text-on-background transition-all-200">
        <form
          onSubmit={submit}
          className="w-full max-w-sm space-y-5 rounded-2xl bg-surface-low p-6 shadow-xl border border-outline-variant/40 animate-content-size"
        >
          <div className="flex items-center justify-between gap-3 border-b border-outline-variant/30 pb-4">
            <div className="flex items-center gap-3">
              <img className="h-10 w-10 shrink-0 filter drop-shadow-[0_4px_10px_rgba(11,87,208,0.25)]" src="./logo.svg" alt="XBClient" />
              <div className="min-w-0">
                <h1 className="text-lg font-bold tracking-tight text-primary">
                  SecOVPN {mode === 'login' ? t('login') : t('register')}
                </h1>
                <p className="text-[10px] text-on-surface-variant font-medium">Xboard Client</p>
              </div>
            </div>
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

          <div className="space-y-4">
            <label className="block">
              <span className="mb-1 block text-xs font-semibold text-on-surface-variant">{t('site_url')}</span>
              <input
                className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50 focus:border-primary focus:ring-1 focus:ring-primary transition-all duration-150"
                placeholder={t('site_placeholder')}
                value={baseUrl}
                onChange={(e) => setBaseUrl(e.target.value)}
                required
              />
            </label>

            <label className="block">
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
              <label className="block animate-slide-down">
                <span className="mb-1 block text-xs font-semibold text-on-surface-variant">{t('invite_code')}</span>
                <input
                  className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50 focus:border-primary focus:ring-1 focus:ring-primary transition-all duration-150"
                  placeholder="Code"
                  value={inviteCode}
                  onChange={(e) => setInviteCode(e.target.value)}
                />
              </label>
            )}
          </div>

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
