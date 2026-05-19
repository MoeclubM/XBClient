import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { xboardRequest } from '../api/xboard'
import { useAppStore, type InviteItem, type NoticeItem } from '../store'
import { clearSession } from '../store/persist'
import { formatMoney, formatUnixDate, numericValue } from '../format'
import { enabled, parseRewardLogs, rewardStatusText } from '../reward'
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
  message?: string
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
  message?: string
}

interface InviteFetchBody {
  data?: { codes?: Array<{ code?: string; status?: number }>; codes_list?: Array<{ code?: string; status?: number }> }
  message?: string
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
  message?: string
}

interface XboardBody<T = unknown> {
  data?: T
  message?: string
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
    pointsRewardAdEnabled,
    capabilities,
    inviteForce,
    inviteCommissionRate,
    inviteCommissionBalance,
    invites,
    notices,
    adRewardLogs,
    subscription,
    setProfile,
    setAdmobConfig,
    setRewardLogs,
    setInvites,
    setNotices,
    reset,
  } = useAppStore()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [copied, setCopied] = useState<string | null>(null)
  const mobileControl = capabilities?.admob === true
  const pointsLogs = adRewardLogs.filter((log) => log.scene === 'points')

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
        } else {
          setError(info.body?.message ?? info.error ?? `HTTP ${info.status}`)
        }
        if (config.ok) {
          const data = config.body?.data ?? {}
          setProfile({
            currencySymbol: data.currency_symbol ?? data.currency ?? '',
            currencyUnit: data.currency_unit ?? data.currency ?? '',
            inviteForce: Boolean(data.invite_force),
            inviteCommissionRate: Math.round(numericValue(data.commission_rate)),
            inviteCommissionBalance: Math.round(numericValue(data.invite_commission_balance)),
          })
        }
        if (mobileControl) {
          const [rewardConfig, rewardHistory] = await Promise.all([
            xboardRequest<XboardBody<Record<string, unknown>>>('admob_reward_config', { baseUrl, authData }),
            xboardRequest<XboardBody<unknown>>('xbclient_reward_history', { baseUrl, authData }),
          ])
          if (cancelled) return
          if (rewardConfig.ok && rewardConfig.body?.data) {
            const data = rewardConfig.body.data
            const adEnabled = enabled(data.ad_enabled)
            setProfile({ paymentEnabled: enabled(data.payment_enabled) })
            setAdmobConfig({
              admobCloudEnabled: adEnabled,
              planRewardAdEnabled: adEnabled && enabled(data.plan_reward_ad_enabled),
              pointsRewardAdEnabled: adEnabled && enabled(data.points_reward_ad_enabled),
              appOpenAdEnabled: enabled(data.app_open_ad_enabled),
              planRewardedAdUnitId: String(data.plan_rewarded_ad_unit_id ?? ''),
              pointsRewardedAdUnitId: String(data.points_rewarded_ad_unit_id ?? ''),
              appOpenAdUnitId: String(data.app_open_ad_unit_id ?? ''),
              githubProjectUrl: String(data.github_project_url ?? ''),
            })
          } else {
            setProfile({ paymentEnabled: false })
            setAdmobConfig({
              admobCloudEnabled: false,
              planRewardAdEnabled: false,
              pointsRewardAdEnabled: false,
              appOpenAdEnabled: false,
              planRewardedAdUnitId: '',
              pointsRewardedAdUnitId: '',
              appOpenAdUnitId: '',
            })
            setError(rewardConfig.body?.message ?? rewardConfig.error ?? `HTTP ${rewardConfig.status}`)
          }
          if (rewardHistory.ok) {
            setRewardLogs(parseRewardLogs(rewardHistory.body?.data))
          } else {
            setError(rewardHistory.body?.message ?? rewardHistory.error ?? `HTTP ${rewardHistory.status}`)
          }
        } else {
          setProfile({ paymentEnabled: true })
          setAdmobConfig({
            admobCloudEnabled: false,
            planRewardAdEnabled: false,
            pointsRewardAdEnabled: false,
            appOpenAdEnabled: false,
            planRewardedAdUnitId: '',
            pointsRewardedAdUnitId: '',
            appOpenAdUnitId: '',
          })
          setRewardLogs([])
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
  }, [authData, baseUrl, mobileControl, setAdmobConfig, setProfile, setRewardLogs, setInvites, setNotices])

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
      <header className="border-b border-outline-variant/30 pb-3 flex items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-primary">{t('nav_profile')}</h1>
          <p className="mt-1 text-xs text-on-surface-variant font-medium break-all">{email || '未登录'}</p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => navigate('/services')}
            className="rounded-xl bg-primary/10 px-4 py-2 text-xs font-bold text-primary hover:bg-primary/20 active:scale-95 transition-all cursor-pointer border border-primary/20"
          >
            🧩 {t('nav_services')}
          </button>
          <button
            onClick={() => navigate('/settings')}
            className="rounded-xl bg-primary/10 px-4 py-2 text-xs font-bold text-primary hover:bg-primary/20 active:scale-95 transition-all cursor-pointer border border-primary/20"
          >
            ⚙️ {t('settings_button')}
          </button>
          <button
            onClick={() => void logout()}
            className="rounded-xl bg-rose-500/10 px-4 py-2 text-xs font-bold text-rose-500 hover:bg-rose-500/20 active:scale-95 transition-all cursor-pointer border border-rose-500/20"
          >
            👋 {t('logout')}
          </button>
        </div>
      </header>

      {error && (
        <p className="rounded-lg bg-rose-500/10 p-3 text-xs font-semibold text-rose-500 border border-rose-500/20 break-words">
          {error}
        </p>
      )}

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

      {mobileControl && (pointsRewardAdEnabled || pointsLogs.length > 0) && (
        <section className="space-y-3 rounded-2xl bg-surface-low p-5 shadow-sm border border-outline-variant/40">
          <div className="flex items-center justify-between gap-3">
            <div>
              <h2 className="text-sm font-bold tracking-tight text-primary">🎁 {t('points_reward_ad_title')}</h2>
              <p className="mt-1 text-xs text-on-surface-variant leading-relaxed">
                {pointsRewardAdEnabled ? t('reward_ad_unavailable') : t('reward_ad_cloud_off')}
              </p>
            </div>
            {pointsRewardAdEnabled && (
              <button
                type="button"
                disabled
                title={capabilities?.admob ? 'Tauri 前端未接入广告展示调用。' : t('reward_ad_unavailable')}
                className="rounded-xl bg-primary/10 px-4 py-2 text-xs font-bold text-primary opacity-60 border border-primary/20"
              >
                {t('reward_watch')}
              </button>
            )}
          </div>
          {pointsLogs.length > 0 && (
            <div className="space-y-2 border-t border-outline-variant/20 pt-3">
              <p className="text-xs font-bold uppercase tracking-wider text-on-surface-variant">{t('reward_recent')}</p>
              <ul className="space-y-2">
                {pointsLogs.slice(0, 3).map((log) => (
                  <li key={log.id || `${log.transactionId}-${log.createdAt}`} className="flex items-center justify-between gap-3 text-xs">
                    <div className="min-w-0">
                      <p className="truncate font-semibold text-on-background">{log.rewardContent || rewardStatusText(log.status)}</p>
                      {log.createdAt > 0 && <p className="mt-0.5 text-[10px] text-on-surface-variant">{formatUnixDate(log.createdAt)}</p>}
                      {log.status === 'failed' && log.error && <p className="mt-0.5 text-[10px] text-rose-500">{log.error}</p>}
                    </div>
                    <span className="shrink-0 rounded-full bg-primary/10 px-2 py-0.5 font-bold text-primary">
                      {rewardStatusText(log.status)}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>
      )}

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
