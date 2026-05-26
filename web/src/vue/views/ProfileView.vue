<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showRewardedAd } from '../../api/system'
import { xboardRequest } from '../../api/xboard'
import { formatMoney, formatUnixDate, numericValue, publicErrorText } from '../../format'
import { enabled, parseRewardLogs, rewardStatusText } from '../../reward'
import { clearSession } from '../../store/persist'
import { appState, store, t } from '../state'
import type { InviteItem } from '../../store'

interface XboardBody {
  data?: unknown
  message?: string
  status?: string
}

const router = useRouter()
const error = ref('')
const loading = ref(false)
const rewardLoading = ref(false)
const copied = ref('')

function inviteRows(value: unknown): InviteItem[] {
  const data = Array.isArray(value)
    ? value
    : value && typeof value === 'object' && Array.isArray((value as Record<string, unknown>).codes)
      ? (value as Record<string, unknown>).codes as unknown[]
      : value && typeof value === 'object' && Array.isArray((value as Record<string, unknown>).codes_list)
        ? (value as Record<string, unknown>).codes_list as unknown[]
      : []
  return data.map((row) => {
    const item = row as Record<string, unknown>
    return { code: String(item.code ?? ''), status: numericValue(item.status) }
  }).filter((item) => item.code)
}

async function loadProfile() {
  loading.value = true
  error.value = ''
  try {
    const [info, config, invites] = await Promise.all([
      xboardRequest<XboardBody>('user_info', { baseUrl: appState.baseUrl, authData: appState.authData }),
      xboardRequest<XboardBody>('user_config', { baseUrl: appState.baseUrl, authData: appState.authData }),
      xboardRequest<XboardBody>('invite_fetch', { baseUrl: appState.baseUrl, authData: appState.authData }),
    ])
    if (!info.ok) {
      error.value = info.body?.message ?? info.error ?? `HTTP ${info.status}`
      return
    }
    const data = info.body?.data && typeof info.body.data === 'object' ? info.body.data as Record<string, unknown> : {}
    store().setProfile({
      balance: numericValue(data.balance),
      commissionBalance: numericValue(data.commission_balance),
    })
    if (config.ok && config.body?.data && typeof config.body.data === 'object') {
      const configData = config.body.data as Record<string, unknown>
      store().setProfile({
        currencySymbol: String(configData.currency_symbol ?? configData.currency ?? '¥'),
        currencyUnit: String(configData.currency_unit ?? configData.currency ?? ''),
        inviteForce: enabled(configData.invite_force),
        inviteCommissionRate: numericValue(configData.commission_rate),
        inviteCommissionBalance: numericValue(configData.invite_commission_balance),
      })
    }
    if (invites.ok) store().setInvites(inviteRows(invites.body?.data))
    await loadRewardConfig()
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

async function loadRewardConfig() {
  if (!appState.capabilities?.admob) {
    store().setRewardLogs([])
    store().setProfile({ paymentEnabled: true })
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

async function createInvite() {
  const response = await xboardRequest<XboardBody>('invite_save', { baseUrl: appState.baseUrl, authData: appState.authData })
  if (!response.ok) {
    error.value = response.body?.message ?? response.error ?? `HTTP ${response.status}`
    return
  }
  await loadProfile()
}

async function copyCode(code: string) {
  try {
    await navigator.clipboard.writeText(code)
    copied.value = code
    window.setTimeout(() => {
      if (copied.value === code) copied.value = ''
    }, 1500)
  } catch (err) {
    error.value = publicErrorText(err)
  }
}

async function watchPointsRewardAd() {
  rewardLoading.value = true
  error.value = ''
  try {
    await showRewardedAd({
      adUnitId: appState.pointsRewardedAdUnitId,
      userId: appState.pointsRewardSsvUserId,
      customData: appState.pointsRewardSsvCustomData,
    })
    const pending = await xboardRequest<XboardBody>('xbclient_reward_pending', {
      baseUrl: appState.baseUrl,
      authData: appState.authData,
      params: { custom_data: appState.pointsRewardSsvCustomData },
    })
    if (!pending.ok || pending.body?.status === 'fail') {
      error.value = pending.body?.message ?? pending.error ?? `HTTP ${pending.status}`
      return
    }
    await loadProfile()
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    rewardLoading.value = false
  }
}

async function logout() {
  store().reset()
  await clearSession()
  await router.replace('/login')
}

onMounted(loadProfile)
</script>

<template>
  <section class="liquid-page">
    <header class="liquid-header">
      <div>
        <p class="eyebrow">{{ appState.email || '未登录' }}</p>
        <h1>{{ t('nav_profile') }}</h1>
      </div>
      <div class="header-actions">
        <v-btn class="glass-button" @click="router.push('/tickets')">{{ t('nav_services') }}</v-btn>
        <v-btn class="glass-button" @click="router.push('/settings')">{{ t('settings_button') }}</v-btn>
        <v-btn class="glass-button danger" @click="logout">{{ t('logout') }}</v-btn>
      </div>
    </header>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <v-card class="glass-card profile-balance pa-5">
      <p class="eyebrow">{{ t('balance') }}</p>
      <h2>{{ formatMoney(appState.balance, appState.currencySymbol || '¥', appState.currencyUnit) }}</h2>
      <p class="muted">{{ t('commission_balance') }}：{{ formatMoney(appState.commissionBalance, appState.currencySymbol || '¥', appState.currencyUnit) }}</p>
      <p v-if="appState.subscription.summary" class="muted mt-3">{{ appState.subscription.summary }}</p>
    </v-card>

    <v-card v-if="appState.capabilities?.admob && appState.pointsRewardAdEnabled" class="glass-card pa-4 mt-4">
      <div class="section-row">
        <div>
          <p class="eyebrow">{{ t('points_reward_ad_title') }}</p>
          <p class="muted">观看 AdMob 激励广告后提交服务器验证。</p>
        </div>
        <v-btn color="primary" :loading="rewardLoading" @click="watchPointsRewardAd">{{ t('reward_watch') }}</v-btn>
      </div>
      <div v-if="appState.adRewardLogs.filter((log) => log.scene === 'points').length" class="stack mt-3">
        <div
          v-for="log in appState.adRewardLogs.filter((item) => item.scene === 'points').slice(0, 3)"
          :key="log.id || log.transactionId"
          class="glass-chip"
        >
          <strong>{{ log.rewardContent || rewardStatusText(log.status) }}</strong>
          <span v-if="log.createdAt > 0">{{ formatUnixDate(log.createdAt) }}</span>
          <span v-if="log.status === 'failed' && log.error" class="text-error">{{ log.error }}</span>
        </div>
      </div>
    </v-card>

    <v-card v-if="appState.inviteForce || appState.inviteCommissionRate > 0" class="glass-card pa-4 mt-4">
      <div class="section-row">
        <div>
          <p class="eyebrow">{{ t('invites_title') }}</p>
          <p class="muted">{{ t('commission') }} {{ appState.inviteCommissionRate }}%</p>
        </div>
        <v-btn color="primary" :loading="loading" @click="createInvite">{{ t('invite_generate') }}</v-btn>
      </div>
      <p v-if="!appState.invites.length" class="muted">{{ t('invites_empty') }}</p>
      <div v-else class="stack">
        <div v-for="invite in appState.invites" :key="invite.code" class="glass-chip row-chip">
          <span>
            <strong>{{ invite.code }}</strong>
            <small>{{ invite.status === 0 ? t('unused') : t('used') }}</small>
          </span>
          <v-btn size="small" variant="tonal" @click="copyCode(invite.code)">
            {{ copied === invite.code ? t('copied') : t('copy') }}
          </v-btn>
        </div>
      </div>
    </v-card>
  </section>
</template>
