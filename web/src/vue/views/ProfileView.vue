<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { failureText } from '../../api/helpers'
import { xboardRequest } from '../../api/xboard'
import { formatMoney, numericValue, publicErrorText } from '../../format'
import { enabled } from '../../reward'
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
const copied = ref('')

function inviteRows(value: unknown): InviteItem[] {
  if (!value || typeof value !== 'object' || !Array.isArray((value as Record<string, unknown>).codes)) {
    throw new Error('invite_fetch response missing codes array')
  }
  const data = (value as Record<string, unknown>).codes as unknown[]
  return data.map((row) => {
    const item = row as Record<string, unknown>
    if (typeof item.code !== 'string' || !item.code.trim()) throw new Error('invite code is required')
    return { code: item.code, status: numericValue(item.status) }
  })
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
      error.value = failureText(info)
      return
    }
    if (!info.body?.data || typeof info.body.data !== 'object') throw new Error('user_info response missing data')
    const data = info.body.data as Record<string, unknown>
    store().setProfile({
      balance: numericValue(data.balance),
      commissionBalance: numericValue(data.commission_balance),
    })
    if (!config.ok) throw new Error(failureText(config))
    if (!config.body?.data || typeof config.body.data !== 'object') throw new Error('user_config response missing data')
    const configData = config.body.data as Record<string, unknown>
    if (typeof configData.currency_symbol !== 'string') throw new Error('user_config currency_symbol is required')
    if (typeof configData.currency_unit !== 'string') throw new Error('user_config currency_unit is required')
    store().setProfile({
      currencySymbol: configData.currency_symbol,
      currencyUnit: configData.currency_unit,
      inviteForce: enabled(configData.invite_force),
      inviteCommissionRate: numericValue(configData.commission_rate),
      inviteCommissionBalance: numericValue(configData.invite_commission_balance),
    })
    if (!invites.ok) throw new Error(failureText(invites))
    store().setInvites(inviteRows(invites.body?.data))
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

async function createInvite() {
  const response = await xboardRequest<XboardBody>('invite_save', { baseUrl: appState.baseUrl, authData: appState.authData })
  if (!response.ok) {
    error.value = failureText(response)
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

async function logout() {
  store().reset()
  await clearSession()
  await router.replace('/login')
}

onMounted(loadProfile)
</script>

<template>
  <section class="liquid-page">
    <!-- Page Header -->
    <div class="page-header">
      <div class="page-header-bar" />
      <div class="page-header-content">
        <p class="muted">{{ appState.email }}</p>
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
            {{ appState.email }}
          </p>
          <p class="muted mt-1">
            {{ t('balance') }}：{{ formatMoney(appState.balance, appState.currencySymbol, appState.currencyUnit) }}
          </p>
          <p class="muted">
            {{ t('commission_balance') }}：{{ formatMoney(appState.commissionBalance, appState.currencySymbol, appState.currencyUnit) }}
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

    <!-- Invite Section -->
    <div v-if="appState.inviteForce || appState.inviteCommissionRate > 0" class="page-section">
      <p class="section-label">{{ t('invites_title') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <p class="muted">
            {{ t('commission') }} {{ appState.inviteCommissionRate }}%
            <span v-if="appState.inviteCommissionBalance > 0">
              · {{ formatMoney(appState.inviteCommissionBalance, appState.currencySymbol, appState.currencyUnit) }}
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
