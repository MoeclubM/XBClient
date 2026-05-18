import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { xboardRequest } from '../api/xboard'
import { useAppStore } from '../store'
import { saveSession } from '../store/persist'

type AuthMode = 'login' | 'register'

export function Login() {
  const navigate = useNavigate()
  const setSession = useAppStore((s) => s.setSession)
  const [mode, setMode] = useState<AuthMode>('login')
  const [baseUrl, setBaseUrl] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [inviteCode, setInviteCode] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError('')
    setMessage('')
    setLoading(true)
    try {
      const params: Record<string, string> = { email, password }
      if (mode === 'register' && inviteCode.trim()) params.invite_code = inviteCode.trim()
      const response = await xboardRequest<{ data?: { auth_data?: string; token?: string }; message?: string }>(
        mode,
        { baseUrl, params },
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
    } catch (error) {
      setError(error instanceof Error ? error.message : String(error))
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="flex min-h-full items-center justify-center p-6">
      <form
        onSubmit={submit}
        className="w-full max-w-sm space-y-4 rounded-2xl bg-slate-900/60 p-6 shadow-xl ring-1 ring-white/10"
      >
        <div className="flex items-center justify-between gap-3">
          <h1 className="text-xl font-semibold">XBClient {mode === 'login' ? '登录' : '注册'}</h1>
          <button
            type="button"
            onClick={() => {
              setMode(mode === 'login' ? 'register' : 'login')
              setError('')
              setMessage('')
            }}
            className="rounded-lg bg-slate-800 px-3 py-1 text-sm text-sky-300 hover:bg-slate-700"
          >
            {mode === 'login' ? '注册账号' : '返回登录'}
          </button>
        </div>
        <label className="block">
          <span className="mb-1 block text-sm text-slate-300">站点地址</span>
          <input
            className="w-full rounded-lg bg-slate-800 px-3 py-2 outline-none ring-1 ring-white/10 focus:ring-sky-500"
            placeholder="https://example.com"
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            required
          />
        </label>
        <label className="block">
          <span className="mb-1 block text-sm text-slate-300">邮箱</span>
          <input
            className="w-full rounded-lg bg-slate-800 px-3 py-2 outline-none ring-1 ring-white/10 focus:ring-sky-500"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </label>
        <label className="block">
          <span className="mb-1 block text-sm text-slate-300">密码</span>
          <input
            className="w-full rounded-lg bg-slate-800 px-3 py-2 outline-none ring-1 ring-white/10 focus:ring-sky-500"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        {mode === 'register' && (
          <label className="block">
            <span className="mb-1 block text-sm text-slate-300">邀请码</span>
            <input
              className="w-full rounded-lg bg-slate-800 px-3 py-2 outline-none ring-1 ring-white/10 focus:ring-sky-500"
              value={inviteCode}
              onChange={(e) => setInviteCode(e.target.value)}
            />
          </label>
        )}
        {message && <p className="text-sm text-emerald-300">{message}</p>}
        {error && <p className="text-sm text-rose-400">{error}</p>}
        <button
          type="submit"
          disabled={loading}
          className="w-full rounded-lg bg-sky-500 px-3 py-2 font-medium text-white hover:bg-sky-400 disabled:opacity-50"
        >
          {loading ? '处理中…' : mode === 'login' ? '登录' : '注册'}
        </button>
      </form>
    </main>
  )
}
