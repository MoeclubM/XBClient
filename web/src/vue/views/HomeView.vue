<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  aerionStartSocks,
  aerionStartVpn,
  aerionStop,
  aerionStopVpn,
} from '../../api/xboard'
import { applyDesktopConnection, isDesktopConnectionShell } from '../../desktop/connection'
import { onAeronEvent, reportVpnSession } from '../../platform/electron'
import {
  parseSocksAddr,
  resolveAppNode,
  systemProxyClear,
  systemProxySet,
} from '../../api/system'
import DesktopConnectionPanel from '../components/DesktopConnectionPanel.vue'
import SubscriptionBlockedPanel from '../components/SubscriptionBlockedPanel.vue'
import { displayNodeName, dnsAddressForVpn } from '../../nodes'
import { formatDuration, formatTrafficBytes, publicErrorText } from '../../format'
import { syncSubscription } from '../../subscription-sync'
import { appState, store, t } from '../state'

const router = useRouter()
const loading = ref(false)
const error = ref('')
const connectingIndex = ref<number | null>(null)
const duration = ref(0)
let connectedAt = 0
let durationTimer = 0
let unlistenEvent: (() => void) | null = null

const selectedNodeIndex = computed(() => appState.vpn?.nodeIndex ?? appState.preferredNodeIndex)
const selectedNode = computed(() => appState.nodes[selectedNodeIndex.value] || appState.nodes[0])
const progressPercent = computed(() =>
  appState.subscription.trafficTotalBytes > 0
    ? Math.min(100, (appState.subscription.trafficUsedBytes / appState.subscription.trafficTotalBytes) * 100)
    : 0,
)

onMounted(async () => {
  await refresh()
  unlistenEvent = onAeronEvent((payload) => {
    try {
      const data = JSON.parse(payload) as {
        type?: string
        wrapper_session_id?: number
        session_id?: number
        upload_bytes?: number
        download_bytes?: number
      }
      if (data.type !== 'traffic_recorded') return
      const sessionId = data.wrapper_session_id ?? data.session_id
      if (typeof sessionId !== 'number' || appState.vpn?.sessionId !== sessionId) return
      store().updateVpnTraffic(
        sessionId,
        Number(data.upload_bytes ?? 0),
        Number(data.download_bytes ?? 0),
      )
    } catch (err) {
      console.error('parse Aerion event failed', err)
    }
  })
})

onUnmounted(() => {
  if (durationTimer) window.clearInterval(durationTimer)
  unlistenEvent?.()
})

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const message = await syncSubscription()
    if (message) error.value = message
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
    if (isDesktopConnectionShell()) {
      const message = await applyDesktopConnection()
      if (message) error.value = message
    }
  }
}

function startDuration() {
  connectedAt = Date.now()
  duration.value = 0
  if (durationTimer) window.clearInterval(durationTimer)
  durationTimer = window.setInterval(() => { duration.value = Date.now() - connectedAt }, 1000)
}

async function toggleConnection(index = selectedNodeIndex.value) {
  const useTun = appState.capabilities?.vpn === true
  if (appState.vpn) {
    if (useTun) await aerionStopVpn(appState.vpn.sessionId)
    else {
      await aerionStop(appState.vpn.sessionId)
      if (appState.settings.autoApplyProxy || appState.systemProxyActive) {
        await systemProxyClear()
        store().setSystemProxyActive(false)
      }
    }
    store().setVpn(null)
    if (useTun) await reportVpnSession(null)
    if (durationTimer) window.clearInterval(durationTimer)
    duration.value = 0
    return
  }
  const node = appState.nodes[index]
  if (!node) return
  connectingIndex.value = index
  error.value = ''
  try {
    const resolved = await resolveAppNode(node, appState.settings.nodeDns, appState.buildConfig?.user_agent ?? '')
    if (useTun) {
      const dnsMode = appState.settings.vpnDnsMode
      const dns_addr = dnsAddressForVpn(
        dnsMode === 'direct' ? appState.settings.directDns : appState.settings.overseasDns,
      )
      const handle = await aerionStartVpn({
        node: resolved,
        mtu: 1500,
        dns: dnsMode,
        dns_addr,
        virtual_dns_pool: appState.settings.virtualDnsPool,
        ipv6: appState.settings.vpnIpv6Enabled,
      })
      store().setPreferredNodeIndex(index)
      store().setVpn({
        sessionId: handle.session_id,
        socksAddr: '',
        nodeIndex: index,
        uploadBytes: 0,
        downloadBytes: 0,
      })
      await reportVpnSession(handle.session_id)
    } else {
      const handle = await aerionStartSocks(resolved)
      const parsed = parseSocksAddr(handle.socks_addr)
      if (appState.settings.autoApplyProxy) {
        await systemProxySet(parsed.host, parsed.port)
        store().setSystemProxyActive(true)
      }
      store().setPreferredNodeIndex(index)
      store().setVpn({
        sessionId: handle.session_id,
        socksAddr: handle.socks_addr,
        nodeIndex: index,
        uploadBytes: 0,
        downloadBytes: 0,
      })
    }
    startDuration()
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    connectingIndex.value = null
  }
}

