<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { failureText } from '../../api/helpers'
import { xboardRequest, type XboardBody } from '../../api/xboard'
import { formatMoney, numericValue, publicErrorText } from '../../format'
import { clearSession } from '../../store/persist'
import { appState, store, t } from '../state'

const router = useRouter()
const error = ref('')

async function loadProfile() {
  error.value = ''
  try {
    const [info, config] = await Promise.all([
      xboardRequest<XboardBody>('user_info', { baseUrl: appState.baseUrl, authData: appState.authData }),
      xboardRequest<XboardBody>('user_config', { baseUrl: appState.baseUrl, authData: appState.authData }),
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
    if (typeof configData.currency !== 'string') throw new Error('user_config currency is required')
    store().setProfile({
      currencySymbol: configData.currency_symbol,
      currencyUnit: configData.currency,
      inviteCommissionRate: numericValue(data.commission_rate),
      inviteCommissionBalance: numericValue(data.commission_balance),
    })
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
          <v-btn variant="tonal" block class="mt-4" @click="router.push('/services')">
            {{ t('service_center') }}
          </v-btn>
          <v-btn
            v-if="appState.inviteForce || appState.inviteCommissionRate > 0"
            variant="tonal"
            block
            class="mt-2"
            @click="router.push('/promotion')"
          >
            {{ t('nav_promotion') }}
          </v-btn>
        </v-card-text>
      </v-card>
    </div>
  </section>
</template>
