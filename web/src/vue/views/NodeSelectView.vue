<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  aerionTestNode,
} from '../../api/xboard'
import {
  resolveNodeHost,
} from '../../api/system'
import {
  aerionNodeWithResolvedHost,
  displayNodeName,
  rawNodeHost,
  readableNodeTestError,
  targetHostPort,
} from '../../nodes'
import { publicErrorText } from '../../format'
import { appState, store, t } from '../state'
import type { AppNode } from '../../store'

const router = useRouter()
const testingAll = ref(false)
const error = ref('')

const selectedNodeIndex = computed(() => appState.vpn?.nodeIndex ?? 0)

const blockTitle = computed(() => {
  if (appState.subscription.blockReason === 'no_plan') return t('subscription_no_plan_title')
  if (appState.subscription.blockReason === 'traffic_exceeded') return t('subscription_traffic_exceeded_title')
  return t('subscription_expired_title')
})

const blockDescription = computed(() => {
  if (appState.subscription.blockReason === 'no_plan') return t('subscription_no_plan_body')
  if (appState.subscription.blockReason === 'traffic_exceeded') return t('subscription_traffic_exceeded_body')
  return t('subscription_expired_body')
})

async function resolvedNode(node: AppNode): Promise<unknown> {
  const host = rawNodeHost(node)
  const resolvedHost = await resolveNodeHost(appState.settings.nodeDns, host, appState.buildConfig?.user_agent ?? '')
  return aerionNodeWithResolvedHost(node, resolvedHost)
}

async function testOne(node: AppNode, index: number) {
  store().setNodeLoading(index)
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

function selectNode(index: number) {
  const node = appState.nodes[index]
  if (!node) return
  if (!node.connectSupported) {
    error.value = t('unsupported_protocol')
    return
  }
  router.back()
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
        :disabled="testingAll"
        @click="testAll"
      >
        {{ testingAll ? t('node_test') + '…' : t('test_all_nodes') }}
      </v-btn>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <!-- Subscription Blocked -->
    <div v-if="appState.subscription.blockReason" class="page-section">
      <p class="section-label">{{ blockTitle }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <p class="muted">{{ blockDescription }}</p>
          <v-btn class="mt-4" color="primary" block @click="router.push('/plans')">
            {{ t('go_to_plans') }}
          </v-btn>
        </v-card-text>
      </v-card>
    </div>

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
        @click="selectNode(index)"
      >
        <div class="flex-grow-1 min-w-0">
          <div class="d-flex align-center gap-2">
            <strong class="text-truncate">
              <span v-if="index === selectedNodeIndex" class="text-primary font-weight-bold">✓ </span>
              {{ displayNodeName(node, index) }}
            </strong>
            <v-btn
              v-if="node.connectSupported"
              icon="↻"
              size="x-small"
              variant="text"
              :loading="node._testing"
              @click.stop="testOne(node, index)"
            />
          </div>
          <div class="d-flex align-center flex-wrap gap-1 mt-1">
            <span class="text-caption text-medium-emphasis">{{ node.protocolLabel }}</span>
            <span
              v-for="tag in node.tags"
              :key="tag"
              class="tag-chip"
            >{{ tag }}</span>
            <span v-if="node.latencyMs" class="text-caption text-medium-emphasis ml-auto">
              {{ node.latencyMs }} ms
            </span>
            <span v-if="node.testError" class="text-caption text-error ml-auto">
              {{ node.testError }}
            </span>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
