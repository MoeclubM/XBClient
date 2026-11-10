<script setup lang="ts">
import { computed, ref } from 'vue'
import { aerionTestNode } from '../../api/aerion'
import { resolveAppNode } from '../../api/system'
import {
  displayNodeName,
  readableNodeTestError,
  targetHostPort,
} from '../../nodes'
import { applyDesktopConnection, isDesktopConnectionShell } from '../../desktop/connection'
import { publicErrorText } from '../../format'
import { isElectronShell } from '../../platform/shell'
import SubscriptionBlockedPanel from '../components/SubscriptionBlockedPanel.vue'
import { appState, store, t } from '../state'
import type { AppNode } from '../../store'

const NODE_TEST_CONCURRENCY = 15
const NODE_TEST_TIMEOUT_MS = 8000

const nodeTestAvailable = isElectronShell()
const testingAll = ref(false)
const error = ref('')
const searchQuery = ref('')
const protocolFilter = ref('all')
const sortBy = ref<'default' | 'latency'>('default')

const selectedNodeIndex = computed(() => appState.vpn?.nodeIndex ?? appState.preferredNodeIndex)

const testingBusy = ref(false)

const protocolOptions = computed(() => {
  const seen = new Set<string>()
  for (const node of appState.nodes) {
    if (node.protocolLabel) seen.add(node.protocolLabel)
  }
  return Array.from(seen)
})

const filteredNodes = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  const items = appState.nodes
    .map((node, index) => ({ node, index }))
    .filter(({ node, index }) => {
      if (protocolFilter.value !== 'all' && node.protocolLabel !== protocolFilter.value) return false
      if (!q) return true
      const haystack = `${displayNodeName(node, index)} ${node.name} ${node.protocolLabel} ${node.tags.join(' ')}`.toLowerCase()
      return haystack.includes(q)
    })
  if (sortBy.value === 'latency') {
    items.sort((a, b) => {
      const aLat = typeof a.node.latencyMs === 'number' && a.node.latencyMs > 0 ? a.node.latencyMs : Number.POSITIVE_INFINITY
      const bLat = typeof b.node.latencyMs === 'number' && b.node.latencyMs > 0 ? b.node.latencyMs : Number.POSITIVE_INFINITY
      if (aLat !== bLat) return aLat - bLat
      if (Boolean(a.node.testError) !== Boolean(b.node.testError)) return a.node.testError ? 1 : -1
      return a.index - b.index
    })
  }
  return items
})

async function runNodeTest(node: AppNode, index: number) {
  store().setNodeLoading(index)
  const target = targetHostPort(appState.settings.nodeTestTarget)
  if (!appState.buildConfig?.user_agent) throw new Error('XBCLIENT_USER_AGENT is required in build config')
  const result = await aerionTestNode({
    node: await resolveAppNode(node, appState.settings.nodeDns, appState.buildConfig.user_agent),
    target_host: target.host,
    target_port: target.port,
    target_tls: target.tls,
    timeout_ms: NODE_TEST_TIMEOUT_MS,
  })
  if (result.ok) {
    if (typeof result.latency_ms !== 'number') throw new Error('node test success missing latency_ms')
    store().setNodeResult(index, { latencyMs: result.latency_ms })
  } else {
    if (!result.error) throw new Error('node test failed without error')
    store().setNodeResult(index, { testError: readableNodeTestError(result.error, appState.settings.appLanguage) })
  }
}

async function testOne(node: AppNode, index: number) {
  if (!nodeTestAvailable) {
    error.value = t('node_test_desktop_only')
    return
  }
  if (testingAll.value || testingBusy.value) return
  testingBusy.value = true
  try {
    await runNodeTest(node, index)
  } catch (err) {
    store().setNodeResult(index, { testError: readableNodeTestError(publicErrorText(err), appState.settings.appLanguage) })
  } finally {
    testingBusy.value = false
  }
}

async function testAll() {
  if (!nodeTestAvailable) {
    error.value = t('node_test_desktop_only')
    return
  }
  if (testingAll.value) return
  testingAll.value = true
  error.value = ''
  const queue: number[] = []
  for (let i = 0; i < appState.nodes.length; i++) {
    if (!appState.nodes[i].connectSupported) {
      store().setNodeResult(i, { testError: t('unsupported_protocol') })
      continue
    }
    queue.push(i)
  }
  let next = 0
  async function worker() {
    while (next < queue.length) {
      const index = queue[next++]
      const node = appState.nodes[index]
      if (!node) continue
      try {
        await runNodeTest(node, index)
      } catch (err) {
        store().setNodeResult(index, { testError: readableNodeTestError(publicErrorText(err), appState.settings.appLanguage) })
      }
    }
  }
  try {
    const n = Math.min(NODE_TEST_CONCURRENCY, queue.length)
    await Promise.all(Array.from({ length: n }, () => worker()))
  } finally {
    testingAll.value = false
  }
}

async function selectNode(index: number) {
  const node = appState.nodes[index]
  if (!node) return
  if (!node.connectSupported) {
    error.value = t('unsupported_protocol')
    return
  }
  store().setPreferredNodeIndex(index)
  if (isDesktopConnectionShell()) {
    const message = await applyDesktopConnection()
    if (message) error.value = message
  }
}

