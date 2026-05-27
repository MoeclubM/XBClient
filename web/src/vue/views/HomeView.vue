<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { listen } from '@tauri-apps/api/event'
import { useRouter } from 'vue-router'
import {
  aerionStartSocks,
  aerionStop,
  aerionTestNode,
  subscriptionFetch,
  xboardRequest,
} from '../../api/xboard'
import {
  androidGetVpnState,
  androidStartVpn,
  androidStopVpn,
  parseSocksAddr,
  resolveNodeHost,
  systemProxyClear,
  systemProxySet,
} from '../../api/system'
import {
  aerionNodeWithResolvedHost,
  displayNodeName,
  mergeNodeLists,
  mergeXboardNodeTags,
  rawNodeHost,
  readableNodeTestError,
  targetHostPort,
  toAppNode,
  type RawNode,
} from '../../nodes'
import { formatDuration, formatTrafficBytes, formatUnixDate, numericValue, publicErrorText } from '../../format'
import { appState, store, t } from '../state'
import type { AppNode, NoticeItem } from '../../store'

interface XboardBody {
  data?: unknown
  message?: string
}

interface NoticeFetchBody {
  data?: Array<{ id?: number; title?: string; subject?: string; content?: string; message?: string; created_at?: number }>
}

const router = useRouter()
const loading = ref(false)
const error = ref('')
const connectingIndex = ref<number | null>(null)
const duration = ref(0)
let connectedAt = 0
let durationTimer = 0
let unlistenEvent: (() => void) | null = null

const selectedNodeIndex = computed(() => appState.vpn?.nodeIndex ?? 0)
const selectedNode = computed(() => appState.nodes[selectedNodeIndex.value] || appState.nodes[0])
const progressPercent = computed(() =>
  appState.subscription.trafficTotalBytes > 0
    ? Math.min(100, (appState.subscription.trafficUsedBytes / appState.subscription.trafficTotalBytes) * 100)
    : 0,
)

onMounted(async () => {
  await refresh()
  unlistenEvent = await listen<string>('aerion-event', (event) => {
    try {
      const data = JSON.parse(event.payload) as { session_id?: number; upload_bytes?: number; download_bytes?: number }
      if (typeof data.session_id === 'number') {
        store().updateVpnTraffic(data.session_id, Number(data.upload_bytes ?? 0), Number(data.download_bytes ?? 0))
      }
    } catch (err) {
      console.error('parse Aerion event failed', err)
    }
  })
})

onUnmounted(() => {
  if (durationTimer) window.clearInterval(durationTimer)
  unlistenEvent?.()
})

function extractRows(value: unknown): RawNode[] {
  if (Array.isArray(value)) return value as RawNode[]
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>
    for (const key of ['nodes', 'data', 'list', 'items']) {
      if (Array.isArray(object[key])) return object[key] as RawNode[]
    }
  }
  return []
}

function parseNotices(body: NoticeFetchBody | undefined): NoticeItem[] {
  return (body?.data ?? [])
    .map((row) => ({
      id: Number(row.id ?? 0),
      title: row.title ?? row.subject ?? '',
      content: row.content ?? row.message ?? '',
      createdAt: Number(row.created_at ?? 0),
    }))
    .filter((item) => item.title.trim() || item.content.trim())
}

function responseError(response: { status: number; error?: string; body?: XboardBody }): string {
  return response.body?.message || response.error || `HTTP ${response.status}`
}

