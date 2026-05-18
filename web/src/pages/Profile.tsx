import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { xboardRequest } from '../api/xboard'
import { useAppStore, type InviteItem, type NoticeItem } from '../store'
import { clearSession } from '../store/persist'
import { formatMoney, formatUnixDate, numericValue } from '../format'

interface UserInfoBody {
  data?: {
    email?: string
    balance?: number
    commission_balance?: number
    invite_force?: number | boolean
    commission_rate?: number
    expired_at?: number
    plan_id?: number
    plan?: { name?: string }
  }
}

interface UserConfigBody {
  data?: {
    currency_symbol?: string
    currency?: string
    currency_unit?: string
    invite_force?: boolean | number
    commission_rate?: number
    invite_commission_balance?: number
  }
}

interface InviteFetchBody {
  data?: { codes?: Array<{ code?: string; status?: number }>; codes_list?: Array<{ code?: string; status?: number }> }
}

interface NoticeFetchBody {
  data?: Array<{
    id?: number
    title?: string
    subject?: string
    content?: string
    message?: string
    created_at?: number
  }>
}

function parseInvites(body: InviteFetchBody | undefined): InviteItem[] {
  const data = body?.data
  if (!data) return []
  const list = data.codes ?? data.codes_list ?? []
  return list.map((row) => ({ code: row.code ?? '', status: Number(row.status ?? 0) }))
}

function parseNotices(body: NoticeFetchBody | undefined): NoticeItem[] {
  const data = body?.data ?? []
  return data
    .map((row) => ({
      id: Number(row.id ?? 0),
      title: row.title ?? row.subject ?? '',
      content: row.content ?? row.message ?? '',
      createdAt: Number(row.created_at ?? 0),
    }))
    .filter((item) => item.title.trim() || item.content.trim())
}

