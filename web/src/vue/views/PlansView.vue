<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { failureText, parseUserCurrencyConfig } from '../../api/helpers'
import { openInAppBrowser } from '../../api/system'
import { xboardRequest, type XboardBody } from '../../api/xboard'
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

const loading = ref(false)
const message = ref('')
const error = ref('')

function rows(value: unknown): RawPlan[] {
  if (Array.isArray(value)) return value as RawPlan[]
  throw new Error('plan_fetch response data must be an array')
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
    .filter(([field]) => raw[field] !== undefined && raw[field] !== null)
    .map(([field, label]) => ({ field: String(field), label, amount: numericValue(raw[field]) }))
    .filter((item) => item.amount > 0)
  return {
    id: numericValue(raw.id),
    name: typeof raw.name === 'string' ? raw.name : (() => { throw new Error('plan name is required') })(),
    content: typeof raw.content === 'string' ? raw.content.replace(/<[^>]+>/g, '') : (() => { throw new Error('plan content is required') })(),
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
      error.value = failureText(plans)
      return
    }
    if (!config.ok) throw new Error(failureText(config))
    if (!userInfo.ok) throw new Error(failureText(userInfo))
    store().setPlans(rows(plans.body?.data).map(parsePlan).filter((plan) => plan.id > 0))
    if (!userInfo.body?.data || typeof userInfo.body.data !== 'object') throw new Error('user_info response missing data')
    const currency = parseUserCurrencyConfig(config.body.data)
    const data = userInfo.body.data as Record<string, unknown>
    store().setProfile({
      balance: numericValue(data.balance),
      commissionBalance: numericValue(data.commission_balance),
      currencySymbol: currency.currencySymbol,
      currencyUnit: currency.currencyUnit,
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
  if (!response.ok) throw new Error(failureText(response))
  if (!response.body?.data || typeof response.body.data !== 'object') throw new Error('admob_reward_config response missing data')
  const data = response.body.data as Record<string, unknown>
  store().setProfile({ paymentEnabled: enabled(data.payment_enabled) })
  store().setAdmobConfig({
    appOpenAdEnabled: enabled(data.app_open_ad_enabled),
    appOpenAdUnitId: typeof data.app_open_ad_unit_id === 'string' ? data.app_open_ad_unit_id : (() => { throw new Error('admob_reward_config app_open_ad_unit_id is required') })(),
    planRewardAdEnabled: enabled(data.plan_reward_ad_enabled),
    planRewardedAdUnitId: typeof data.plan_rewarded_ad_unit_id === 'string' ? data.plan_rewarded_ad_unit_id : (() => { throw new Error('admob_reward_config plan_rewarded_ad_unit_id is required') })(),
    planRewardSsvUserId: typeof data.plan_ssv_user_id === 'string' ? data.plan_ssv_user_id : (() => { throw new Error('admob_reward_config plan_ssv_user_id is required') })(),
    planRewardSsvCustomData: typeof data.plan_ssv_custom_data === 'string' ? data.plan_ssv_custom_data : (() => { throw new Error('admob_reward_config plan_ssv_custom_data is required') })(),
    pointsRewardAdEnabled: enabled(data.points_reward_ad_enabled),
    pointsRewardedAdUnitId: typeof data.points_rewarded_ad_unit_id === 'string' ? data.points_rewarded_ad_unit_id : (() => { throw new Error('admob_reward_config points_rewarded_ad_unit_id is required') })(),
    pointsRewardSsvUserId: typeof data.points_ssv_user_id === 'string' ? data.points_ssv_user_id : (() => { throw new Error('admob_reward_config points_ssv_user_id is required') })(),
    pointsRewardSsvCustomData: typeof data.points_ssv_custom_data === 'string' ? data.points_ssv_custom_data : (() => { throw new Error('admob_reward_config points_ssv_custom_data is required') })(),
  })
  if (typeof data.github_project_url === 'string' && data.github_project_url) {
    store().setAdmobConfig({ githubProjectUrl: data.github_project_url })
  }
}

async function buy(plan: PlanItem, price: PlanPrice) {
  if (appState.paymentEnabled) {
    const response = await xboardRequest<{ data?: string; message?: string }>('xbclient_plan_payment', {
      baseUrl: appState.baseUrl,
      authData: appState.authData,
      params: { plan_id: plan.id },
    })
    if (!response.ok || !response.body?.data) {
      error.value = !response.ok ? failureText(response) : 'xbclient_plan_payment response missing data'
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
    error.value = !response.ok ? failureText(response) : 'order_save response missing data'
    return
  }
  const tradeNo = response.body.data
  const checkout = await xboardRequest<{ type?: number; message?: string }>('order_checkout', {
    baseUrl: appState.baseUrl,
    authData: appState.authData,
    params: { trade_no: tradeNo },
  })
  if (!checkout.ok || checkout.body?.type !== -1) {
    error.value = !checkout.ok ? failureText(checkout) : 'order_checkout response type is not paid'
    return
  }
  message.value = t('balance_pay_success')
  await loadPlans()
}

function planPriceText(plan: PlanItem): string {
  if (!plan.prices.length) return t('plan_price_unset')
  return plan.prices.map((p) => `${p.label} ${formatMoney(p.amount, appState.currencySymbol, appState.currencyUnit)}`).join(' · ')
}

onMounted(loadPlans)
</script>

<template>
  <section class="liquid-page">
    <!-- Page Header -->
    <div class="page-header">
      <div class="page-header-bar" />
      <div class="page-header-content">
        <p class="muted">{{ formatMoney(appState.balance, appState.currencySymbol, appState.currencyUnit) }}</p>
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
            <v-chip color="primary" variant="tonal">{{ planPriceText(plan) }}</v-chip>
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
              {{ price.label }} {{ formatMoney(price.amount, appState.currencySymbol, appState.currencyUnit) }}
            </v-btn>
          </div>
          <v-row v-if="appState.paymentEnabled && plan.prices.length" class="mt-2">
            <v-col v-for="price in plan.prices" :key="price.field" cols="12" sm="6">
              <v-btn variant="outlined" block class="justify-space-between" @click="buy(plan, price)">
                <span>{{ price.label }}</span>
                <strong>{{ formatMoney(price.amount, appState.currencySymbol, appState.currencyUnit) }}</strong>
              </v-btn>
            </v-col>
          </v-row>
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
