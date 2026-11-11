<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { disconnectDesktopConnection } from '../../../desktop/connection'
import { isDesktopShell } from '../../../platform/shell'
import {
  openPxBoardPortal,
  pxboardBatch,
  pxboardPublicUrl,
  pxboardRpc,
  resetPxBoardProfileRevision,
  syncPxBoard,
  type PxBoardBatchCall,
} from '../../../api/pxboard'
import { openExternal } from '../../../api/system'
import { formatMoney, publicErrorText } from '../../../format'
import { clearSession } from '../../../store/persist'
import { appState, store, t } from '../../state'

const router = useRouter()
const isDesktop = isDesktopShell()
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const message = ref('')
const tab = ref('subscriptions')

const links = ref<Array<Record<string, any>>>([])
const newLinkName = ref('')
const newLinkBindUa = ref(false)

const orders = ref<Array<Record<string, any>>>([])
const ledger = ref<Array<Record<string, any>>>([])
const channels = ref<Array<{ id: string; name: string; enabled?: boolean }>>([])
const rechargeAmount = ref('50')
const selectedChannelId = ref('')

const redeemCode = ref('')
const claims = ref<Array<Record<string, any>>>([])

const expireReminder = ref(true)
const trafficReminder = ref(true)
const expireEnabled = ref(false)
const trafficEnabled = ref(false)

const currentPassword = ref('')
const mfaCode = ref('')
const nextPassword = ref('')

const hasSubscriptions = computed(() => appState.panelExtensions.includes('subscriptions'))
const hasCommerce = computed(() => appState.panelExtensions.includes('commerce'))
const hasPayments = computed(() => appState.panelExtensions.includes('payments'))
const hasRedeem = computed(() => appState.panelExtensions.includes('redeem'))
const hasAccess = computed(() => appState.panelExtensions.includes('access'))
const hasSupport = computed(() => appState.panelExtensions.includes('support'))
const hasAffiliate = computed(() => appState.panelExtensions.includes('affiliate'))

function money(cents: number): string {
  return formatMoney(Number(cents || 0), appState.currencySymbol, appState.currencyUnit)
}

function formatIso(value?: string): string {
  if (!value) return '—'
  const ts = Date.parse(value)
  return Number.isFinite(ts) ? new Date(ts).toLocaleString() : value
}

function orderStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    pending: '待支付',
    paid: '开通中',
    fulfilled: '已完成',
    credited: '已折抵',
    canceled: '已取消',
    refunded: '已退款',
    refunding: '退款中',
  }
  return labels[status] || status
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    await syncPxBoard()
    const calls: PxBoardBatchCall[] = []
    if (hasSubscriptions.value) {
      calls.push({ key: 'links', service: 'subscriptions', method: 'links.list', input: { mine: true } })
    }
    if (hasCommerce.value) {
      calls.push(
        { key: 'orders', service: 'commerce', method: 'orders.list', input: { mine: true } },
        { key: 'ledger', service: 'commerce', method: 'ledger.list', input: { mine: true } },
      )
    }
    if (hasPayments.value) {
      calls.push({ key: 'channels', service: 'payments', method: 'channels.list' })
    }
    if (hasRedeem.value) {
      calls.push({ key: 'claims', service: 'redeem', method: 'claims.list', input: { mine: true } })
    }
    if (hasAccess.value) {
      calls.push({ key: 'prefs', service: 'access', method: 'preferences.get' })
    }
    if (!calls.length) {
      tab.value = 'security'
      return
    }
    const batch = await pxboardBatch(calls)
    if (Array.isArray(batch.links?.data)) links.value = batch.links.data
    if (Array.isArray(batch.orders?.data)) orders.value = batch.orders.data
    if (Array.isArray(batch.ledger?.data)) ledger.value = batch.ledger.data
    if (Array.isArray(batch.channels?.data)) {
      channels.value = (batch.channels.data as Array<{ id: string; name: string; enabled?: boolean }>).filter(
        (item) => item.enabled !== false,
      )
      if (!selectedChannelId.value && channels.value.length > 0) {
        selectedChannelId.value = channels.value[0].id
      }
    }
    if (Array.isArray(batch.claims?.data)) claims.value = batch.claims.data
    if (batch.prefs?.status === 200 && batch.prefs.data) {
      const p = batch.prefs.data as Record<string, any>
      expireReminder.value = Boolean(p.expireReminder)
      trafficReminder.value = Boolean(p.trafficReminder)
      expireEnabled.value = Boolean(p.expireEnabled)
      trafficEnabled.value = Boolean(p.trafficEnabled)
    }
    if (!hasSubscriptions.value && tab.value === 'subscriptions') {
      tab.value = hasCommerce.value ? 'billing' : hasRedeem.value ? 'redeem' : 'security'
    }
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