export function Profile() {
  const navigate = useNavigate()
  const {
    baseUrl,
    authData,
    email,
    vpn,
    balance,
    commissionBalance,
    currencySymbol,
    currencyUnit,
    inviteForce,
    inviteCommissionRate,
    inviteCommissionBalance,
    invites,
    notices,
    subscription,
    setProfile,
    setInvites,
    setNotices,
    reset,
  } = useAppStore()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [copied, setCopied] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    async function load() {
      if (!authData) return
      setLoading(true)
      setError('')
      try {
        const [info, config, inviteList] = await Promise.all([
          xboardRequest<UserInfoBody>('user_info', { baseUrl, authData }),
          xboardRequest<UserConfigBody>('user_config', { baseUrl, authData }),
          xboardRequest<InviteFetchBody>('invite_fetch', { baseUrl, authData }),
        ])
        if (cancelled) return
        if (info.ok) {
          const data = info.body?.data ?? {}
          setProfile({
            balance: Math.round(numericValue(data.balance)),
            commissionBalance: Math.round(numericValue(data.commission_balance)),
          })
        }
        if (config.ok) {
          const data = config.body?.data ?? {}
          setProfile({
            currencySymbol: data.currency_symbol ?? data.currency ?? '',
            currencyUnit: data.currency_unit ?? '',
            paymentEnabled: true,
            inviteForce: Boolean(data.invite_force),
            inviteCommissionRate: Math.round(numericValue(data.commission_rate)),
            inviteCommissionBalance: Math.round(numericValue(data.invite_commission_balance)),
          })
        }
        if (inviteList.ok) setInvites(parseInvites(inviteList.body))
        const noticeResponse = await xboardRequest<NoticeFetchBody>('notices', { baseUrl, authData })
        if (cancelled) return
        if (noticeResponse.ok) setNotices(parseNotices(noticeResponse.body))
      } catch (err) {
        if (!cancelled) setError(err instanceof Error ? err.message : String(err))
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [authData, baseUrl, setProfile, setInvites, setNotices])

  async function generateInvite() {
    try {
      const created = await xboardRequest('invite_save', { baseUrl, authData })
      if (!created.ok) {
        setError(created.error ?? `HTTP ${created.status}`)
        return
      }
      const inviteList = await xboardRequest<InviteFetchBody>('invite_fetch', { baseUrl, authData })
      if (inviteList.ok) setInvites(parseInvites(inviteList.body))
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  async function copyCode(code: string) {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(code)
      window.setTimeout(() => setCopied((current) => (current === code ? null : current)), 1500)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  async function logout() {
    try {
      await clearSession()
    } catch (err) {
      console.error('clear session failed', err)
    }
    reset()
    navigate('/login')
  }

  return (
    <main className="mx-auto max-w-3xl space-y-4 p-6">
      <header>
        <h1 className="text-xl font-semibold">个人中心</h1>
        <p className="text-xs text-slate-400 break-all">{email || '未登录'}</p>
      </header>
      <section className="space-y-2 rounded-2xl bg-slate-900/60 p-5 ring-1 ring-white/10">
        <p className="text-sm text-slate-400">账户余额</p>
        <p className="text-2xl font-semibold">{formatMoney(balance, currencySymbol, currencyUnit)}</p>
        <p className="text-xs text-slate-500">佣金余额：{formatMoney(commissionBalance, currencySymbol, currencyUnit)}</p>
        {subscription.summary && (
          <p className="mt-3 text-sm text-slate-300">{subscription.summary}</p>
        )}
        {subscription.expiredAt > 0 && (
          <p className="text-xs text-slate-500">到期：{formatUnixDate(subscription.expiredAt)}</p>
        )}
        {vpn && (
          <p className="text-xs text-emerald-300">当前连接：socks5://{vpn.socksAddr}</p>
        )}
      </section>
      {(inviteForce || inviteCommissionRate > 0) && (
        <section className="space-y-3 rounded-2xl bg-slate-900/60 p-5 ring-1 ring-white/10">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-medium">邀请</h2>
            <button
              onClick={() => void generateInvite()}
              className="rounded-lg bg-sky-500 px-3 py-1 text-xs hover:bg-sky-400"
            >
              生成邀请码
            </button>
          </div>
          <p className="text-xs text-slate-400">分佣比例 {inviteCommissionRate}% · 佣金 {formatMoney(inviteCommissionBalance, currencySymbol, currencyUnit)}</p>
          {invites.length === 0 ? (
            <p className="text-xs text-slate-500">{loading ? '加载中…' : '暂无邀请码，点击右上角生成。'}</p>
          ) : (
            <ul className="space-y-2">
              {invites.map((invite) => (
                <li
                  key={invite.code}
                  className="flex items-center justify-between rounded-lg bg-slate-800/60 px-3 py-2 text-sm"
                >
                  <div className="min-w-0">
                    <p className="truncate font-mono">{invite.code}</p>
                    <p className="text-xs text-slate-500">{invite.status === 0 ? '未使用' : '已使用'}</p>
                  </div>
                  <button
                    onClick={() => void copyCode(invite.code)}
                    className="rounded-lg bg-slate-700 px-3 py-1 text-xs hover:bg-slate-600"
                  >
                    {copied === invite.code ? '已复制' : '复制'}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
      {notices.length > 0 && (
        <section className="space-y-3 rounded-2xl bg-slate-900/60 p-5 ring-1 ring-white/10">
          <h2 className="text-sm font-medium">公告</h2>
          <ul className="space-y-3">
            {notices.map((notice) => (
              <li key={notice.id} className="space-y-1 border-l-2 border-sky-500/40 pl-3">
                <p className="text-sm font-medium">{notice.title}</p>
                <p className="whitespace-pre-wrap text-xs text-slate-400">{notice.content.replace(/<[^>]+>/g, '')}</p>
                {notice.createdAt > 0 && (
                  <p className="text-[10px] text-slate-500">{formatUnixDate(notice.createdAt)}</p>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}
      <button
        onClick={() => void logout()}
        className="w-full rounded-lg bg-slate-800 py-3 text-sm hover:bg-slate-700"
      >
        退出登录
      </button>
      {error && <p className="text-sm text-rose-400">{error}</p>}
    </main>
  )
}
