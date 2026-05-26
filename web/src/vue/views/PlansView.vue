<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { openInAppBrowser } from '../../api/system'
import { xboardRequest } from '../../api/xboard'
import { formatMoney, formatTrafficGb, numericValue, publicErrorText } from '../../format'
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
}

interface XboardBody {
  data?: unknown
  message?: string
}

const loading = ref(false)
const message = ref('')

function rows(value: unknown): RawPlan[] {
  if (Array.isArray(value)) return value as RawPlan[]
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>
    for (const key of ['data', 'plans', 'list']) if (Array.isArray(object[key])) return object[key] as RawPlan[]
  }
  return []
}

function parsePlan(raw: RawPlan): PlanItem {
  const priceFields: Array<[keyof RawPlan, string]> = [
    ['month_price', '月付'],
    ['quarter_price', '季付'],
    ['half_year_price', '半年'],
    ['year_price', '年付'],
    ['two_year_price', '两年'],
    ['three_year_price', '三年'],
    ['onetime_price', '一次性'],
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
  try {
    const [plans, userInfo] = await Promise.all([
      xboardRequest<XboardBody>('plan_fetch', { baseUrl: appState.baseUrl, authData: appState.authData }),
      xboardRequest<XboardBody>('user_info', { baseUrl: appState.baseUrl, authData: appState.authData }),
    ])
    if (!plans.ok) {
      message.value = plans.body?.message ?? plans.error ?? `HTTP ${plans.status}`
      return
    }
    store().setPlans(rows(plans.body?.data).map(parsePlan).filter((plan) => plan.id > 0))
    const data = userInfo.body?.data && typeof userInfo.body.data === 'object' ? userInfo.body.data as Record<string, unknown> : {}
    store().setProfile({
      balance: numericValue(data.balance),
      commissionBalance: numericValue(data.commission_balance),
      currencySymbol: String(data.currency_symbol ?? '¥'),
      currencyUnit: String(data.currency_unit ?? ''),
    })
  } catch (err) {
    message.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

async function buy(plan: PlanItem, price: PlanPrice) {
  const response = await xboardRequest<{ data?: string; message?: string }>('quick_login_url', {
    baseUrl: appState.baseUrl,
    authData: appState.authData,
    params: { redirect: `/plan/${plan.id}?period=${price.field}` },
  })
  if (!response.ok || !response.body?.data) {
    message.value = response.body?.message ?? response.error ?? `HTTP ${response.status}`
    return
  }
  await openInAppBrowser(response.body.data, plan.name)
}

onMounted(loadPlans)
</script>

<template>
  <section class="liquid-page">
    <header class="liquid-header">
      <div>
        <p class="eyebrow">{{ t('balance') }}</p>
        <h1>{{ t('nav_plans') }}</h1>
        <p class="muted">{{ formatMoney(appState.balance, appState.currencySymbol || '¥', appState.currencyUnit) }}</p>
      </div>
      <v-btn class="glass-button" :loading="loading" @click="loadPlans">{{ t('refreshing') }}</v-btn>
    </header>

    <v-alert v-if="message" class="mb-4" color="primary" variant="tonal">{{ message }}</v-alert>

    <div class="plans-grid">
      <v-card v-for="plan in appState.plans" :key="plan.id" class="glass-card plan-card pa-4">
        <div class="section-row">
          <h2>{{ plan.name }}</h2>
          <span class="glass-badge">{{ formatTrafficGb(plan.transferEnable) }}</span>
        </div>
        <p class="muted preline">{{ plan.content }}</p>
        <div class="price-grid">
          <button v-for="price in plan.prices" :key="price.field" class="price-tile" @click="buy(plan, price)">
            <span>{{ price.label }}</span>
            <strong>{{ formatMoney(price.amount, appState.currencySymbol || '¥', appState.currencyUnit) }}</strong>
          </button>
        </div>
      </v-card>
    </div>
  </section>
</template>