async function copyText(text: string) {
  await navigator.clipboard.writeText(text)
  message.value = t('copied')
}

async function createLink() {
  busy.value = true
  error.value = ''
  try {
    await pxboardRpc('subscriptions', 'links.create', {
      name: newLinkName.value.trim(),
      bindUserAgent: newLinkBindUa.value,
    })
    newLinkName.value = ''
    newLinkBindUa.value = false
    await load()
    message.value = '订阅链接已创建'
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    busy.value = false
  }
}

async function mutateLink(id: string, action: 'rotate' | 'delete') {
  busy.value = true
  error.value = ''
  try {
    await pxboardRpc('subscriptions', 'links.' + action, { id })
    await load()
    message.value = action === 'rotate' ? '订阅链接已重置' : '订阅链接已删除'
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    busy.value = false
  }
}

async function submitRecharge() {
  if (!selectedChannelId.value || busy.value) return
  const numeric = Number(rechargeAmount.value)
  if (!Number.isFinite(numeric) || numeric <= 0) {
    error.value = '请输入有效充值金额'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const order = await pxboardRpc<{ id: string }>('commerce', 'orders.create', {
      kind: 'recharge',
      amount: Math.round(numeric * 100),
      requestId: crypto.randomUUID(),
    })
    const checkout = await pxboardRpc<{ redirectUrl: string }>('payments', 'checkout', {
      orderId: order.id,
      channelId: selectedChannelId.value,
    })
    await openExternal(checkout.redirectUrl)
    message.value = '已打开在线充值页面'
    await load()
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    busy.value = false
  }
}

async function payPendingOrder(order: Record<string, any>, useWallet: boolean) {
  busy.value = true
  error.value = ''
  try {
    if (useWallet || Number(order.amount) === 0) {
      await pxboardRpc('commerce', 'orders.pay', { id: order.id })
      message.value = '订单已使用余额支付'
    } else {
      if (!selectedChannelId.value) throw new Error('暂无可用在线支付渠道')
      const res = await pxboardRpc<{ redirectUrl: string }>('payments', 'checkout', {
        orderId: order.id,
        channelId: selectedChannelId.value,
      })
      await openExternal(res.redirectUrl)
      message.value = '已打开在线支付页面'
    }
    await load()
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    busy.value = false
  }
}

async function cancelOrder(id: string) {
  busy.value = true
  error.value = ''
  try {
    await pxboardRpc('commerce', 'orders.cancel', { id })
    await load()
    message.value = '订单已取消'
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    busy.value = false
  }
}

async function submitRedeem() {
  if (!redeemCode.value.trim() || busy.value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const res = await pxboardRpc<{ status: string; message?: string }>('redeem', 'redeem.create', {
      code: redeemCode.value.trim(),
      requestId: crypto.randomUUID(),
    })
    redeemCode.value = ''
    message.value = res.message || (res.status === 'redeemed' ? '兑换成功' : '兑换已登记，正在处理')
    await load()
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    busy.value = false
  }
}

async function saveReminders() {
  busy.value = true
  error.value = ''
  try {
    await pxboardRpc('access', 'preferences.update', {
      expireReminder: expireReminder.value,
      trafficReminder: trafficReminder.value,
    })
    message.value = '提醒设置已保存'
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    busy.value = false
  }
}

async function updatePassword() {
  if (!currentPassword.value || nextPassword.value.length < 10 || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const payload: Record<string, unknown> = {
      currentPassword: currentPassword.value,
      password: nextPassword.value,
    }
    if (mfaCode.value.trim()) payload.code = mfaCode.value.trim()
    await pxboardRpc('core', 'account.changePassword', payload)
    await logout()
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    busy.value = false
  }
}

async function logout() {
  try {
    await disconnectDesktopConnection()
    try {
      await pxboardRpc('core', 'auth.logout', {})
    } catch {}
    resetPxBoardProfileRevision()
    store().reset()
    await clearSession()
    await router.replace('/login')
  } catch (err) {
    error.value = publicErrorText(err)
  }
}