function subscriptionState(data: Record<string, unknown>) {
  const used = numericValue(data.u) + numericValue(data.d)
  const total = numericValue(data.transfer_enable)
  const plan = data.plan && typeof data.plan === 'object' ? (data.plan as Record<string, unknown>) : null
  const planName = String(plan?.name ?? '')
  const expiredAt = numericValue(data.expired_at)
  const planId = numericValue(data.plan_id)
  return {
    summary: [
      planName,
      total > 0 ? `${t('used_traffic')} ${formatTrafficBytes(used)} / ${formatTrafficBytes(total)}` : '',
      expiredAt > 0 ? `${t('expires_prefix')} ${formatUnixDate(expiredAt)}` : '',
    ].filter(Boolean).join(' · '),
    blockReason: (planId <= 0 && !plan
      ? 'no_plan'
      : expiredAt > 0 && expiredAt <= Date.now() / 1000
        ? 'expired'
        : total <= 0 || used >= total
          ? 'traffic_exceeded'
          : '') as '' | 'no_plan' | 'expired' | 'traffic_exceeded',
    trafficUsedBytes: used,
    trafficTotalBytes: total,
    planName,
    expiredAt,
  }
}

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const sub = await xboardRequest<XboardBody>('user_subscribe', { baseUrl: appState.baseUrl, authData: appState.authData })
    if (!sub.ok) {
      error.value = responseError(sub)
      return
    }
    const data = sub.body?.data && typeof sub.body.data === 'object' ? sub.body.data as Record<string, unknown> : {}
    const url = String(data.subscribe_url ?? data.subscribeUrl ?? '')
    let list: AppNode[] = []
    const xbclientNodes = await xboardRequest<XboardBody>('xbclient_nodes', { baseUrl: appState.baseUrl, authData: appState.authData })
    if (xbclientNodes.ok) {
      list = extractRows(xbclientNodes.body?.data).map(toAppNode)
    } else if (url) {
      const subscription = await subscriptionFetch(url, 'meta')
      list = (subscription.nodes ?? []).map((node) => toAppNode(node as RawNode))
      const tagRows = await xboardRequest<XboardBody>('nodes', { baseUrl: appState.baseUrl, authData: appState.authData })
      if (tagRows.ok) list = mergeXboardNodeTags(list, extractRows(tagRows.body?.data))
    }
    store().setSubscribe({ subscribeUrl: url, nodes: mergeNodeLists(appState.nodes, list) })
    store().setSubscriptionState(subscriptionState(data))
    const noticeResponse = await xboardRequest<NoticeFetchBody>('notices', { baseUrl: appState.baseUrl, authData: appState.authData })
    if (noticeResponse.ok) store().setNotices(parseNotices(noticeResponse.body))
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

async function resolvedNode(node: AppNode): Promise<unknown> {
  const host = rawNodeHost(node)
  const resolvedHost = await resolveNodeHost(appState.settings.nodeDns, host, appState.buildConfig?.user_agent ?? '')
  return aerionNodeWithResolvedHost(node, resolvedHost)
}

async function testNode(node: AppNode, index: number) {
  const target = targetHostPort(appState.settings.nodeTestTarget)
  const result = await aerionTestNode({
    node: await resolvedNode(node),
    target_host: target.host,
    target_port: target.port,
    target_tls: target.tls,
    timeout_ms: 8000,
  })
  store().setNodeResult(index, result.ok ? { latencyMs: result.latency_ms ?? result.first_latency_ms } : { testError: readableNodeTestError(result.error ?? '', appState.settings.appLanguage) })
}

function startDuration() {
  connectedAt = Date.now()
  duration.value = 0
  if (durationTimer) window.clearInterval(durationTimer)
  durationTimer = window.setInterval(() => { duration.value = Date.now() - connectedAt }, 1000)
}

async function toggleConnection(index = selectedNodeIndex.value) {
  if (appState.vpn) {
    if (appState.capabilities?.vpn) await androidStopVpn()
    else await aerionStop(appState.vpn.sessionId)
    if (appState.settings.autoApplyProxy) await systemProxyClear()
    store().setVpn(null)
    if (durationTimer) window.clearInterval(durationTimer)
    duration.value = 0
    return
  }
  const node = appState.nodes[index]
  if (!node) return
  connectingIndex.value = index
  error.value = ''
  try {
    if (appState.capabilities?.vpn) {
      await androidStartVpn({
        nodeJson: JSON.stringify(await resolvedNode(node)),
        nodesJson: JSON.stringify(appState.nodes.map((item) => JSON.parse(item.rawJson))),
        nodeIndex: index,
        excludedApps: appState.settings.excludedApps,
        allowedApps: appState.settings.allowedApps,
        nodeDns: appState.settings.nodeDns,
        overseasDns: appState.settings.overseasDns,
        directDns: appState.settings.directDns,
        dnsMode: appState.settings.vpnDnsMode,
        virtualDnsPool: appState.settings.virtualDnsPool,
        ipv6Enabled: appState.settings.vpnIpv6Enabled,
      })
      const state = await androidGetVpnState()
      store().setVpn({ sessionId: 0, socksAddr: '', nodeIndex: state.nodeIndex, uploadBytes: 0, downloadBytes: 0 })
    } else {
      const handle = await aerionStartSocks(await resolvedNode(node))
      const parsed = parseSocksAddr(handle.socks_addr)
      if (appState.settings.autoApplyProxy) await systemProxySet(parsed.host, parsed.port)
      store().setVpn({ sessionId: handle.session_id, socksAddr: handle.socks_addr, nodeIndex: index, uploadBytes: 0, downloadBytes: 0 })
    }
    startDuration()
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    connectingIndex.value = null
  }
}
</script>

