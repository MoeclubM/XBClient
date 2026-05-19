import { useEffect, useState } from 'react'
import { xboardRequest } from '../api/xboard'
import { openExternal } from '../api/system'
import { useAppStore, type PlanItem, type PlanPrice } from '../store'
import { formatMoney, formatTrafficGb, numericValue } from '../format'
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
    plans,
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
          paymentEnabled: true,
        })
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
  }, [authData, baseUrl, setPlans, setProfile])

  async function openPlanPage(planId: number) {
    setMessage('')
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
    <main className="mx-auto max-w-3xl space-y-5 p-6 pb-24">
      <header className="border-b border-outline-variant/30 pb-3">
        <h1 className="text-xl font-bold tracking-tight text-primary">{t('nav_plans')}</h1>
        <p className="mt-1 text-xs text-on-surface-variant font-medium">
          请选择合适的套餐订阅，支持使用账户金额抵扣或前往网页购买。
        </p>
      </header>

      {message && (
        <p className="rounded-lg bg-primary/10 p-3 text-xs font-semibold text-primary border border-primary/20">
          {message}
        </p>
      )}

      {loading && plans.length === 0 && <p className="text-xs text-on-surface-variant font-medium">套餐加载中…</p>}
      {!loading && plans.length === 0 && <p className="text-xs text-on-surface-variant font-medium">暂无可用套餐。</p>}

      <ul className="space-y-4">
        {plans.map((plan) => (
          <li
            key={plan.id}
            className="rounded-2xl bg-surface-low p-5 shadow-sm border border-outline-variant/40 hover:border-primary/25 transition-all duration-200"
          >
            <div className="flex items-start justify-between gap-3 pb-3 border-b border-outline-variant/20">
              <div className="min-w-0">
                <p className="text-base font-extrabold text-on-background tracking-tight">{plan.name}</p>
                {plan.transferEnable > 0 && (
                  <p className="mt-0.5 text-xs text-on-surface-variant font-semibold">
                    💾 流量 {formatTrafficGb(plan.transferEnable)}
                  </p>
                )}
              </div>
              {plan.prices.length > 0 && (
                <span className="shrink-0 rounded-full bg-primary/10 px-3 py-1 text-xs font-extrabold text-primary border border-primary/20 shadow-sm">
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
                    className="rounded-xl bg-surface p-3 border border-outline-variant/25 flex flex-col justify-between"
                  >
                    <div className="flex items-center justify-between gap-2 text-xs font-bold text-on-surface-variant">
                      <span>{price.label}</span>
                      <span className="text-emerald-500 font-extrabold">
                        {formatMoney(price.amount, currencySymbol, currencyUnit)}
                      </span>
                    </div>
                    <button
                      onClick={() => void buyWithBalance(plan, price)}
                      className="mt-3 w-full rounded-lg bg-primary/10 py-1.5 text-[11px] font-bold text-primary hover:bg-primary/20 active:scale-95 transition-all cursor-pointer border border-primary/20"
                    >
                      💳 {t('purchase_balance')}
                    </button>
                  </li>
                ))}
              </ul>
            )}

            <div className="mt-4 flex justify-end">
              <button
                onClick={() => void openPlanPage(plan.id)}
                className="rounded-xl bg-primary px-4 py-2 text-xs font-bold text-white shadow-sm hover:bg-primary/95 hover:shadow active:scale-95 transition-all cursor-pointer"
              >
                🛒 {t('purchase_web')}
              </button>
            </div>
          </li>
        ))}
      </ul>
    </main>
  )
}
