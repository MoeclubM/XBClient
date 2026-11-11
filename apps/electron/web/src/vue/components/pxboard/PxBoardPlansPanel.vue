<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { isPxBoard, openPxBoardPortal, pxboardBatch, pxboardRpc, syncPxBoard } from '../../../api/pxboard'
import { openExternal } from '../../../api/system'
import { formatMoney, publicErrorText } from '../../../format'
import { appState, store, t } from '../../state'

interface PlanPeriod {
  id: string
  label?: string
  kind?: string
  price: number
  durationValue?: number
  durationUnit?: string
}

interface PxPlan {
  id: string
  name: string
  description?: string
  quotaGiB?: number
  unlimited?: boolean
  periods: PlanPeriod[]
}

interface PaymentChannel {
  id: string
  name: string
  enabled?: boolean
}

interface OrderQuote {
  amount: number
  originalAmount?: number
  discount?: number
  surplusAmount?: number
  surplusCredit?: number
  purchaseType?: string
}

interface OrderRecord {
  id: string
  number?: string
  planName?: string
  amount: number
  discount?: number
  surplusAmount?: number
  surplusCredit?: number
  status: string
  expiresAt?: string
}

const loading = ref(false)
const error = ref('')
const message = ref('')
const plans = ref<PxPlan[]>([])
const channels = ref<PaymentChannel[]>([])

const dialogOpen = ref(false)
const selectedPlan = ref<PxPlan | null>(null)
const selectedPeriodId = ref('')
const coupon = ref('')
const quoting = ref(false)
const quoteError = ref('')
const quote = ref<OrderQuote | null>(null)
const createdOrder = ref<OrderRecord | null>(null)
const paymentMethod = ref('wallet')
const submitting = ref(false)
const submitError = ref('')
let quoteTimer = 0
let requestId = ''

function periodLabel(period: PlanPeriod): string {
  if (period.label) return String(period.label)
  if (period.kind === 'reset') return t('price_reset')
  if (period.kind === 'traffic') return '流量包'
  if (period.durationUnit === 'permanent') return '长期'
  return String(period.durationValue || 0) + ' ' + (period.durationUnit === 'month' ? '个月' : '天')
}

const activePeriod = computed(() =>
  selectedPlan.value?.periods.find((item) => item.id === selectedPeriodId.value) || null,
)

const dueAmount = computed(() =>
  Number(createdOrder.value?.amount ?? quote.value?.amount ?? activePeriod.value?.price ?? 0),
)

const insufficientWallet = computed(
  () => dueAmount.value > 0 && paymentMethod.value === 'wallet' && appState.balance < dueAmount.value,
)

function money(cents: number): string {
  return formatMoney(Number(cents || 0), appState.currencySymbol, appState.currencyUnit)
}

