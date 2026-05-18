import { useEffect, useState } from 'react'
import { xboardRequest } from '../api/xboard'
import { openExternal } from '../api/system'
import { useAppStore, type PlanItem, type PlanPrice } from '../store'
import { formatMoney, formatTrafficGb, numericValue } from '../format'

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
  const {
    baseUrl,
    authData,
    balance,
    capabilities,
    currencySymbol,
    currencyUnit,
    paymentEnabled,
    plans,
    setAdmobConfig,
    setProfile,
    setPlans,
  } = useAppStore()
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState('')

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
          currencyUnit: String(configData.currency_unit ?? ''),
          paymentEnabled: capabilities?.admob ? false : true,
          })
        if (capabilities?.admob) {
          const admob = await xboardRequest<XboardBody<Record<string, unknown>>>('admob_reward_config', { baseUrl, authData })
          if (cancelled) return
          if (admob.ok) {
            const data = admob.body?.data ?? {}
            const adEnabled = Boolean(data.ad_enabled)
            setProfile({ paymentEnabled: Boolean(data.payment_enabled) })
            setAdmobConfig({
              admobCloudEnabled: adEnabled,
              planRewardAdEnabled: adEnabled && Boolean(data.plan_reward_ad_enabled),
              pointsRewardAdEnabled: adEnabled && Boolean(data.points_reward_ad_enabled),
              appOpenAdEnabled: adEnabled && Boolean(data.app_open_ad_enabled),
              planRewardedAdUnitId: String(data.plan_rewarded_ad_unit_id ?? ''),
              pointsRewardedAdUnitId: String(data.points_rewarded_ad_unit_id ?? ''),
              appOpenAdUnitId: String(data.app_open_ad_unit_id ?? ''),
            })
          } else {
            setMessage(admob.body?.message ?? admob.error ?? `HTTP ${admob.status}`)
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
        }
        setPlans(planRows(response.body?.data).map(parsePlan))
      } catch (err) {
        if (!cancelled) setMessage(err instanceof Error ? err.message : String(err))
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [authData, baseUrl, capabilities?.admob, setAdmobConfig, setPlans, setProfile])

  async function openPlanPage(planId: number) {
    setMessage('')
    if (capabilities?.admob) {
      if (!paymentEnabled) {
        setMessage('云控未开启支付。')
        return
      }
      const response = await xboardRequest<XboardBody<string>>('xbclient_plan_payment', {
        baseUrl,
        authData,
        params: { plan_id: planId },
      })
      if (!response.ok || !response.body?.data) {
        setMessage(response.body?.message ?? response.error ?? `HTTP ${response.status}`)
        return
      }
      await openExternal(response.body.data)
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
    await openExternal(response.body.data)
  }

  async function buyWithBalance(plan: PlanItem, price: PlanPrice) {
    setMessage('')
    if (capabilities?.admob && !paymentEnabled) {
      setMessage('云控未开启支付。')
      return
    }
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
    setProfile({ balance: Math.max(0, balance - price.amount), paymentEnabled: true })
    setMessage('账户金额支付成功，请回到节点页刷新订阅。')
  }

  return (
    <main className="mx-auto max-w-3xl space-y-4 p-6">
      <header>
        <h1 className="text-xl font-semibold">套餐</h1>
        <p className="text-xs text-slate-400">
          {capabilities?.admob
            ? '移动端广告和支付开关由云控配置控制。'
            : '当前平台不接入广告，支付入口始终开启。'}
        </p>
      </header>
      {message && <p className="text-sm text-amber-300">{message}</p>}
      {loading && plans.length === 0 && <p className="text-sm text-slate-400">套餐加载中…</p>}
      {!loading && plans.length === 0 && <p className="text-sm text-slate-400">暂无可用套餐。</p>}
      <ul className="space-y-3">
        {plans.map((plan) => (
          <li key={plan.id} className="rounded-2xl bg-slate-900/60 p-5 ring-1 ring-white/10">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="text-lg font-semibold">{plan.name}</p>
                {plan.transferEnable > 0 && (
                  <p className="mt-1 text-sm text-slate-400">流量 {formatTrafficGb(plan.transferEnable)}</p>
                )}
              </div>
              {plan.prices.length > 0 && (
                <span className="shrink-0 rounded-full bg-sky-500/15 px-3 py-1 text-xs text-sky-300">
                  {formatMoney(plan.prices[0].amount, currencySymbol, currencyUnit)} 起
                </span>
              )}
            </div>
            {plan.content && !plan.content.trim().startsWith('[') && !plan.content.trim().startsWith('{') && (
              <p className="mt-3 whitespace-pre-wrap text-sm text-slate-300">{plan.content}</p>
            )}
            {plan.prices.length > 0 && (
              <ul className="mt-4 grid gap-2 sm:grid-cols-2">
                {plan.prices.map((price) => (
                  <li key={price.field} className="rounded-lg bg-slate-800/60 px-3 py-2 text-sm">
                    <div className="flex items-center justify-between gap-2">
                      <span>{price.label}</span>
                      <span className="text-emerald-300">
                        {formatMoney(price.amount, currencySymbol, currencyUnit)}
                      </span>
                    </div>
                    <button
                      onClick={() => void buyWithBalance(plan, price)}
                      className="mt-2 w-full rounded-lg bg-slate-700 px-3 py-1 text-xs hover:bg-slate-600"
                    >
                      账户金额支付
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <div className="mt-4 flex justify-end">
              <button
                onClick={() => void openPlanPage(plan.id)}
                disabled={capabilities?.admob && !paymentEnabled}
                className="rounded-lg bg-sky-500 px-4 py-2 text-sm hover:bg-sky-400"
              >
                {capabilities?.admob && !paymentEnabled ? '云控未开启支付' : '前往网页购买'}
              </button>
            </div>
          </li>
        ))}
      </ul>
    </main>
  )
}
