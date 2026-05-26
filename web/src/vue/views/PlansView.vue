<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { openInAppBrowser, showRewardedAd } from '../../api/system'
import { xboardRequest } from '../../api/xboard'
import { formatMoney, formatTrafficGb, numericValue, publicErrorText } from '../../format'
import { enabled, parseRewardLogs, rewardStatusText } from '../../reward'
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
const rewardLoading = ref(false)
const message = ref('')

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
    ['month_price', '月付'],
    ['quarter_price', '季付'],
    ['half_year_price', '半年'],
    ['year_price', '年付'],
    ['two_year_price', '两年'],
    ['three_year_price', '三年'],
    ['onetime_price', '一次性'],
    ['reset_price', '重置流量'],
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
    const [config, plans, userInfo] = await Promise.all([
      xboardRequest<XboardBody>('user_config', { baseUrl: appState.baseUrl, authData: appState.authData }),
      xboardRequest<XboardBody>('plan_fetch', { baseUrl: appState.baseUrl, authData: appState.authData }),
      xboardRequest<XboardBody>('user_info', { baseUrl: appState.baseUrl, authData: appState.authData }),
    ])
    if (!plans.ok) {
      message.value = plans.body?.message ?? plans.error ?? `HTTP ${plans.status}`
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
    await loadRewardConfig()
  } catch (err) {
    message.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

async function loadRewardConfig() {
  if (!appState.capabilities?.admob) {
    store().setProfile({ paymentEnabled: true })
    store().setRewardLogs([])
    return
  }
  const [rewardConfig, rewardHistory] = await Promise.all([
    xboardRequest<XboardBody>('admob_reward_config', { baseUrl: appState.baseUrl, authData: appState.authData }),
    xboardRequest<XboardBody>('xbclient_reward_history', { baseUrl: appState.baseUrl, authData: appState.authData }),
  ])
  if (rewardConfig.ok && rewardConfig.body?.data && typeof rewardConfig.body.data === 'object') {
    const data = rewardConfig.body.data as Record<string, unknown>
    const adEnabled = enabled(data.ad_enabled)
    store().setProfile({ paymentEnabled: enabled(data.payment_enabled) })
    store().setAdmobConfig({
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
    store().setProfile({ paymentEnabled: false })
  }
  if (rewardHistory.ok) store().setRewardLogs(parseRewardLogs(rewardHistory.body?.data))
}

async function buy(plan: PlanItem, price: PlanPrice) {
  if (appState.capabilities?.admob) {
    const response = await xboardRequest<{ data?: string; message?: string }>('xbclient_plan_payment', {
      baseUrl: appState.baseUrl,
      authData: appState.authData,
      params: { plan_id: plan.id },
    })
    if (!response.ok || !response.body?.data) {
      message.value = response.body?.message ?? response.error ?? `HTTP ${response.status}`
      return
    }
    await openInAppBrowser(response.body.data, plan.name)
    return
  }
  const response = await xboardRequest<{ data?: string; message?: string }>('quick_login_url', {
    baseUrl: appState.baseUrl,
    authData: appState.authData,
    params: { redirect: `/#/plan/${plan.id}?period=${price.field}` },
  })
  if (!response.ok || !response.body?.data) {
    message.value = response.body?.message ?? response.error ?? `HTTP ${response.status}`
    return
  }
  await openInAppBrowser(response.body.data, plan.name)
}

async function watchPlanRewardAd() {
  rewardLoading.value = true
  message.value = ''
  try {
    await showRewardedAd({
      adUnitId: appState.planRewardedAdUnitId,
      userId: appState.planRewardSsvUserId,
      customData: appState.planRewardSsvCustomData,
    })
    const pending = await xboardRequest<XboardBody>('xbclient_reward_pending', {
      baseUrl: appState.baseUrl,
      authData: appState.authData,
      params: { custom_data: appState.planRewardSsvCustomData },
    })
    if (!pending.ok || pending.body?.status === 'fail') {
      message.value = pending.body?.message ?? pending.error ?? `HTTP ${pending.status}`
      return
    }
    await loadPlans()
  } catch (err) {
    message.value = publicErrorText(err)
  } finally {
    rewardLoading.value = false
  }
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

    <v-card v-if="appState.capabilities?.admob && appState.planRewardAdEnabled" class="glass-card pa-4 mb-4">
      <div class="section-row">
        <div>
          <p class="eyebrow">{{ t('plan_reward_ad_title') }}</p>
          <p class="muted">观看 AdMob 激励广告后提交服务器验证。</p>
        </div>
        <v-btn color="primary" :loading="rewardLoading" @click="watchPlanRewardAd">{{ t('reward_watch') }}</v-btn>
      </div>
      <div v-if="appState.adRewardLogs.filter((log) => log.scene === 'plan').length" class="stack mt-3">
        <div
          v-for="log in appState.adRewardLogs.filter((item) => item.scene === 'plan').slice(0, 3)"
          :key="log.id || log.transactionId"
          class="glass-chip row-chip"
        >
          <span>{{ log.rewardContent || rewardStatusText(log.status) }}</span>
          <strong>{{ rewardStatusText(log.status) }}</strong>
        </div>
      </div>
    </v-card>

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
