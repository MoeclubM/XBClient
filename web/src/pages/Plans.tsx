import { useEffect, useState } from 'react'
import { xboardRequest } from '../api/xboard'
import { openInAppBrowser, showRewardedAd } from '../api/system'
import { useAppStore, type PlanItem, type PlanPrice } from '../store'
import { formatMoney, formatTrafficGb, numericValue, publicErrorText } from '../format'
import { enabled, parseRewardLogs, rewardStatusText } from '../reward'
import { useTranslation } from '../i18n'

const PRICE_FIELDS: Array<{ field: string; label: string }> = [
  { field: 'month_price', label: '月付' },
  { field: 'quarter_price', label: '季付' },
  { field: 'half_year_price', label: '半年付' },
  { field: 'year_price', label: '年付' },
  { field: 'two_year_price', label: '两年付' },
  { field: 'three_year_price', label: '三年付' },
  { field: 'onetime_price', label: '一次性' },
  { field: 'reset_price', label: '重置流量' },
]

interface RawPlan {
  id: number
  name?: string
  content?: string
  transfer_enable?: number | string
  [key: string]: unknown
}

interface XboardBody<T = unknown> {
  data?: T
  message?: string
  status?: string
}

function parsePlan(raw: RawPlan): PlanItem {
  const prices: PlanPrice[] = []
  for (const { field, label } of PRICE_FIELDS) {
    const amount = Math.round(numericValue(raw[field]))
    if (amount > 0) prices.push({ field, label, amount })
  }
  return {
    id: raw.id,
    name: raw.name ?? `套餐 ${raw.id}`,
    content: raw.content ?? '',
    transferEnable: numericValue(raw.transfer_enable),
    prices,
  }
}

function planRows(value: unknown): RawPlan[] {
  if (Array.isArray(value)) return value as RawPlan[]
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>
    for (const key of ['data', 'list', 'items', 'plans']) {
      if (Array.isArray(object[key])) return object[key] as RawPlan[]
    }
  }
  return []
}