function stripHtml(html: string): string {
  return html.replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').trim()
}

function formatUnixTime(value: number): string {
  if (value <= 0) return ''
  return new Date(value * 1000).toLocaleString()
}
</script>

<template>
  <section class="liquid-page">
    <!-- Page Header -->
    <div class="page-header">
      <div class="page-header-bar" />
      <div class="page-header-content">
        <h1>{{ t('nav_home') }}</h1>
      </div>
      <v-btn variant="outlined" :loading="loading" @click="refresh">
        {{ loading ? t('refreshing') : t('refresh') }}
      </v-btn>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <SubscriptionBlockedPanel show-summary />

    <DesktopConnectionPanel v-if="!appState.subscription.blockReason && isDesktopConnectionShell()" />

    <!-- Connection Section (mobile) -->
    <div v-if="!appState.subscription.blockReason && !isDesktopConnectionShell()" class="page-section">
      <p class="section-label">{{ t('section_connection') }}</p>
      <v-card class="panel-card connection-card">
        <v-card-text>
          <h2>{{ appState.vpn ? t('status_connected') : t('status_disconnected') }}</h2>
          <button
            class="liquid-orb mt-4"
            :class="{ connected: appState.vpn }"
            :disabled="connectingIndex !== null || (!appState.vpn && Boolean(selectedNode && !selectedNode.connectSupported))"
            @click="toggleConnection()"
          >
            {{ connectingIndex !== null ? t('action_connecting') : appState.vpn ? t('action_disconnect') : t('action_connect') }}
          </button>
          <p v-if="selectedNode && !selectedNode.connectSupported" class="text-error mt-2 text-caption">
            {{ t('unsupported_protocol') }}
          </p>
          <div v-if="appState.vpn" class="metric-grid mt-4">
            <div class="metric-cell">
              <span>{{ t('session_duration') }}</span>
              <strong>{{ formatDuration(duration) }}</strong>
            </div>
            <div class="metric-cell">
              <span>{{ t('session_traffic') }}</span>
              <strong>{{ formatTrafficBytes(appState.vpn.uploadBytes + appState.vpn.downloadBytes) }}</strong>
            </div>
          </div>
        </v-card-text>
      </v-card>
    </div>

    <!-- Current Node Section -->
    <div v-if="!appState.subscription.blockReason" class="page-section">
      <p class="section-label">{{ t('section_current_node') }}</p>
      <v-card
        class="panel-card"
        :class="{ 'cursor-pointer': appState.nodes.length > 0 }"
        @click="appState.nodes.length > 0 && router.push('/nodes')"
      >
        <v-card-text>
          <div class="d-flex align-center">
            <div class="flex-grow-1">
              <h3 class="text-h6">
                {{ selectedNode ? displayNodeName(selectedNode, selectedNodeIndex) : (loading ? t('refreshing') : t('no_nodes')) }}
              </h3>
              <p v-if="selectedNode" class="muted mt-1">
                {{ selectedNode.protocolLabel }}
                <span v-if="selectedNode.latencyMs"> · {{ selectedNode.latencyMs }} ms</span>
              </p>
            </div>
            <span v-if="appState.nodes.length > 0" class="text-h5 text-medium-emphasis">›</span>
          </div>
        </v-card-text>
      </v-card>
    </div>

    <!-- Traffic Section -->
    <div v-if="!appState.subscription.blockReason && appState.subscription.trafficTotalBytes > 0" class="page-section">
      <p class="section-label">{{ t('section_traffic') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <p class="muted mb-2">{{ appState.subscription.summary }}</p>
          <v-progress-linear
            color="primary"
            height="8"
            rounded
            :model-value="progressPercent"
            bg-color="surface-container-high"
          />
        </v-card-text>
      </v-card>
    </div>

    <!-- Notices -->
    <div v-if="appState.notices.length" class="page-section">
      <p class="section-label">{{ t('announcement') }}</p>
      <div class="stack">
        <v-card v-for="notice in appState.notices" :key="notice.id" class="panel-card">
          <v-card-text>
            <p v-if="notice.title" class="text-body-1 font-weight-bold">{{ notice.title }}</p>
            <p class="muted preline">{{ stripHtml(notice.content) }}</p>
            <p v-if="notice.createdAt > 0" class="text-caption mt-2 text-medium-emphasis">
              {{ formatUnixTime(notice.createdAt) }}
            </p>
          </v-card-text>
        </v-card>
      </div>
    </div>
  </section>
</template>
