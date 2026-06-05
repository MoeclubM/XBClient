<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { failureText, field, parseInviteRows } from '../../api/helpers'
import { xboardRequest, type XboardBody } from '../../api/xboard'
import { formatMoney, formatUnixDateTime, numericValue, publicErrorText } from '../../format'
import { appState, store, t } from '../state'

interface InviteDetail {
  id: number
  tradeNo: string
  orderAmount: number
  getAmount: number
  createdAt: number
}

const loading = ref(false)
const error = ref('')
const message = ref('')
const copied = ref('')
const details = ref<InviteDetail[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = 10

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

function applyInviteStats(value: unknown) {
  const data = value as Record<string, unknown>
  const stat = data.stat as unknown[]
  store().setProfile({
    inviteCommissionRate: Math.round(numericValue(stat[3])),
    inviteCommissionBalance: Math.round(numericValue(stat[4])),
  })
}

function detailRows(value: unknown): InviteDetail[] {
  return (value as Array<Record<string, unknown>>).map((item) => ({
    id: Math.round(numericValue(item.id)),
    tradeNo: field(item, 'trade_no'),
    orderAmount: Math.round(numericValue(item.order_amount)),
    getAmount: Math.round(numericValue(item.get_amount)),
    createdAt: Math.round(numericValue(item.created_at)),
  }))
}

async function loadPromotion(page = current.value) {
  loading.value = true
  error.value = ''
  try {
    const [config, invites, inviteDetails] = await Promise.all([
      xboardRequest<XboardBody>('user_config', { baseUrl: appState.baseUrl, authData: appState.authData }),
      xboardRequest<XboardBody>('invite_fetch', { baseUrl: appState.baseUrl, authData: appState.authData }),
      xboardRequest<XboardBody>('invite_details', {
        baseUrl: appState.baseUrl,
        authData: appState.authData,
        params: { current: page, page_size: pageSize },
      }),
    ])
    if (!config.ok) throw new Error(failureText(config))
    if (!invites.ok) throw new Error(failureText(invites))
    if (!inviteDetails.ok) throw new Error(failureText(inviteDetails))

    const configData = config.body.data as Record<string, unknown>
    if (typeof configData.currency_symbol !== 'string') throw new Error('user_config currency_symbol is required')
    if (typeof configData.currency !== 'string') throw new Error('user_config currency is required')
    store().setProfile({
      currencySymbol: configData.currency_symbol,
      currencyUnit: configData.currency,
    })
    store().setInvites(parseInviteRows(invites.body.data))
    applyInviteStats(invites.body.data)
    details.value = detailRows(inviteDetails.body.data)
    total.value = Math.round(numericValue(inviteDetails.body.total))
    current.value = page
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

async function createInvite() {
  error.value = ''
  message.value = ''
  const response = await xboardRequest<XboardBody>('invite_save', { baseUrl: appState.baseUrl, authData: appState.authData })
  const text = failureText(response)
  if (text) {
    error.value = text
    return
  }
  message.value = t('invite_generated')
  await loadPromotion(1)
}

async function transferCommission() {
  error.value = ''
  message.value = ''
  const response = await xboardRequest<XboardBody>('transfer', {
    baseUrl: appState.baseUrl,
    authData: appState.authData,
    params: { transfer_amount: appState.inviteCommissionBalance },
  })
  const text = failureText(response)
  if (text) {
    error.value = text
    return
  }
  message.value = t('transfer_success')
  await loadPromotion(current.value)
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

onMounted(() => loadPromotion())
</script>

<template>
  <section class="liquid-page">
    <div class="page-header">
      <div class="page-header-bar subtitle" />
      <div class="page-header-content">
        <h1>{{ t('nav_promotion') }}</h1>
        <p>{{ t('service_promotion_desc') }}</p>
      </div>
      <v-btn variant="outlined" :loading="loading" @click="loadPromotion(current)">
        {{ loading ? t('refreshing') : t('refresh') }}
      </v-btn>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="message" color="primary" variant="tonal" class="mb-4">{{ message }}</v-alert>

    <div class="metric-grid page-section">
      <div class="metric-cell">
        <span>{{ t('commission') }}</span>
        <strong>{{ appState.inviteCommissionRate }}%</strong>
      </div>
      <div class="metric-cell">
        <span>{{ t('commission_balance') }}</span>
        <strong>{{ formatMoney(appState.inviteCommissionBalance, appState.currencySymbol, appState.currencyUnit) }}</strong>
      </div>
    </div>

    <div class="page-section">
      <p class="section-label">{{ t('invites_title') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <div class="stack">
            <div v-for="invite in appState.invites" :key="invite.code" class="row-chip glass-chip">
              <span>
                <strong>{{ invite.code }}</strong>
                <small>{{ invite.status === 0 ? t('unused') : t('used') }}</small>
              </span>
              <v-btn size="small" variant="tonal" @click="copyCode(invite.code)">
                {{ copied === invite.code ? t('copied') : t('copy') }}
              </v-btn>
            </div>
          </div>
          <p v-if="!loading && !appState.invites.length" class="muted">{{ t('invites_empty') }}</p>
          <div class="d-flex flex-wrap gap-2 mt-4">
            <v-btn color="primary" :loading="loading" @click="createInvite">
              {{ t('invite_generate') }}
            </v-btn>
            <v-btn
              variant="outlined"
              :disabled="appState.inviteCommissionBalance <= 0"
              @click="transferCommission"
            >
              {{ t('transfer_commission') }}
            </v-btn>
          </div>
        </v-card-text>
      </v-card>
    </div>

    <div class="page-section">
      <p class="section-label">{{ t('promotion_orders') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <div class="stack">
            <div v-for="detail in details" :key="detail.id" class="glass-chip">
              <div class="row-chip">
                <span>
                  <strong>{{ detail.tradeNo }}</strong>
                  <small>{{ formatUnixDateTime(detail.createdAt) }}</small>
                </span>
                <span class="glass-badge">
                  {{ formatMoney(detail.getAmount, appState.currencySymbol, appState.currencyUnit) }}
                </span>
              </div>
              <p class="muted">
                {{ t('order_amount') }} {{ formatMoney(detail.orderAmount, appState.currencySymbol, appState.currencyUnit) }}
              </p>
            </div>
          </div>
          <p v-if="!loading && !details.length" class="muted">{{ t('promotion_orders_empty') }}</p>
          <div v-if="totalPages > 1" class="d-flex justify-center mt-4">
            <v-pagination
              :model-value="current"
              :length="totalPages"
              density="comfortable"
              @update:model-value="loadPromotion"
            />
          </div>
        </v-card-text>
      </v-card>
    </div>
  </section>
</template>
