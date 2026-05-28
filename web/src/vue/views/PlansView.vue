<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { openInAppBrowser } from '../../api/system'
import { xboardRequest } from '../../api/xboard'
import { formatMoney, formatTrafficGb, numericValue, publicErrorText } from '../../format'
import { enabled } from '../../reward'
import { appState, store, t } from '../state'
import type { PlanItem, PlanPrice } from '../../store'

interface RawPlan {
  id?: number
  name?: string
  content?: string
  transfer_enable?: number
  month_price?: number
  quarter_price?: number
  half_year_price?: number
  year_price?: number
  two_year_price?: number
  three_year_price?: number
  onetime_price?: number
  reset_price?: number
}

interface XboardBody {
  data?: unknown
  message?: string
  status?: string
}

const loading = ref(false)
const message = ref('')
const error = ref('')

function rows(value: unknown): RawPlan[] {
  if (Array.isArray(value)) return value as RawPlan[]
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>
    for (const key of ['data', 'plans', 'list', 'items']) if (Array.isArray(object[key])) return object[key] as RawPlan[]
  }
  return []
}

function parsePlan(raw: RawPlan): PlanItem {
  const priceFields: Array<[keyof RawPlan, string]> = [
    ['month_price', t('price_month')],
    ['quarter_price', t('price_quarter')],
    ['half_year_price', t('price_half_year')],
    ['year_price', t('price_year')],
    ['two_year_price', t('price_two_year')],
    ['three_year_price', t('price_three_year')],
    ['onetime_price', t('price_onetime')],
    ['reset_price', t('price_reset')],
  ]
  const prices: PlanPrice[] = priceFields
    .map(([field, label]) => ({ field: String(field), label, amount: numericValue(raw[field]) }))
    .filter((item) => item.amount > 0)
  return {
    id: numericValue(raw.id),
    name: String(raw.name ?? ''),
    content: String(raw.content ?? '').replace(/<[^>]+>/g, ''),
    transferEnable: numericValue(raw.transfer_enable),
    prices,
  }
}

