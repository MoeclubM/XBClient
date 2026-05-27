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
import SubscriptionBlockedPanel from '../components/SubscriptionBlockedPanel.vue'
import { appState, store, t } from '../state'
import type { AppNode } from '../../store'

const testingAll = ref(false)
const error = ref('')

const selectedNodeIndex = computed(() => appState.vpn?.nodeIndex ?? appState.preferredNodeIndex)

const testingBusy = ref(false)

async function testOne(node: AppNode, index: number) {
  if (testingBusy.value) return
  testingBusy.value = true
  store().setNodeLoading(index)
  try {
    const target = targetHostPort(appState.settings.nodeTestTarget)
    const result = await aerionTestNode({
      node: await resolveAppNode(node, appState.settings.nodeDns, appState.buildConfig?.user_agent ?? ''),
      target_host: target.host,
      target_port: target.port,
      target_tls: target.tls,
      timeout_ms: 8000,
    })
    store().setNodeResult(index, result.ok ? { latencyMs: result.latency_ms ?? result.first_latency_ms } : { testError: readableNodeTestError(result.error ?? '', appState.settings.appLanguage) })
  } catch (err) {
    store().setNodeResult(index, { testError: readableNodeTestError(publicErrorText(err), appState.settings.appLanguage) })
  } finally {
    testingBusy.value = false
  }
}

async function testAll() {
  testingAll.value = true
  error.value = ''
  try {
    for (let i = 0; i < appState.nodes.length; i++) {
      const node = appState.nodes[i]
      if (!node.connectSupported) {
        store().setNodeResult(i, { testError: t('unsupported_protocol') })
        continue
      }
      await testOne(node, i)
    }
  } catch (err) {
    error.value = publicErrorText(err)
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
        v-if="!appState.subscription.blockReason && appState.nodes.length > 0"
        variant="tonal"
        :loading="testingAll"
        :disabled="testingAll || testingBusy"
        @click="testAll"
      >
        {{ testingAll ? t('node_test') + '…' : t('test_all_nodes') }}
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
          <small v-if="node.testError" class="text-error">{{ node.testError }}</small>
          <v-btn
            v-if="node.connectSupported"
            size="small"
            variant="tonal"
            :loading="node._testing"
            :disabled="testingBusy || node._testing"
            @click.stop="testOne(node, index)"
          >
            {{ t('node_test') }}
          </v-btn>
        </span>
      </div>
    </div>
  </section>
</template>
