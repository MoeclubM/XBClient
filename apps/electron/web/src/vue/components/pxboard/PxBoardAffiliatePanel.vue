<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { pxboardBatch, pxboardPublicUrl, pxboardRpc, syncPxBoard } from '../../../api/pxboard'
import { formatMoney, publicErrorText } from '../../../format'
import { appState, t } from '../../state'

interface AffiliateSummary {
  availableAmount: number
  pendingAmount: number
  totalAmount: number
  invitedCount: number
  commissionPercent: number
  commissionHoldDays: number
  commissionMode: string
  referralPath: string
}

const loading = ref(false)
const busy = ref(false)
const error = ref('')
const message = ref('')
const tab = ref<'commissions' | 'withdrawals'>('commissions')

const summary = ref<AffiliateSummary | null>(null)
const commissions = ref<Array<Record<string, any>>>([])
const withdrawals = ref<Array<Record<string, any>>>([])

const dialogMethod = ref<'wallet' | 'cash' | null>(null)
const withdrawAmount = ref('')
const payoutAccount = ref('')

const referralUrl = computed(() =>
  summary.value?.referralPath ? pxboardPublicUrl(summary.value.referralPath) : '',
)

function money(cents: number): string {
  return formatMoney(Number(cents || 0), appState.currencySymbol, appState.currencyUnit)
}

function formatIso(value?: string): string {
  if (!value) return '—'
  const ts = Date.parse(value)
  return Number.isFinite(ts) ? new Date(ts).toLocaleString() : value
}