async function loadPlans() {
  loading.value = true
  message.value = ''
  error.value = ''
  try {
    const [config, plans, userInfo] = await Promise.all([
      xboardRequest<XboardBody>('user_config', { baseUrl: appState.baseUrl, authData: appState.authData }),
      xboardRequest<XboardBody>('plan_fetch', { baseUrl: appState.baseUrl, authData: appState.authData }),
      xboardRequest<XboardBody>('user_info', { baseUrl: appState.baseUrl, authData: appState.authData }),
    ])
    if (!plans.ok) {
      error.value = plans.body?.message ?? plans.error ?? `HTTP ${plans.status}`
      return
    }
    store().setPlans(rows(plans.body?.data).map(parsePlan).filter((plan) => plan.id > 0))
    const configData = config.body?.data && typeof config.body.data === 'object' ? config.body.data as Record<string, unknown> : {}
    const data = userInfo.body?.data && typeof userInfo.body.data === 'object' ? userInfo.body.data as Record<string, unknown> : {}
    store().setProfile({
      balance: numericValue(data.balance),
      commissionBalance: numericValue(data.commission_balance),
      currencySymbol: String(configData.currency_symbol ?? configData.currency ?? '¥'),
      currencyUnit: String(configData.currency_unit ?? configData.currency ?? ''),
    })
    await loadClientConfig()
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

async function loadClientConfig() {
  store().setRewardLogs([])
  const response = await xboardRequest<XboardBody>('admob_reward_config', { baseUrl: appState.baseUrl, authData: appState.authData })
  if (response.ok && response.body?.data && typeof response.body.data === 'object') {
    const data = response.body.data as Record<string, unknown>
    store().setProfile({ paymentEnabled: enabled(data.payment_enabled) })
    const github = String(data.github_project_url ?? '')
    if (github) store().setAdmobConfig({ githubProjectUrl: github })
    return
  }
  store().setProfile({ paymentEnabled: true })
}

async function buy(plan: PlanItem, price: PlanPrice) {
  if (appState.paymentEnabled) {
    const response = await xboardRequest<{ data?: string; message?: string }>('quick_login_url', {
      baseUrl: appState.baseUrl,
      authData: appState.authData,
      params: { redirect: `/#/plan/${plan.id}?period=${price.field}` },
    })
    if (!response.ok || !response.body?.data) {
      error.value = response.body?.message ?? response.error ?? `HTTP ${response.status}`
      return
    }
    await openInAppBrowser(response.body.data, plan.name)
    return
  }
  // Balance purchase
  const response = await xboardRequest<{ data?: string; message?: string }>('order_save', {
    baseUrl: appState.baseUrl,
    authData: appState.authData,
    params: { plan_id: plan.id, period: price.field },
  })
  if (!response.ok || !response.body?.data) {
    error.value = response.body?.message ?? response.error ?? `HTTP ${response.status}`
    return
  }
  const tradeNo = response.body.data
  const checkout = await xboardRequest<{ type?: number; message?: string }>('order_checkout', {
    baseUrl: appState.baseUrl,
    authData: appState.authData,
    params: { trade_no: tradeNo },
  })
  if (!checkout.ok || checkout.body?.type !== -1) {
    error.value = checkout.body?.message ?? checkout.error ?? `HTTP ${checkout.status}`
    return
  }
  message.value = t('balance_pay_success')
  await loadPlans()
}

function planPriceText(plan: PlanItem): string {
  if (!plan.prices.length) return t('plan_price_unset')
  return plan.prices.map((p) => `${p.label} ${formatMoney(p.amount, appState.currencySymbol || '¥', appState.currencyUnit)}`).join(' · ')
}

onMounted(loadPlans)
</script>

<template>
  <section class="liquid-page">
    <!-- Page Header -->
    <div class="page-header">
      <div class="page-header-bar" />
      <div class="page-header-content">
        <p class="muted">{{ formatMoney(appState.balance, appState.currencySymbol || '¥', appState.currencyUnit) }}</p>
        <h1>{{ t('nav_plans') }}</h1>
      </div>
      <v-btn variant="outlined" :loading="loading" @click="loadPlans">
        {{ loading ? t('refreshing') : t('refresh') }}
      </v-btn>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="message" color="primary" variant="tonal" class="mb-4">{{ message }}</v-alert>

    <!-- Plans -->
    <div class="plans-grid">
      <v-card v-for="plan in appState.plans" :key="plan.id" class="panel-card">
        <v-card-text>
          <div class="d-flex align-center justify-space-between">
            <div>
              <p class="text-h6 font-weight-bold mb-0">{{ plan.name }}</p>
              <p v-if="plan.transferEnable > 0" class="muted mt-1">
                {{ t('plan_traffic') }} {{ formatTrafficGb(plan.transferEnable) }}
              </p>
            </div>
            <span class="glass-badge">{{ planPriceText(plan) }}</span>
          </div>
          <p v-if="plan.content && !plan.content.startsWith('[') && !plan.content.startsWith('{')" class="muted mt-3 preline">
            {{ plan.content }}
          </p>
          <div v-if="!appState.paymentEnabled && plan.prices.length" class="mt-4 stack">
            <v-btn
              v-for="price in plan.prices"
              :key="price.field"
              variant="tonal"
              block
              @click="buy(plan, price)"
            >
              {{ price.label }} {{ formatMoney(price.amount, appState.currencySymbol || '¥', appState.currencyUnit) }}
            </v-btn>
          </div>
          <div v-if="appState.paymentEnabled && plan.prices.length" class="price-grid mt-4">
            <button
              v-for="price in plan.prices"
              :key="price.field"
              class="price-tile"
              @click="buy(plan, price)"
            >
              <span>{{ price.label }}</span>
              <strong>{{ formatMoney(price.amount, appState.currencySymbol || '¥', appState.currencyUnit) }}</strong>
            </button>
          </div>
        </v-card-text>
      </v-card>
      <v-card v-if="!loading && !appState.plans.length" class="panel-card">
        <v-card-text>
          <p class="muted">{{ t('plans_empty') }}</p>
        </v-card-text>
      </v-card>
    </div>
  </section>
</template>