async function selectLowestLatency() {
  error.value = ''
  const candidates = () => {
    const base = filteredNodes.value.length > 0
      ? filteredNodes.value
      : appState.nodes.map((node, index) => ({ node, index }))
    return base.filter((item) => item.node.connectSupported && typeof item.node.latencyMs === 'number' && item.node.latencyMs > 0)
  }
  if (candidates().length === 0 && nodeTestAvailable) {
    await testAll()
  }
  const reachable = candidates()
  if (reachable.length === 0) {
    error.value = t('no_reachable_nodes')
    return
  }
  reachable.sort((a, b) => (a.node.latencyMs ?? Number.POSITIVE_INFINITY) - (b.node.latencyMs ?? Number.POSITIVE_INFINITY))
  await selectNode(reachable[0].index)
}
</script>

<template>
  <section class="liquid-page liquid-page--wide">
    <!-- Page Header -->
    <div class="page-header">
      <div class="page-header-bar" />
      <div class="page-header-content">
        <h1>{{ t('select_node') }}</h1>
        <p class="muted">{{ t('select_node_desc') }}</p>
      </div>
      <div
        v-if="nodeTestAvailable && !appState.subscription.blockReason && appState.nodes.length > 0"
        class="d-flex align-center gap-2"
      >
        <v-btn
          variant="outlined"
          size="small"
          :disabled="testingAll || testingBusy"
          @click="selectLowestLatency"
        >
          {{ t('select_lowest_latency') }}
        </v-btn>
        <v-btn
          variant="tonal"
          color="primary"
          size="small"
          :loading="testingAll"
          :disabled="testingAll || testingBusy"
          @click="testAll"
        >
          {{ testingAll ? t('node_testing') : t('test_all_nodes') }}
        </v-btn>
      </div>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" density="compact" class="mb-3">{{ error }}</v-alert>

    <SubscriptionBlockedPanel />

    <div v-if="!appState.subscription.blockReason && appState.nodes.length > 0" class="node-toolbar mb-3">
      <v-text-field
        v-model="searchQuery"
        class="node-search"
        :placeholder="t('search_nodes')"
        variant="outlined"
        density="compact"
        clearable
        hide-details
      />
      <div v-if="protocolOptions.length > 1" class="d-flex align-center flex-wrap gap-1">
        <v-chip
          size="small"
          :color="protocolFilter === 'all' ? 'primary' : undefined"
          :variant="protocolFilter === 'all' ? 'tonal' : 'outlined'"
          @click="protocolFilter = 'all'"
        >
          {{ t('filter_all') }}
        </v-chip>
        <v-chip
          v-for="proto in protocolOptions"
          :key="proto"
          size="small"
          :color="protocolFilter === proto ? 'primary' : undefined"
          :variant="protocolFilter === proto ? 'tonal' : 'outlined'"
          @click="protocolFilter = proto"
        >
          {{ proto }}
        </v-chip>
      </div>
      <v-btn-toggle
        v-model="sortBy"
        mandatory
        divided
        density="compact"
        variant="outlined"
      >
        <v-btn value="default" size="small">{{ t('sort_default') }}</v-btn>
        <v-btn value="latency" size="small">{{ t('sort_latency') }}</v-btn>
      </v-btn-toggle>
    </div>

    <!-- Empty -->
    <div v-if="!appState.subscription.blockReason && !appState.nodes.length" class="page-section">
      <p class="section-label">{{ t('section_available_nodes') }}</p>
      <p class="muted">{{ t('no_nodes') }}</p>
    </div>
    <p v-else-if="!appState.subscription.blockReason && filteredNodes.length === 0" class="muted">
      {{ t('nodes_filter_empty') }}
    </p>

    <!-- Node List -->
    <div v-if="!appState.subscription.blockReason && filteredNodes.length > 0" class="node-list">
      <v-card
        v-for="{ node, index } in filteredNodes"
        :key="`${node.name}-${index}`"
        class="panel-card interactive-card"
        :variant="index === selectedNodeIndex ? 'tonal' : 'elevated'"
        :color="index === selectedNodeIndex ? 'primary' : undefined"
        role="button"
        tabindex="0"
        :aria-pressed="index === selectedNodeIndex"
        @click="selectNode(index)"
        @keydown.enter="selectNode(index)"
        @keydown.space.prevent="selectNode(index)"
      >
        <v-card-text>
          <div class="d-flex align-start justify-space-between gap-2">
            <div class="flex-grow-1 min-width-0">
              <p class="font-weight-bold mb-1">
                <span v-if="index === selectedNodeIndex">✓ </span>
                {{ displayNodeName(node, index) }}
              </p>
              <p class="text-caption text-medium-emphasis mb-0">
                {{ node.protocolLabel }}{{ node.connectSupported ? '' : ` · ${t('unsupported_protocol')}` }}
              </p>
              <div v-if="node.tags.length" class="d-flex flex-wrap gap-1 mt-2">
              <v-chip
                v-for="tag in node.tags"
                :key="tag"
                size="x-small"
                color="secondary"
                variant="tonal"
              >
                {{ tag }}
              </v-chip>
              </div>
            </div>
            <div class="text-right">
              <p v-if="node.latencyMs" class="text-caption mb-2">{{ node.latencyMs }} ms</p>
              <p v-else-if="node._testing" class="text-caption text-medium-emphasis mb-2">{{ t('node_testing') }}</p>
              <p v-else-if="node.testError" class="text-caption text-error mb-2">{{ node.testError }}</p>
              <v-btn
                v-if="nodeTestAvailable && node.connectSupported"
                size="small"
                variant="tonal"
                :loading="node._testing"
                :disabled="testingAll || testingBusy || node._testing"
                @click.stop="testOne(node, index)"
              >
                {{ t('node_test') }}
              </v-btn>
            </div>
          </div>
        </v-card-text>
      </v-card>
    </div>
  </section>
</template>