function parseCents(raw: string): number {
  const trimmed = raw.trim()
  if (!/^\d+(\.\d{1,2})?$/.test(trimmed)) throw new Error('金额格式无效，最多保留两位小数')
  const [whole, frac = ''] = trimmed.split('.')
  return Number(whole) * 100 + Number(frac.padEnd(2, '0'))
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const batch = await pxboardBatch([
      { key: 'summary', service: 'affiliate', method: 'summary.get' },
      { key: 'commissions', service: 'affiliate', method: 'commissions.list', input: { mine: true } },
      { key: 'withdrawals', service: 'affiliate', method: 'withdrawals.list', input: { mine: true } },
    ])
    if (batch.summary?.status === 200 && batch.summary.data) {
      const s = batch.summary.data as Record<string, any>
      summary.value = {
        availableAmount: Number(s.availableAmount ?? 0),
        pendingAmount: Number(s.pendingAmount ?? 0),
        totalAmount: Number(s.totalAmount ?? 0),
        invitedCount: Number(s.invitedCount ?? 0),
        commissionPercent: Number(s.commissionPercent ?? 0),
        commissionHoldDays: Number(s.commissionHoldDays ?? 0),
        commissionMode: String(s.commissionMode ?? ''),
        referralPath: String(s.referralPath ?? ''),
      }
    }
    commissions.value = Array.isArray(batch.commissions?.data) ? batch.commissions.data : []
    withdrawals.value = Array.isArray(batch.withdrawals?.data) ? batch.withdrawals.data : []
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

async function copyReferral() {
  if (!referralUrl.value) return
  await navigator.clipboard.writeText(referralUrl.value)
  message.value = t('copied')
}

function openWithdraw(method: 'wallet' | 'cash') {
  dialogMethod.value = method
  withdrawAmount.value = ((summary.value?.availableAmount ?? 0) / 100).toFixed(2)
  payoutAccount.value = ''
}

async function submitWithdraw() {
  if (!dialogMethod.value || busy.value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const cents = parseCents(withdrawAmount.value)
    const min = dialogMethod.value === 'wallet' ? 1 : 100
    if (cents < min || cents > (summary.value?.availableAmount ?? 0)) {
      throw new Error('输入金额超出可用佣金范围')
    }
    await pxboardRpc('affiliate', 'withdrawals.create', {
      amount: cents,
      method: dialogMethod.value,
      requestId: crypto.randomUUID(),
      ...(dialogMethod.value === 'cash' ? { payoutAccount: payoutAccount.value.trim() } : {}),
    })
    dialogMethod.value = null
    message.value = '结算申请已提交'
    await syncPxBoard()
    await load()
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    busy.value = false
  }
}

async function withdrawalAction(id: string, action: 'transfer' | 'cancel') {
  busy.value = true
  error.value = ''
  try {
    await pxboardRpc('affiliate', 'withdrawals.' + action, { id })
    await syncPxBoard()
    await load()
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="liquid-page">
    <div class="page-header">
      <div class="page-header-bar" />
      <div class="page-header-content">
        <h1>{{ t('nav_promotion') }}</h1>
      </div>
      <v-btn variant="outlined" size="small" :loading="loading" @click="load">
        {{ loading ? t('refreshing') : t('refresh') }}
      </v-btn>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="message" color="primary" variant="tonal" class="mb-4">{{ message }}</v-alert>

    <v-card v-if="summary" class="panel-card mb-4">
      <v-card-text>
        <div class="metric-grid">
          <div class="metric-cell">
            <span>可用佣金</span>
            <strong>{{ money(summary.availableAmount) }}</strong>
          </div>
          <div class="metric-cell">
            <span>冻结佣金</span>
            <strong>{{ money(summary.pendingAmount) }}</strong>
          </div>
          <div class="metric-cell">
            <span>累计佣金</span>
            <strong>{{ money(summary.totalAmount) }}</strong>
          </div>
          <div class="metric-cell">
            <span>邀请人数</span>
            <strong>{{ summary.invitedCount }}</strong>
          </div>
        </div>
        <p class="muted text-caption mt-3 mb-2">
          返佣比例 {{ summary.commissionPercent }}% · 冻结 {{ summary.commissionHoldDays }} 天 ·
          {{ summary.commissionMode === 'firstPurchase' ? '首单返佣' : '持续返佣' }}
        </p>
        <div v-if="referralUrl" class="d-flex align-center gap-2 mt-2">
          <v-text-field :model-value="referralUrl" readonly density="compact" hide-details />
          <v-btn variant="tonal" color="primary" @click="copyReferral">{{ t('copy') }}</v-btn>
        </div>
        <div class="d-flex gap-2 mt-3">
          <v-btn
            color="primary"
            variant="tonal"
            :disabled="summary.availableAmount < 1"
            @click="openWithdraw('wallet')"
          >
            转入余额
          </v-btn>
          <v-btn
            variant="outlined"
            :disabled="summary.availableAmount < 100"
            @click="openWithdraw('cash')"
          >
            申请提现
          </v-btn>
        </div>
      </v-card-text>
    </v-card>

    <v-tabs v-model="tab" density="compact" class="mb-3">
      <v-tab value="commissions">佣金记录</v-tab>
      <v-tab value="withdrawals">结算记录</v-tab>
    </v-tabs>

    <div v-if="tab === 'commissions'" class="stack">
      <v-card v-for="row in commissions" :key="String(row.id)" class="panel-card">
        <v-card-text class="d-flex align-center justify-space-between py-3">
          <div>
            <p class="text-body-2 font-weight-bold mb-0">{{ money(Number(row.amount ?? 0)) }}</p>
            <p class="text-caption muted mb-0">{{ formatIso(row.createdAt) }}</p>
          </div>
          <v-chip size="small" variant="tonal">{{ row.status }}</v-chip>
        </v-card-text>
      </v-card>
      <v-card v-if="!commissions.length && !loading" class="panel-card">
        <v-card-text><p class="muted mb-0">暂无佣金记录。</p></v-card-text>
      </v-card>
    </div>

    <div v-else class="stack">
      <v-card v-for="row in withdrawals" :key="String(row.id)" class="panel-card">
        <v-card-text class="d-flex align-center justify-space-between py-3">
          <div>
            <p class="text-body-2 font-weight-bold mb-0">
              {{ money(Number(row.amount ?? 0)) }} · {{ row.method === 'cash' ? '现金提现' : '转入余额' }}
            </p>
            <p class="text-caption muted mb-0">{{ formatIso(row.createdAt) }}</p>
          </div>
          <div class="d-flex align-center gap-2">
            <v-chip size="small" variant="tonal">{{ row.status }}</v-chip>
            <v-btn
              v-if="row.method === 'wallet' && row.status === 'pending'"
              size="x-small"
              variant="tonal"
              :disabled="busy"
              @click="withdrawalAction(String(row.id), 'transfer')"
            >
              转入余额
            </v-btn>
            <v-btn
              v-if="row.method === 'cash' && row.status === 'pending'"
              size="x-small"
              variant="text"
              color="error"
              :disabled="busy"
              @click="withdrawalAction(String(row.id), 'cancel')"
            >
              撤回
            </v-btn>
          </div>
        </v-card-text>
      </v-card>
      <v-card v-if="!withdrawals.length && !loading" class="panel-card">
        <v-card-text><p class="muted mb-0">暂无结算记录。</p></v-card-text>
      </v-card>
    </div>

    <v-dialog :model-value="Boolean(dialogMethod)" max-width="440" @update:model-value="!$event && (dialogMethod = null)">
      <v-card class="panel-card">
        <v-card-title class="text-subtitle-1 font-weight-bold">
          {{ dialogMethod === 'wallet' ? '佣金转入余额' : '申请佣金提现' }}
        </v-card-title>
        <v-card-text>
          <v-text-field v-model="withdrawAmount" label="金额" density="comfortable" />
          <v-textarea
            v-if="dialogMethod === 'cash'"
            v-model="payoutAccount"
            label="收款方式与账户"
            rows="3"
            density="comfortable"
          />
        </v-card-text>
        <v-card-actions class="justify-end">
          <v-btn variant="text" :disabled="busy" @click="dialogMethod = null">{{ t('panel_cancel') }}</v-btn>
          <v-btn
            color="primary"
            :loading="busy"
            :disabled="dialogMethod === 'cash' && !payoutAccount.trim()"
            @click="submitWithdraw"
          >
            {{ t('confirm') }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </section>
</template>
