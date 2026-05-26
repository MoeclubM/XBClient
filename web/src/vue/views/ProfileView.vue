<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { xboardRequest } from '../../api/xboard'
import { formatMoney, numericValue, publicErrorText } from '../../format'
import { clearSession } from '../../store/persist'
import { appState, store, t } from '../state'
import type { InviteItem } from '../../store'

interface XboardBody {
  data?: unknown
  message?: string
}

const router = useRouter()
const error = ref('')
const loading = ref(false)

function inviteRows(value: unknown): InviteItem[] {
  const data = Array.isArray(value)
    ? value
    : value && typeof value === 'object' && Array.isArray((value as Record<string, unknown>).codes)
      ? (value as Record<string, unknown>).codes as unknown[]
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
    const [info, invites] = await Promise.all([
      xboardRequest<XboardBody>('user_info', { baseUrl: appState.baseUrl, authData: appState.authData }),
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
      inviteCommissionRate: numericValue(data.commission_rate),
      inviteCommissionBalance: numericValue(data.commission_balance),
      currencySymbol: String(data.currency_symbol ?? '¥'),
      currencyUnit: String(data.currency_unit ?? ''),
    })
    if (invites.ok) store().setInvites(inviteRows(invites.body?.data))
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

async function createInvite() {
  const response = await xboardRequest<XboardBody>('invite_save', { baseUrl: appState.baseUrl, authData: appState.authData })
  if (!response.ok) {
    error.value = response.body?.message ?? response.error ?? `HTTP ${response.status}`
    return
  }
  await loadProfile()
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

    <v-card class="glass-card pa-4 mt-4">
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
          <strong>{{ invite.code }}</strong>
          <span>{{ invite.status === 0 ? t('unused') : t('used') }}</span>
        </div>
      </div>
    </v-card>
  </section>
</template>