export function Plans() {
  const t = useTranslation()
  const {
    baseUrl,
    authData,
    balance,
    currencySymbol,
    currencyUnit,
    paymentEnabled,
    planRewardAdEnabled,
    planRewardedAdUnitId,
    planRewardSsvUserId,
    planRewardSsvCustomData,
    capabilities,
    adRewardLogs,
    plans,
    setProfile,
    setAdmobConfig,
    setRewardLogs,
    setPlans,
  } = useAppStore()
  const [loading, setLoading] = useState(false)
  const [rewardLoading, setRewardLoading] = useState(false)
  const [message, setMessage] = useState('')
  const mobileControl = capabilities?.admob === true
  const planLogs = adRewardLogs.filter((log) => log.scene === 'plan')

  useEffect(() => {
    let cancelled = false
    async function load() {
      if (!authData) return
      setLoading(true)
      setMessage('')
      try {
        const [config, info, response] = await Promise.all([
          xboardRequest<XboardBody<Record<string, unknown>>>('user_config', { baseUrl, authData }),
          xboardRequest<XboardBody<Record<string, unknown>>>('user_info', { baseUrl, authData }),
          xboardRequest<XboardBody<unknown>>('plan_fetch', { baseUrl, authData }),
        ])
        if (cancelled) return
        if (!response.ok) {
          setMessage(response.body?.message ?? response.error ?? `HTTP ${response.status}`)
          return
        }
        const configData = config.body?.data ?? {}
        const infoData = info.body?.data ?? {}
        setProfile({
          balance: Math.round(numericValue(infoData.balance)),
          commissionBalance: Math.round(numericValue(infoData.commission_balance)),
          currencySymbol: String(configData.currency_symbol ?? configData.currency ?? ''),
          currencyUnit: String(configData.currency_unit ?? configData.currency ?? ''),
        })
        setPlans(planRows(response.body?.data).map(parsePlan))
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
            setMessage(`云控配置加载失败：${rewardConfig.body?.message ?? rewardConfig.error ?? `HTTP ${rewardConfig.status}`}`)
          }
          if (rewardHistory.ok) {
            setRewardLogs(parseRewardLogs(rewardHistory.body?.data))
          } else {
            setMessage(`广告奖励记录加载失败：${rewardHistory.body?.message ?? rewardHistory.error ?? `HTTP ${rewardHistory.status}`}`)
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
      } catch (err) {
        if (!cancelled) setMessage(publicErrorText(err))
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [authData, baseUrl, mobileControl, setAdmobConfig, setPlans, setProfile, setRewardLogs])

  async function openPlanPage(planId: number) {
    setMessage('')
    if (mobileControl) {
      const response = await xboardRequest<XboardBody<string>>('xbclient_plan_payment', {
        baseUrl,
        authData,
        params: { plan_id: planId },
      })
      if (!response.ok || !response.body?.data) {
        setMessage(response.body?.message ?? response.error ?? `HTTP ${response.status}`)
        return
      }
      await openInAppBrowser(response.body.data, '套餐购买')
      return
    }
    const response = await xboardRequest<XboardBody<string>>('quick_login_url', {
      baseUrl,
      authData,
      params: { redirect: `/#/plan/${planId}` },
    })
    if (!response.ok || !response.body?.data) {
      setMessage(response.body?.message ?? response.error ?? `HTTP ${response.status}`)
      return
    }
    await openInAppBrowser(response.body.data, '套餐购买')
  }

  async function watchPlanRewardAd() {
    setMessage('')
    setRewardLoading(true)
    try {
      await showRewardedAd({
        adUnitId: planRewardedAdUnitId,
        userId: planRewardSsvUserId,
        customData: planRewardSsvCustomData,
      })
      const pending = await xboardRequest<XboardBody<Record<string, unknown>>>('xbclient_reward_pending', {
        baseUrl,
        authData,
        params: { custom_data: planRewardSsvCustomData },
      })
      if (!pending.ok || pending.body?.status === 'fail') {
        setMessage(pending.body?.message ?? pending.error ?? `HTTP ${pending.status}`)
        return
      }
      const [info, rewardHistory] = await Promise.all([
        xboardRequest<XboardBody<Record<string, unknown>>>('user_info', { baseUrl, authData }),
        xboardRequest<XboardBody<unknown>>('xbclient_reward_history', { baseUrl, authData }),
      ])
      if (info.ok) {
        const data = info.body?.data ?? {}
        setProfile({
          balance: Math.round(numericValue(data.balance)),
          commissionBalance: Math.round(numericValue(data.commission_balance)),
        })
      } else {
        setMessage(`用户信息刷新失败：${info.body?.message ?? info.error ?? `HTTP ${info.status}`}`)
        return
      }
      if (!rewardHistory.ok) {
        setMessage(`广告奖励记录加载失败：${rewardHistory.body?.message ?? rewardHistory.error ?? `HTTP ${rewardHistory.status}`}`)
        return
      }
      setRewardLogs(parseRewardLogs(rewardHistory.body?.data))
      setMessage(pending.body?.message ?? '广告奖励验证已提交。')
    } catch (err) {
      setMessage(publicErrorText(err))
    } finally {
      setRewardLoading(false)
    }
  }

  async function buyWithBalance(plan: PlanItem, price: PlanPrice) {
    setMessage('')
    if (price.amount > balance) {
      setMessage('账户金额不足，当前只允许账户金额足额抵扣。')
      return
    }
    const saved = await xboardRequest<XboardBody<string>>('order_save', {
      baseUrl,
      authData,
      params: { plan_id: plan.id, period: price.field },
    })
    if (!saved.ok || !saved.body?.data) {
      setMessage(saved.body?.message ?? saved.error ?? `HTTP ${saved.status}`)
      return
    }
    const checked = await xboardRequest<{ type?: number; message?: string }>('order_checkout', {
      baseUrl,
      authData,
      params: { trade_no: saved.body.data },
    })
    if (!checked.ok) {
      setMessage(checked.body?.message ?? checked.error ?? `HTTP ${checked.status}`)
      return
    }
    if (checked.body?.type !== -1) {
      setMessage('订单未完成账户金额抵扣。')
      return
    }
    const [info, planList] = await Promise.all([
      xboardRequest<XboardBody<Record<string, unknown>>>('user_info', { baseUrl, authData }),
      xboardRequest<XboardBody<unknown>>('plan_fetch', { baseUrl, authData }),
    ])
    if (info.ok) {
      const data = info.body?.data ?? {}
      setProfile({
        balance: Math.round(numericValue(data.balance)),
        commissionBalance: Math.round(numericValue(data.commission_balance)),
      })
    }
    if (planList.ok) setPlans(planRows(planList.body?.data).map(parsePlan))
    setMessage('账户金额支付成功，请回到连接页自动同步。')
  }

  const isErrorMsg = message.includes('失败') || message.includes('错误') || message.includes('fail') || message.includes('error') || message.includes('HTTP')

  return (
    <main className="md3-screen space-y-5">
      <header className="md3-page-header">
        <span className="md3-page-rail" />
        <div className="min-w-0">
          <h1 className="text-2xl font-semibold tracking-tight text-on-background">{t('nav_plans')}</h1>
          <p className="mt-1 text-sm text-on-surface-variant">{formatMoney(balance, currencySymbol, currencyUnit)}</p>
        </div>
      </header>

      {mobileControl && planRewardAdEnabled && (
        <section className="md3-card-low space-y-3">
          <div className="flex items-center justify-between gap-3">
            <div>
              <h2 className="md3-section-title">{t('plan_reward_ad_title')}</h2>
              <p className="mt-1 text-xs text-on-surface-variant leading-relaxed">
                观看 AdMob 激励广告后提交服务器验证。
              </p>
            </div>
            <button
              type="button"
              disabled={rewardLoading}
              onClick={() => void watchPlanRewardAd()}
              className="md3-button md3-button-tonal text-xs"
            >
              {rewardLoading ? '加载中…' : t('reward_watch')}
            </button>
          </div>
          {planLogs.length > 0 && (
            <ul className="space-y-2 border-t border-outline-variant/20 pt-3">
              {planLogs.slice(0, 3).map((log) => (
                <li key={log.id || `${log.transactionId}-${log.createdAt}`} className="flex items-center justify-between gap-3 text-xs">
                  <span className="min-w-0 truncate text-on-surface-variant">
                    {log.rewardContent || rewardStatusText(log.status)}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      {message && !isErrorMsg && (
        <p className="md3-alert md3-alert-info">
          {message}
        </p>
      )}
      {message && isErrorMsg && (
        <p className="md3-alert md3-alert-error break-words">
          {message}
        </p>
      )}
      {loading && plans.length === 0 && (
        <div className="flex justify-center p-8">
          <p className="text-xs font-semibold text-on-surface-variant">加载中...</p>
        </div>
      )}
      {!loading && plans.length === 0 && <p className="text-xs text-on-surface-variant font-medium">暂无可用套餐。</p>}

      <ul className="space-y-4">
        {plans.map((plan) => (
          <li
            key={plan.id}
            className="md3-card-low"
          >
            <div className="flex items-start justify-between gap-3 pb-3 border-b border-outline-variant/20">
              <div className="min-w-0">
                <p className="text-base font-semibold text-on-background tracking-tight">{plan.name}</p>
                {plan.transferEnable > 0 && (
                  <p className="mt-0.5 text-xs text-on-surface-variant font-semibold">
                    流量 {formatTrafficGb(plan.transferEnable)}
                  </p>
                )}
              </div>
              {plan.prices.length > 0 && (
                <span className="shrink-0 rounded-full bg-primary-container px-3 py-1 text-xs font-semibold text-on-primary-container">
                  {formatMoney(plan.prices[0].amount, currencySymbol, currencyUnit)} 起
                </span>
              )}
            </div>

            {plan.content && !plan.content.trim().startsWith('[') && !plan.content.trim().startsWith('{') && (
              <p className="mt-3 whitespace-pre-wrap text-xs text-on-surface-variant leading-relaxed font-medium">
                {plan.content}
              </p>
            )}

            {plan.prices.length > 0 && (
              <ul className="mt-4 grid gap-3 sm:grid-cols-2">
                {plan.prices.map((price) => (
                  <li
                    key={price.field}
                    className="flex flex-col justify-between rounded-2xl border border-outline-variant bg-surface p-3"
                  >
                    <div className="flex items-center justify-between gap-2 text-xs font-bold text-on-surface-variant">
                      <span>{price.label}</span>
                      <span className="text-emerald-500 font-extrabold">
                        {formatMoney(price.amount, currencySymbol, currencyUnit)}
                      </span>
                    </div>
                    <button
                      onClick={() => void buyWithBalance(plan, price)}
                      className="md3-button md3-button-outlined mt-3 w-full text-xs"
                    >
                      {t('purchase_balance')}
                    </button>
                  </li>
                ))}
              </ul>
            )}

            {(!mobileControl || paymentEnabled) && (
              <div className="mt-4 flex justify-end">
                <button
                  onClick={() => void openPlanPage(plan.id)}
                  className="md3-button md3-button-filled text-xs"
                >
                  {t('purchase_web')}
                </button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </main>
  )
}
