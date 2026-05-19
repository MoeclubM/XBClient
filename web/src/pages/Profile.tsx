import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { xboardRequest } from '../api/xboard'
import { useAppStore, type InviteItem, type NoticeItem } from '../store'
import { clearSession } from '../store/persist'
import { formatMoney, formatUnixDate, numericValue } from '../format'
import { useTranslation } from '../i18n'

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
  const t = useTranslation()
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
    <main className="mx-auto max-w-3xl space-y-5 p-6 pb-24">
      <header className="border-b border-outline-variant/30 pb-3 flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-primary">{t('nav_profile')}</h1>
          <p className="mt-1 text-xs text-on-surface-variant font-medium break-all">{email || '未登录'}</p>
        </div>
        <button
          onClick={() => void logout()}
          className="rounded-xl bg-rose-500/10 px-4 py-2 text-xs font-bold text-rose-500 hover:bg-rose-500/20 active:scale-95 transition-all cursor-pointer border border-rose-500/20"
        >
          👋 {t('logout')}
        </button>
      </header>

      {error && (
        <p className="rounded-lg bg-rose-500/10 p-3 text-xs font-semibold text-rose-500 border border-rose-500/20">
          {error}
        </p>
      )}

      {/* Main Balance Sheet */}
      <section className="space-y-4 rounded-2xl bg-surface-low p-6 shadow-md border border-outline-variant/40 relative overflow-hidden">
        <div className="absolute right-0 top-0 translate-x-6 -translate-y-6 h-28 w-28 rounded-full bg-primary/5 filter blur-xl"></div>
        <div>
          <p className="text-xs font-bold text-on-surface-variant tracking-wider uppercase">{t('balance')}</p>
          <p className="text-3xl font-extrabold text-primary mt-1.5">{formatMoney(balance, currencySymbol, currencyUnit)}</p>
        </div>

        <div className="flex items-center gap-1.5 text-xs text-on-surface-variant font-semibold">
          <span>{t('commission_balance')}:</span>
          <span className="text-emerald-500 font-bold">{formatMoney(commissionBalance, currencySymbol, currencyUnit)}</span>
        </div>

        {subscription.summary && (
          <div className="mt-4 pt-4 border-t border-outline-variant/20 space-y-1">
            <p className="text-sm font-semibold text-on-background leading-relaxed">{subscription.summary}</p>
            {subscription.expiredAt > 0 && (
              <p className="text-xs text-on-surface-variant font-medium">
                📅 {t('expires_at')}: {formatUnixDate(subscription.expiredAt)}
              </p>
            )}
          </div>
        )}

        {vpn && (
          <div className="rounded-xl bg-emerald-500/10 border border-emerald-500/20 p-3 flex items-center justify-between text-xs text-emerald-500 font-bold">
            <span>🟢 SOCKS Status</span>
            <span className="font-mono">socks5://{vpn.socksAddr}</span>
          </div>
        )}
      </section>

      {/* Invites Management Section */}
      {(inviteForce || inviteCommissionRate > 0) && (
        <section className="space-y-4 rounded-2xl bg-surface-low p-5 shadow-sm border border-outline-variant/40">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-bold tracking-tight">{t('invites_title')}</h2>
            <button
              onClick={() => void generateInvite()}
              className="rounded-xl bg-primary px-3.5 py-1.5 text-xs font-bold text-white shadow-sm hover:bg-primary/95 hover:shadow active:scale-95 transition-all cursor-pointer"
            >
              ➕ {t('invite_generate')}
            </button>
          </div>

          <p className="text-xs text-on-surface-variant font-medium">
            💸 {t('commission')}: <span className="font-bold text-primary">{inviteCommissionRate}%</span> · {t('commission_balance')}: <span className="font-bold text-emerald-500">{formatMoney(inviteCommissionBalance, currencySymbol, currencyUnit)}</span>
          </p>

          {invites.length === 0 ? (
            <p className="text-xs text-on-surface-variant font-medium italic pt-2">
              {loading ? '...' : t('invites_empty')}
            </p>
          ) : (
            <ul className="space-y-2.5 max-h-60 overflow-y-auto pr-1">
              {invites.map((invite) => (
                <li
                  key={invite.code}
                  className="flex items-center justify-between rounded-xl bg-surface p-3 border border-outline-variant/30 hover:border-primary/20 transition-all duration-200"
                >
                  <div className="min-w-0">
                    <p className="truncate font-mono font-bold text-sm text-primary tracking-wide">{invite.code}</p>
                    <p className={`text-[10px] font-bold mt-0.5 ${invite.status === 0 ? 'text-amber-500' : 'text-on-surface-variant'}`}>
                      {invite.status === 0 ? t('unused') : t('used')}
                    </p>
                  </div>
                  <button
                    onClick={() => void copyCode(invite.code)}
                    className="rounded-lg bg-primary/10 px-3.5 py-1.5 text-xs font-bold text-primary hover:bg-primary/20 active:scale-95 transition-all cursor-pointer"
                  >
                    {copied === invite.code ? `✓ ${t('copied')}` : `📋 ${t('copy')}`}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      {/* Notices Section */}
      {notices.length > 0 && (
        <section className="space-y-4 rounded-2xl bg-surface-low p-5 shadow-sm border border-outline-variant/40">
          <h2 className="text-sm font-bold tracking-tight text-primary">📣 {t('announcement')}</h2>
          <ul className="space-y-4">
            {notices.map((notice) => (
              <li
                key={notice.id}
                className="space-y-2 border-l-3 border-primary/50 pl-3.5 py-0.5"
              >
                <p className="text-sm font-bold text-on-background">{notice.title}</p>
                <p className="whitespace-pre-wrap text-xs text-on-surface-variant leading-relaxed">
                  {notice.content.replace(/<[^>]+>/g, '')}
                </p>
                {notice.createdAt > 0 && (
                  <p className="text-[10px] font-bold text-on-surface-variant">
                    📅 {formatUnixDate(notice.createdAt)}
                  </p>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}
    </main>
  )
}
