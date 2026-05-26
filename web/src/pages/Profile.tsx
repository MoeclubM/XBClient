import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { xboardRequest } from '../api/xboard'
import { showRewardedAd } from '../api/system'
import { useAppStore, type InviteItem } from '../store'
import { clearSession } from '../store/persist'
import { formatMoney, formatUnixDate, numericValue, publicErrorText } from '../format'
import { enabled, parseRewardLogs, rewardStatusText } from '../reward'
import { useTranslation } from '../i18n'
import { Tickets } from './Tickets'

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

interface XboardBody<T = unknown> {
  data?: T
  message?: string
  status?: string
}

function parseInvites(body: InviteFetchBody | undefined): InviteItem[] {
  const data = body?.data
  if (!data) return []
  const list = data.codes ?? data.codes_list ?? []
  return list.map((row) => ({ code: row.code ?? '', status: Number(row.status ?? 0) }))
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
    pointsRewardedAdUnitId,
    pointsRewardSsvUserId,
    pointsRewardSsvCustomData,
    capabilities,
    inviteForce,
    inviteCommissionRate,
    inviteCommissionBalance,
    invites,
    adRewardLogs,
    subscription,
    setProfile,
    setAdmobConfig,
    setRewardLogs,
    setInvites,
    reset,
  } = useAppStore()
  const [loading, setLoading] = useState(false)
  const [rewardLoading, setRewardLoading] = useState(false)
  const [error, setError] = useState('')
  const [copied, setCopied] = useState<string | null>(null)
  const [section, setSection] = useState<'overview' | 'tickets'>('overview')
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
              planRewardSsvUserId: String(data.plan_ssv_user_id ?? ''),
              planRewardSsvCustomData: String(data.plan_ssv_custom_data ?? ''),
              pointsRewardedAdUnitId: String(data.points_rewarded_ad_unit_id ?? ''),
              pointsRewardSsvUserId: String(data.points_ssv_user_id ?? ''),
              pointsRewardSsvCustomData: String(data.points_ssv_custom_data ?? ''),
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
              planRewardSsvUserId: '',
              planRewardSsvCustomData: '',
              pointsRewardedAdUnitId: '',
              pointsRewardSsvUserId: '',
              pointsRewardSsvCustomData: '',
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
            planRewardSsvUserId: '',
            planRewardSsvCustomData: '',
            pointsRewardedAdUnitId: '',
            pointsRewardSsvUserId: '',
            pointsRewardSsvCustomData: '',
            appOpenAdUnitId: '',
          })
          setRewardLogs([])
        }
        if (inviteList.ok) setInvites(parseInvites(inviteList.body))
      } catch (err) {
        if (!cancelled) setError(publicErrorText(err))
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [authData, baseUrl, mobileControl, setAdmobConfig, setProfile, setRewardLogs, setInvites])

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
      setError(publicErrorText(err))
    }
  }

  async function copyCode(code: string) {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(code)
      window.setTimeout(() => setCopied((current) => (current === code ? null : current)), 1500)
    } catch (err) {
      setError(publicErrorText(err))
    }
  }

  async function watchPointsRewardAd() {
    setError('')
    setRewardLoading(true)
    try {
      await showRewardedAd({
        adUnitId: pointsRewardedAdUnitId,
        userId: pointsRewardSsvUserId,
        customData: pointsRewardSsvCustomData,
      })
      const pending = await xboardRequest<XboardBody<Record<string, unknown>>>('xbclient_reward_pending', {
        baseUrl,
        authData,
        params: { custom_data: pointsRewardSsvCustomData },
      })
      if (!pending.ok || pending.body?.status === 'fail') {
        setError(pending.body?.message ?? pending.error ?? `HTTP ${pending.status}`)
        return
      }
      const [info, rewardHistory] = await Promise.all([
        xboardRequest<UserInfoBody>('user_info', { baseUrl, authData }),
        xboardRequest<XboardBody<unknown>>('xbclient_reward_history', { baseUrl, authData }),
      ])
      if (info.ok) {
        const data = info.body?.data ?? {}
        setProfile({
          balance: Math.round(numericValue(data.balance)),
          commissionBalance: Math.round(numericValue(data.commission_balance)),
        })
      } else {
        setError(`用户信息刷新失败：${info.body?.message ?? info.error ?? `HTTP ${info.status}`}`)
        return
      }
      if (!rewardHistory.ok) {
        setError(`广告奖励记录加载失败：${rewardHistory.body?.message ?? rewardHistory.error ?? `HTTP ${rewardHistory.status}`}`)
        return
      }
      setRewardLogs(parseRewardLogs(rewardHistory.body?.data))
    } catch (err) {
      setError(publicErrorText(err))
    } finally {
      setRewardLoading(false)
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

  if (section === 'tickets') {
    return (
      <div className="md3-screen space-y-4">
        <button
          type="button"
          onClick={() => setSection('overview')}
          className="md3-button md3-button-outlined text-xs"
        >
          ← 返回个人中心
        </button>
        <Tickets compact />
      </div>
    )
  }

  return (
    <main className="md3-screen space-y-4">
      <header className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <h1 className="text-2xl font-semibold tracking-tight text-on-background">{t('nav_profile')}</h1>
          <p className="mt-1 break-all text-xs text-on-surface-variant">{email || '未登录'}</p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <button
            onClick={() => setSection('tickets')}
            className="md3-button md3-button-outlined px-3 text-xs"
          >
            {t('nav_services')}
          </button>
          <button
            onClick={() => navigate('/settings')}
            className="md3-button md3-button-outlined px-3 text-xs"
          >
            {t('settings_button')}
          </button>
          <button
            onClick={() => void logout()}
            className="md3-button md3-button-danger px-3 text-xs"
          >
            {t('logout')}
          </button>
        </div>
      </header>

      {error && (
        <p className="md3-alert md3-alert-error break-words">
          {error}
        </p>
      )}

      <section className="md3-card-low space-y-4">
        <div>
          <p className="text-xs font-bold text-on-surface-variant tracking-wider uppercase">{t('balance')}</p>
          <p className="text-3xl font-semibold text-primary mt-1.5">{formatMoney(balance, currencySymbol, currencyUnit)}</p>
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
                {t('expires_at')}: {formatUnixDate(subscription.expiredAt)}
              </p>
            )}
          </div>
        )}

        {vpn && (
          <div className="rounded-xl bg-emerald-500/10 border border-emerald-500/20 p-3 flex items-center justify-between text-xs text-emerald-500 font-bold">
            <span>连接状态</span>
            <span>{t('status_connected')}</span>
          </div>
        )}
      </section>

      {mobileControl && pointsRewardAdEnabled && (
        <section className="md3-card-low space-y-3">
          <div className="flex items-center justify-between gap-3">
            <div>
              <h2 className="md3-section-title">{t('points_reward_ad_title')}</h2>
              <p className="mt-1 text-xs text-on-surface-variant leading-relaxed">
                观看 AdMob 激励广告后提交服务器验证。
              </p>
            </div>
            <button
              type="button"
              disabled={rewardLoading}
              onClick={() => void watchPointsRewardAd()}
              className="md3-button md3-button-tonal text-xs"
            >
              {rewardLoading ? '加载中…' : t('reward_watch')}
            </button>
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
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>
      )}

      {(inviteForce || inviteCommissionRate > 0) && (
        <section className="md3-card-low space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="md3-section-title">{t('invites_title')}</h2>
            <button
              onClick={() => void generateInvite()}
              className="md3-button md3-button-filled px-4 text-xs"
            >
              {t('invite_generate')}
            </button>
          </div>

          <p className="text-xs text-on-surface-variant font-medium">
            {t('commission')}: <span className="font-bold text-primary">{inviteCommissionRate}%</span> · {t('commission_balance')}: <span className="font-bold text-emerald-500">{formatMoney(inviteCommissionBalance, currencySymbol, currencyUnit)}</span>
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
                  className="flex items-center justify-between rounded-xl bg-surface p-3 border border-outline-variant/30 hover:border-primary/20"
                >
                  <div className="min-w-0">
                    <p className="truncate font-mono font-bold text-sm text-primary tracking-wide">{invite.code}</p>
                    <p className={`text-[10px] font-bold mt-0.5 ${invite.status === 0 ? 'text-amber-500' : 'text-on-surface-variant'}`}>
                      {invite.status === 0 ? t('unused') : t('used')}
                    </p>
                  </div>
                  <button
                    onClick={() => void copyCode(invite.code)}
                    className="md3-button md3-button-tonal px-3 text-xs"
                  >
                    {copied === invite.code ? t('copied') : t('copy')}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

    </main>
  )
}
