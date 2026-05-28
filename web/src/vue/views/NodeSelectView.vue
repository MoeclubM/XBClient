<script setup lang="ts">
import { computed, ref } from 'vue'
import { aerionTestNode } from '../../api/xboard'
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

const selectedNodeIndex = computed(() => appState.vpn?.nodeIndex ?? appState.preferredNodeIndex)

const testingBusy = ref(false)

async function runNodeTest(node: AppNode, index: number) {
  store().setNodeLoading(index)
  const target = targetHostPort(appState.settings.nodeTestTarget)
  const result = await aerionTestNode({
    node: await resolveAppNode(node, appState.settings.nodeDns, appState.buildConfig?.user_agent ?? ''),
    target_host: target.host,
    target_port: target.port,
    target_tls: target.tls,
    timeout_ms: NODE_TEST_TIMEOUT_MS,
  })
  store().setNodeResult(
    index,
    result.ok
      ? { latencyMs: result.latency_ms ?? result.first_latency_ms }
      : { testError: readableNodeTestError(result.error ?? '', appState.settings.appLanguage) },
  )
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
</script>

<template>
  <section class="liquid-page">
    <!-- Page Header -->
    <div class="page-header">
      <div class="page-header-bar" />
      <div class="page-header-content">
        <h1>{{ t('select_node') }}</h1>
        <p class="muted">{{ t('select_node_desc') }}</p>
      </div>
      <v-btn
        v-if="nodeTestAvailable && !appState.subscription.blockReason && appState.nodes.length > 0"
        variant="tonal"
        :loading="testingAll"
        :disabled="testingAll || testingBusy"
        @click="testAll"
      >
        {{ testingAll ? t('node_testing') : t('test_all_nodes') }}
      </v-btn>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <SubscriptionBlockedPanel />

    <!-- Empty -->
    <div v-if="!appState.subscription.blockReason && !appState.nodes.length" class="page-section">
      <p class="section-label">{{ t('section_available_nodes') }}</p>
      <p class="muted">{{ t('no_nodes') }}</p>
    </div>

    <!-- Node List -->
    <div v-if="!appState.subscription.blockReason && appState.nodes.length > 0" class="node-list">
      <div
        v-for="(node, index) in appState.nodes"
        :key="`${node.name}-${index}`"
        class="node-row"
        :class="{ active: index === selectedNodeIndex }"
      >
        <button
          class="node-pick flex-grow-1"
          :disabled="!node.connectSupported"
          @click="selectNode(index)"
        >
          <span>
            <strong>
              <span v-if="index === selectedNodeIndex" class="text-primary font-weight-bold">✓ </span>
              {{ displayNodeName(node, index) }}
            </strong>
            <small>{{ node.protocolLabel }} · {{ node.host }}{{ node.connectSupported ? '' : ` · ${t('unsupported_protocol')}` }}</small>
          </span>
        </button>
        <span class="node-actions">
          <small v-if="node.latencyMs">{{ node.latencyMs }} ms</small>
          <small v-else-if="node._testing" class="text-medium-emphasis">{{ t('node_testing') }}</small>
          <small v-else-if="node.testError" class="text-error">{{ node.testError }}</small>
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
        </span>
      </div>
    </div>
  </section>
</template>