onMounted(load)
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
        <v-btn variant="text" size="small" @click="openPxBoardPortal('/user')">网页版</v-btn>
        <v-btn variant="outlined" size="small" :loading="loading" @click="load">
          {{ loading ? t('refreshing') : t('refresh') }}
        </v-btn>
        <v-btn variant="outlined" color="error" size="small" @click="logout">
          {{ t('logout') }}
        </v-btn>
      </div>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="message" color="primary" variant="tonal" class="mb-4">{{ message }}</v-alert>

    <v-card class="panel-card mb-4">
      <v-card-text>
        <div class="metric-grid">
          <div class="metric-cell">
            <span>{{ t('section_traffic') }}</span>
            <strong>{{ appState.subscription.summary || '—' }}</strong>
          </div>
          <div v-if="hasCommerce" class="metric-cell">
            <span>{{ t('balance') }}</span>
            <strong>{{ money(appState.balance) }}</strong>
          </div>
          <div v-if="hasAffiliate" class="metric-cell">
            <span>{{ t('commission_balance') }}</span>
            <strong>{{ money(appState.commissionBalance) }}</strong>
          </div>
        </div>
        <div v-if="!isDesktop && (hasSupport || hasAffiliate)" class="d-flex gap-2 mt-3">
          <v-btn v-if="hasSupport" variant="tonal" size="small" @click="router.push('/tickets')">
            {{ t('ticket_center') }}
          </v-btn>
          <v-btn v-if="hasAffiliate" variant="tonal" size="small" @click="router.push('/promotion')">
            {{ t('nav_promotion') }}
          </v-btn>
        </div>
      </v-card-text>
    </v-card>

    <v-tabs v-model="tab" density="compact" class="mb-4">
      <v-tab v-if="hasSubscriptions" value="subscriptions">订阅链接</v-tab>
      <v-tab v-if="hasCommerce" value="billing">余额与订单</v-tab>
      <v-tab v-if="hasRedeem" value="redeem">兑换码</v-tab>
      <v-tab value="security">安全与提醒</v-tab>
    </v-tabs>

    <div v-if="tab === 'subscriptions' && hasSubscriptions" class="stack">
      <v-card class="panel-card">
        <v-card-text>
          <div class="d-flex align-center gap-2 flex-wrap">
            <v-text-field
              v-model="newLinkName"
              label="新订阅名称（如：电脑 / 手机）"
              density="compact"
              hide-details
              style="min-width: 200px; flex: 1"
            />
            <v-checkbox
              v-model="newLinkBindUa"
              label="绑定首次客户端"
              density="compact"
              hide-details
            />
            <v-btn color="primary" :loading="busy" @click="createLink">创建链接</v-btn>
          </div>
        </v-card-text>
      </v-card>

      <v-card v-for="link in links" :key="String(link.id)" class="panel-card">
        <v-card-text>
          <div class="d-flex align-center justify-space-between mb-2">
            <div>
              <strong>{{ link.name || '默认订阅' }}</strong>
              <span v-if="link.boundClient" class="text-caption muted ms-2">已绑定 {{ link.boundClient }}</span>
            </div>
            <div class="d-flex gap-1">
              <v-btn size="x-small" variant="text" :disabled="busy" @click="mutateLink(String(link.id), 'rotate')">
                重置
              </v-btn>
              <v-btn
                size="x-small"
                variant="text"
                color="error"
                :disabled="busy"
                @click="mutateLink(String(link.id), 'delete')"
              >
                删除
              </v-btn>
            </div>
          </div>
          <div v-if="link.shareUrl" class="d-flex align-center gap-2">
            <v-text-field :model-value="pxboardPublicUrl(String(link.shareUrl))" readonly density="compact" hide-details />
            <v-btn variant="tonal" color="primary" @click="copyText(pxboardPublicUrl(String(link.shareUrl)))">
              {{ t('copy') }}
            </v-btn>
          </div>
          <div v-else class="d-flex align-center justify-space-between">
            <span class="text-caption muted">此链接需先完成人机验证后显示地址</span>
            <v-btn size="small" variant="tonal" @click="openPxBoardPortal('/user/subscriptions')">前往验证</v-btn>
          </div>
        </v-card-text>
      </v-card>
    </div>

    <div v-else-if="tab === 'billing' && hasCommerce" class="stack">
      <v-card v-if="channels.length" class="panel-card">
        <v-card-text>
          <div class="d-flex align-center gap-2 flex-wrap">
            <v-text-field
              v-model="rechargeAmount"
              label="充值金额"
              type="number"
              density="compact"
              hide-details
              style="max-width: 160px"
            />
            <v-select
              v-model="selectedChannelId"
              label="支付渠道"
              :items="channels.map((c) => ({ value: c.id, title: c.name }))"
              density="compact"
              hide-details
              style="min-width: 180px; flex: 1"
            />
            <v-btn color="primary" :loading="busy" @click="submitRecharge">在线充值</v-btn>
          </div>
        </v-card-text>
      </v-card>

      <p class="section-label mb-0">订单记录</p>
      <v-card v-for="order in orders" :key="String(order.id)" class="panel-card">
        <v-card-text class="d-flex align-center justify-space-between flex-wrap gap-2 py-3">
          <div>
            <p class="text-body-2 font-weight-bold mb-0">
              {{ order.planName || '余额充值' }} · {{ money(Number(order.amount ?? 0)) }}
            </p>
            <p class="text-caption muted mb-0">{{ order.number }} · {{ formatIso(order.createdAt) }}</p>
          </div>
          <div class="d-flex align-center gap-2">
            <v-chip size="small" variant="tonal">{{ orderStatusLabel(String(order.status ?? '')) }}</v-chip>
            <template v-if="order.status === 'pending'">
              <v-btn
                v-if="order.kind !== 'recharge' && appState.balance >= Number(order.amount ?? 0)"
                size="x-small"
                color="primary"
                variant="tonal"
                :disabled="busy"
                @click="payPendingOrder(order, true)"
              >
                余额支付
              </v-btn>
              <v-btn
                v-if="channels.length > 0 && Number(order.amount ?? 0) > 0"
                size="x-small"
                color="primary"
                variant="outlined"
                :disabled="busy"
                @click="payPendingOrder(order, false)"
              >
                在线支付
              </v-btn>
              <v-btn size="x-small" variant="text" color="error" :disabled="busy" @click="cancelOrder(String(order.id))">
                取消
              </v-btn>
            </template>
          </div>
        </v-card-text>
      </v-card>
      <v-card v-if="!orders.length && !loading" class="panel-card">
        <v-card-text><p class="muted mb-0">暂无订单记录。</p></v-card-text>
      </v-card>

      <p v-if="ledger.length" class="section-label mb-0 mt-2">余额流水</p>
      <v-card v-for="row in ledger.slice(0, 15)" :key="String(row.id)" class="panel-card">
        <v-card-text class="d-flex align-center justify-space-between py-2">
          <span class="text-body-2">{{ row.reason || '账务变动' }}</span>
          <span class="text-body-2 font-weight-bold">
            {{ Number(row.amount ?? 0) > 0 ? '+' : '' }}{{ money(Number(row.amount ?? 0)) }}
          </span>
        </v-card-text>
      </v-card>
    </div>

    <div v-else-if="tab === 'redeem' && hasRedeem" class="stack">
      <v-card class="panel-card">
        <v-card-text class="d-flex align-center gap-2">
          <v-text-field v-model="redeemCode" label="输入兑换码" density="compact" hide-details />
          <v-btn color="primary" :loading="busy" :disabled="!redeemCode.trim()" @click="submitRedeem">
            兑换
          </v-btn>
        </v-card-text>
      </v-card>

      <v-card v-for="claim in claims" :key="String(claim.id)" class="panel-card">
        <v-card-text class="d-flex align-center justify-space-between py-3">
          <div>
            <p class="text-body-2 font-weight-bold mb-0">{{ claim.name }} · {{ claim.summary }}</p>
            <p class="text-caption muted mb-0">{{ formatIso(claim.redeemedAt || claim.createdAt) }}</p>
          </div>
          <v-chip size="small" variant="tonal" :color="claim.status === 'redeemed' ? 'primary' : undefined">
            {{ claim.status === 'redeemed' ? '已兑换' : '处理中' }}
          </v-chip>
        </v-card-text>
      </v-card>
      <v-card v-if="!claims.length && !loading" class="panel-card">
        <v-card-text><p class="muted mb-0">暂无兑换记录。</p></v-card-text>
      </v-card>
    </div>

    <div v-else class="stack">
      <v-card v-if="hasAccess && (expireEnabled || trafficEnabled)" class="panel-card">
        <v-card-text>
          <p class="text-subtitle-2 font-weight-bold mb-2">邮件提醒</p>
          <v-switch
            v-if="expireEnabled"
            v-model="expireReminder"
            label="套餐到期前邮件提醒"
            color="primary"
            density="compact"
            hide-details
          />
          <v-switch
            v-if="trafficEnabled"
            v-model="trafficReminder"
            label="流量不足邮件提醒"
            color="primary"
            density="compact"
            hide-details
          />
          <v-btn color="primary" variant="tonal" size="small" class="mt-3" :loading="busy" @click="saveReminders">
            保存提醒偏好
          </v-btn>
        </v-card-text>
      </v-card>

      <v-card class="panel-card">
        <v-card-text>
          <p class="text-subtitle-2 font-weight-bold mb-3">修改登录密码</p>
          <v-text-field
            v-model="currentPassword"
            label="当前密码"
            type="password"
            density="comfortable"
          />
          <v-text-field
            v-model="mfaCode"
            label="两步验证码（已启用时填写）"
            density="comfortable"
          />
          <v-text-field
            v-model="nextPassword"
            label="新密码（至少 10 位）"
            type="password"
            density="comfortable"
          />
          <div class="d-flex justify-space-between align-center mt-2">
            <v-btn variant="text" size="small" @click="openPxBoardPortal('/user/account')">
              两步验证 / 第三方绑定
            </v-btn>
            <v-btn
              color="primary"
              :loading="busy"
              :disabled="!currentPassword || nextPassword.length < 10"
              @click="updatePassword"
            >
              更新密码
            </v-btn>
          </div>
        </v-card-text>
      </v-card>
    </div>
  </section>
</template>