async function load() {
  if (!isPxBoard()) return
  loading.value = true
  error.value = ''
  try {
    const calls = [
      { key: 'plans', service: 'commerce', method: 'plans.list', input: { scope: 'mine' } },
      { key: 'wallet', service: 'commerce', method: 'wallet.get' },
      ...(appState.panelExtensions.includes('payments')
        ? [{ key: 'channels', service: 'payments', method: 'channels.list' }]
        : []),
    ]
    const batch = await pxboardBatch(calls)
    if (batch.plans?.status !== 200) {
      throw new Error(batch.plans?.error?.message || '读取套餐失败')
    }
    const rawPlans = Array.isArray(batch.plans.data) ? (batch.plans.data as Array<Record<string, any>>) : []
    plans.value = rawPlans
      .map((row) => ({
        id: String(row.id ?? ''),
        name: String(row.name ?? ''),
        description: typeof row.description === 'string' ? row.description : '',
        quotaGiB: Number(row.quotaGiB ?? 0),
        unlimited: Boolean(row.unlimited),
        periods: Array.isArray(row.periods)
          ? row.periods.map((p: Record<string, any>) => ({
              id: String(p.id ?? ''),
              label: typeof p.label === 'string' ? p.label : undefined,
              kind: typeof p.kind === 'string' ? p.kind : undefined,
              price: Number(p.price ?? 0),
              durationValue: Number(p.durationValue ?? 0),
              durationUnit: typeof p.durationUnit === 'string' ? p.durationUnit : undefined,
            }))
          : [],
      }))
      .filter((plan) => plan.id && plan.periods.length > 0)

    if (batch.wallet?.status === 200 && batch.wallet.data) {
      const w = batch.wallet.data as Record<string, any>
      store().setProfile({
        balance: Number(w.balance ?? 0),
        currencySymbol: String(w.currencySymbol ?? ''),
        currencyUnit: String(w.currencyName ?? ''),
      })
    }
    if (batch.channels?.status === 200 && Array.isArray(batch.channels.data)) {
      channels.value = (batch.channels.data as PaymentChannel[]).filter((c) => c.enabled !== false)
    } else {
      channels.value = []
    }
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

function openCheckout(plan: PxPlan, periodId?: string) {
  selectedPlan.value = plan
  selectedPeriodId.value = periodId || plan.periods[0]?.id || ''
  coupon.value = ''
  quote.value = null
  quoteError.value = ''
  createdOrder.value = null
  submitError.value = ''
  paymentMethod.value = 'wallet'
  requestId = crypto.randomUUID()
  dialogOpen.value = true
  scheduleQuote()
}

function scheduleQuote() {
  if (quoteTimer) window.clearTimeout(quoteTimer)
  if (!dialogOpen.value || createdOrder.value || !selectedPlan.value || !selectedPeriodId.value) return
  quoting.value = true
  quoteError.value = ''
  const planId = selectedPlan.value.id
  const periodId = selectedPeriodId.value
  const couponCode = coupon.value.trim()
  quoteTimer = window.setTimeout(async () => {
    try {
      const res = await pxboardRpc<OrderQuote>('commerce', 'orders.quote', {
        planId,
        periodId,
        coupon: couponCode,
      })
      if (selectedPlan.value?.id === planId && selectedPeriodId.value === periodId) {
        quote.value = res
      }
    } catch (err) {
      quoteError.value = publicErrorText(err)
      quote.value = null
    } finally {
      quoting.value = false
    }
  }, 220)
}

watch([selectedPeriodId, coupon], () => {
  if (!createdOrder.value && dialogOpen.value) {
    requestId = crypto.randomUUID()
    scheduleQuote()
  }
})

async function submitCheckout() {
  if (!selectedPlan.value || submitting.value) return
  submitting.value = true
  submitError.value = ''
  try {
    if (!createdOrder.value) {
      const order = await pxboardRpc<OrderRecord>('commerce', 'orders.create', {
        planId: selectedPlan.value.id,
        periodId: selectedPeriodId.value,
        coupon: coupon.value.trim(),
        requestId,
      })
      createdOrder.value = order
      if (Number(order.amount) > appState.balance && channels.value.length > 0) {
        paymentMethod.value = channels.value[0].id
      }
      return
    }
    if (paymentMethod.value === 'wallet' || dueAmount.value === 0) {
      const paid = await pxboardRpc<OrderRecord>('commerce', 'orders.pay', { id: createdOrder.value.id })
      createdOrder.value = paid
      await syncPxBoard()
      await load()
      dialogOpen.value = false
      message.value = paid.status === 'fulfilled' ? '支付完成，套餐权益已更新。' : '订单已提交处理。'
      return
    }
    const checkout = await pxboardRpc<{ redirectUrl: string }>('payments', 'checkout', {
      orderId: createdOrder.value.id,
      channelId: paymentMethod.value,
    })
    if (!checkout.redirectUrl) throw new Error('支付渠道未返回跳转链接')
    await openExternal(checkout.redirectUrl)
    dialogOpen.value = false
    message.value = '已在浏览器打开支付页面，支付完成后刷新即可同步。'
  } catch (err) {
    submitError.value = publicErrorText(err)
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="liquid-page">
    <div class="page-header">
      <div class="page-header-bar" />
      <div class="page-header-content">
        <p class="muted">{{ t('balance') }}：{{ money(appState.balance) }}</p>
        <h1>{{ t('nav_plans') }}</h1>
      </div>
      <div class="d-flex gap-2">
        <v-btn variant="text" size="small" @click="openPxBoardPortal('/user/plans')">网页版</v-btn>
        <v-btn variant="outlined" size="small" :loading="loading" @click="load">
          {{ loading ? t('refreshing') : t('refresh') }}
        </v-btn>
      </div>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="message" color="primary" variant="tonal" class="mb-4">{{ message }}</v-alert>

    <div class="plans-grid">
      <v-card v-for="plan in plans" :key="plan.id" class="panel-card">
        <v-card-text>
          <div class="d-flex align-center justify-space-between">
            <div>
              <p class="text-h6 font-weight-bold mb-0">{{ plan.name }}</p>
              <p class="muted mt-1">
                {{ t('plan_traffic') }}：{{ plan.unlimited ? '不限量' : (plan.quotaGiB || 0) + ' GB' }}
              </p>
            </div>
          </div>
          <p v-if="plan.description" class="muted mt-2 preline">{{ plan.description }}</p>
          <v-row class="mt-2">
            <v-col v-for="period in plan.periods" :key="period.id" cols="12" sm="6">
              <v-btn
                variant="outlined"
                block
                class="justify-space-between"
                @click="openCheckout(plan, period.id)"
              >
                <span>{{ periodLabel(period) }}</span>
                <strong>{{ money(period.price) }}</strong>
              </v-btn>
            </v-col>
          </v-row>
        </v-card-text>
      </v-card>

      <v-card v-if="!loading && !plans.length" class="panel-card">
        <v-card-text>
          <p class="muted mb-0">{{ t('plans_empty') }}</p>
        </v-card-text>
      </v-card>
    </div>

    <v-dialog v-model="dialogOpen" max-width="480">
      <v-card v-if="selectedPlan" class="panel-card">
        <v-card-title class="text-subtitle-1 font-weight-bold">
          {{ createdOrder ? '支付订单' : selectedPlan.name }}
        </v-card-title>
        <v-card-text>
          <v-alert v-if="submitError" color="error" variant="tonal" class="mb-3">{{ submitError }}</v-alert>
          <v-alert v-if="quoteError" color="error" variant="tonal" class="mb-3">{{ quoteError }}</v-alert>

          <template v-if="!createdOrder">
            <v-select
              v-model="selectedPeriodId"
              label="购买周期"
              :items="selectedPlan.periods.map((p) => ({ value: p.id, title: periodLabel(p) + ' · ' + money(p.price) }))"
              density="comfortable"
            />
            <v-text-field
              v-model="coupon"
              label="优惠码（可选）"
              density="comfortable"
              hide-details="auto"
              class="mt-2"
            />
          </template>

          <div class="mt-3">
            <p v-if="Number(createdOrder?.discount ?? quote?.discount) > 0" class="muted mb-1">
              优惠减免：-{{ money(Number(createdOrder?.discount ?? quote?.discount ?? 0)) }}
            </p>
            <p v-if="Number(createdOrder?.surplusAmount ?? quote?.surplusAmount) > 0" class="muted mb-1">
              原套餐折抵：-{{ money(Number(createdOrder?.surplusAmount ?? quote?.surplusAmount ?? 0)) }}
            </p>
            <p v-if="Number(createdOrder?.surplusCredit ?? quote?.surplusCredit) > 0" class="muted mb-1">
              折抵转入余额：{{ money(Number(createdOrder?.surplusCredit ?? quote?.surplusCredit ?? 0)) }}
            </p>
            <p class="text-body-1 font-weight-bold mb-1">
              应付金额：{{ quoting && !createdOrder ? '计算中…' : money(dueAmount) }}
            </p>
            <p v-if="createdOrder?.number" class="text-caption muted mb-0">
              订单号：{{ createdOrder.number }}
            </p>
          </div>

          <div v-if="createdOrder && dueAmount > 0" class="mt-4">
            <v-radio-group v-model="paymentMethod" hide-details>
              <v-radio :label="'账户余额（' + money(appState.balance) + '）'" value="wallet" />
              <v-radio
                v-for="ch in channels"
                :key="ch.id"
                :label="ch.name"
                :value="ch.id"
              />
            </v-radio-group>
            <p v-if="insufficientWallet" class="text-warning text-caption mt-1 mb-0">
              账户余额不足，请选择在线支付渠道或先前往个人中心充值。
            </p>
          </div>
        </v-card-text>
        <v-card-actions class="justify-end">
          <v-btn variant="text" :disabled="submitting" @click="dialogOpen = false">
            {{ t('panel_cancel') }}
          </v-btn>
          <v-btn
            color="primary"
            :loading="submitting"
            :disabled="(!createdOrder && (quoting || Boolean(quoteError))) || (Boolean(createdOrder) && insufficientWallet)"
            @click="submitCheckout"
          >
            {{ !createdOrder ? '创建订单' : dueAmount === 0 ? '确认开通' : paymentMethod === 'wallet' ? '余额支付' : '在线支付' }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </section>
</template>
