<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { xboardRequest } from '../../api/xboard'
import { formatMoney, numericValue, publicErrorText } from '../../format'
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
const message = ref('')
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
  if (rewardHistory.ok) store().setRewardLogs(parseRewardLogs(rewardHistory.body?.data, appState.settings.appLanguage))
}

async function createInvite() {
  const response = await xboardRequest<XboardBody>('invite_save', { baseUrl: appState.baseUrl, authData: appState.authData })
  if (!response.ok) {
    error.value = response.body?.message ?? response.error ?? `HTTP ${response.status}`
    return
  }
  message.value = t('invite_generated')
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

function formatUnixTime(value: number): string {
  if (value <= 0) return ''
  return new Date(value * 1000).toLocaleString()
}

onMounted(loadProfile)
</script>

<template>
  <section class="liquid-page">
    <!-- Page Header -->
    <div class="page-header">
      <div class="page-header-bar" />
      <div class="page-header-content">
        <p class="muted">{{ appState.email || t('logged_out') }}</p>
        <h1>{{ t('nav_profile') }}</h1>
      </div>
      <div class="d-flex gap-2">
        <v-btn variant="outlined" size="small" @click="router.push('/settings')">
          {{ t('settings_button') }}
        </v-btn>
        <v-btn variant="outlined" size="small" class="glass-button danger" @click="logout">
          {{ t('logout') }}
        </v-btn>
      </div>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="message" color="primary" variant="tonal" class="mb-4">{{ message }}</v-alert>

    <!-- Account Section -->
    <div class="page-section">
      <p class="section-label">{{ t('section_account') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <p class="text-h6 font-weight-bold">
            {{ appState.email || t('status_logged_in') }}
          </p>
          <p class="muted mt-1">
            {{ t('balance') }}：{{ formatMoney(appState.balance, appState.currencySymbol || '¥', appState.currencyUnit) }}
          </p>
          <p class="muted">
            {{ t('commission_balance') }}：{{ formatMoney(appState.commissionBalance, appState.currencySymbol || '¥', appState.currencyUnit) }}
          </p>
          <p v-if="appState.subscription.summary" class="muted mt-2">
            {{ appState.subscription.summary }}
          </p>
          <v-btn color="primary" block class="mt-4" @click="router.push('/settings')">
            {{ t('settings_button') }}
          </v-btn>
          <v-btn variant="outlined" block class="mt-2" @click="logout">
            {{ t('logout') }}
          </v-btn>
        </v-card-text>
      </v-card>
    </div>

    <!-- Points Reward Ad Section -->
    <div v-if="appState.capabilities?.admob && appState.pointsRewardAdEnabled" class="page-section">
      <v-card class="panel-card">
        <v-card-text>
          <div class="d-flex align-center gap-3 mb-4">
            <div
              class="d-flex align-center justify-center rounded-circle flex-shrink-0"
              style="width:50px;height:50px;background:var(--primary-container);color:var(--on-primary-container);"
            >
              <span style="font-size:26px;">🎁</span>
            </div>
            <div>
              <p class="text-body-1 font-weight-bold mb-0">{{ t('points_reward_ad_title') }}</p>
              <p class="text-caption text-medium-emphasis mb-0">{{ t('reward_ad_verify_desc') }}</p>
            </div>
          </div>
          <v-btn variant="tonal" color="primary" block :loading="rewardLoading" @click="watchPointsRewardAd">
            {{ t('reward_watch') }}
          </v-btn>
          <div v-if="appState.adRewardLogs.filter((log) => log.scene === 'points').length" class="mt-4">
            <p class="text-body-2 font-weight-bold mb-2">{{ t('reward_recent') }}</p>
            <div
              v-for="(log, i) in appState.adRewardLogs.filter((item) => item.scene === 'points').slice(0, 3)"
              :key="log.id || log.transactionId"
            >
              <div class="d-flex align-center justify-space-between">
                <div>
                  <p class="text-body-2 mb-0">
                    {{ log.rewardContent || rewardStatusText(log.status, appState.settings.appLanguage) }}
                  </p>
                  <p v-if="log.createdAt > 0" class="text-caption text-medium-emphasis mb-0">
                    {{ formatUnixTime(log.createdAt) }}
                  </p>
                </div>
                <span
                  class="tag-chip"
                  :style="{
                    background: log.status === 'credited' ? 'color-mix(in srgb, var(--primary) 12%, transparent)' : log.status === 'failed' ? 'color-mix(in srgb, var(--error) 12%, transparent)' : 'color-mix(in srgb, var(--tertiary) 12%, transparent)',
                    color: log.status === 'credited' ? 'var(--primary)' : log.status === 'failed' ? 'var(--error)' : 'var(--tertiary)',
                  }"
                >{{ rewardStatusText(log.status, appState.settings.appLanguage) }}</span>
              </div>
              <v-divider v-if="i < Math.min(appState.adRewardLogs.filter((l) => l.scene === 'points').length, 3) - 1" class="my-2" />
            </div>
          </div>
        </v-card-text>
      </v-card>
    </div>

    <!-- Invite Section -->
    <div v-if="appState.inviteForce || appState.inviteCommissionRate > 0" class="page-section">
      <p class="section-label">{{ t('invites_title') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <p class="muted">
            {{ t('commission') }} {{ appState.inviteCommissionRate }}%
            <span v-if="appState.inviteCommissionBalance > 0">
              · {{ formatMoney(appState.inviteCommissionBalance, appState.currencySymbol || '¥', appState.currencyUnit) }}
            </span>
          </p>
          <div v-if="appState.invites.length" class="mt-3 stack">
            <div v-for="invite in appState.invites" :key="invite.code" class="d-flex align-center justify-space-between">
              <div>
                <p class="text-body-1 font-weight-bold mb-0">{{ invite.code }}</p>
                <p class="text-caption text-medium-emphasis mb-0">
                  {{ invite.status === 0 ? t('unused') : t('used') }}
                </p>
              </div>
              <v-btn size="small" variant="tonal" @click="copyCode(invite.code)">
                {{ copied === invite.code ? t('copied') : t('copy') }}
              </v-btn>
            </div>
          </div>
          <p v-else class="muted mt-2">{{ t('invites_empty') }}</p>
          <v-btn color="primary" block class="mt-4" :loading="loading" @click="createInvite">
            {{ t('invite_generate') }}
          </v-btn>
        </v-card-text>
      </v-card>
    </div>
  </section>
</template>