<template>
  <section class="liquid-page">
    <header class="liquid-header">
      <div>
        <p class="eyebrow">{{ loading ? t('refreshing') : (appState.email || t('logged_out')) }}</p>
        <h1>{{ t('nav_nodes') }}</h1>
      </div>
      <v-btn class="glass-button" :loading="loading" @click="refresh">{{ loading ? t('refreshing') : t('refresh') }}</v-btn>
    </header>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <v-card v-if="appState.subscription.blockReason" class="glass-card pa-4 mb-4">
      <p class="font-weight-bold text-error mb-3">
        {{ appState.subscription.blockReason === 'expired' ? t('subscription_expired') : appState.subscription.blockReason === 'traffic_exceeded' ? t('subscription_traffic_exceeded') : t('subscription_no_plan') }}
      </p>
      <v-btn color="primary" @click="router.push('/plans')">{{ t('go_to_plans') }}</v-btn>
    </v-card>

    <v-card class="glass-card connection-card pa-5">
      <p class="eyebrow">{{ t('section_connection') }}</p>
      <h2>{{ appState.vpn ? t('status_connected') : t('status_disconnected') }}</h2>
      <button class="liquid-orb" :class="{ connected: appState.vpn }" :disabled="connectingIndex !== null || (!appState.vpn && Boolean(selectedNode && !selectedNode.connectSupported))" @click="toggleConnection()">
        {{ connectingIndex !== null ? t('action_connecting') : appState.vpn ? t('action_disconnect') : t('action_connect') }}
      </button>
      <p v-if="selectedNode && !selectedNode.connectSupported" class="muted text-error">{{ t('unsupported_protocol') }}</p>
      <div v-if="appState.vpn" class="metric-grid">
        <div class="glass-chip">
          <span>{{ t('session_duration') }}</span>
          <strong>{{ formatDuration(duration) }}</strong>
        </div>
        <div class="glass-chip">
          <span>{{ t('session_traffic') }}</span>
          <strong>{{ formatTrafficBytes(appState.vpn.uploadBytes + appState.vpn.downloadBytes) }}</strong>
        </div>
      </div>
    </v-card>

    <v-card class="glass-card pa-4 mt-4">
      <p class="eyebrow">{{ t('section_current_node') }}</p>
      <h3>{{ selectedNode ? displayNodeName(selectedNode, selectedNodeIndex) : t('no_nodes') }}</h3>
      <p v-if="selectedNode" class="muted">{{ selectedNode.protocolLabel }} · {{ selectedNode.host }}:{{ selectedNode.port }}</p>
    </v-card>

    <v-card v-if="appState.subscription.trafficTotalBytes > 0" class="glass-card pa-4 mt-4">
      <p class="eyebrow">{{ t('section_traffic') }}</p>
      <v-progress-linear class="my-3" color="primary" height="10" rounded :model-value="progressPercent" />
      <p class="muted">{{ appState.subscription.summary }}</p>
    </v-card>

    <section v-if="appState.notices.length" class="mt-4 stack">
      <p class="eyebrow">{{ t('announcement') }}</p>
      <v-card v-for="notice in appState.notices" :key="notice.id" class="glass-card pa-4">
        <h3>{{ notice.title }}</h3>
        <p class="muted preline">{{ notice.content.replace(/<[^>]+>/g, '') }}</p>
      </v-card>
    </section>

    <v-card class="glass-card pa-4 mt-4">
      <div class="section-row">
        <p class="eyebrow mb-0">{{ t('select_node') }}</p>
        <span class="muted">{{ appState.nodes.length }}</span>
      </div>
      <div class="node-list">
        <div
          v-for="(node, index) in appState.nodes"
          :key="`${node.name}-${index}`"
          class="node-row node-row-action"
          :class="{ active: index === selectedNodeIndex }"
        >
          <button class="node-pick" :disabled="!node.connectSupported" @click="toggleConnection(index)">
            <span>
              <strong>{{ displayNodeName(node, index) }}</strong>
              <small>{{ node.protocolLabel }} · {{ node.host }}{{ node.connectSupported ? '' : ` · ${t('unsupported_protocol')}` }}</small>
            </span>
          </button>
          <span class="node-actions">
            <small v-if="node.latencyMs">{{ node.latencyMs }}ms</small>
            <v-btn size="small" variant="text" @click.stop="testNode(node, index)">{{ t('node_test') }}</v-btn>
          </span>
        </div>
        <p v-if="!loading && !appState.nodes.length" class="muted pa-3">{{ t('no_nodes') }}</p>
      </div>
    </v-card>
  </section>
</template>
