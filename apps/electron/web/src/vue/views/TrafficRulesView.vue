<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { aerionInspectRoute, aerionTestRoute, type RouteInspectResult, type RouteTestResult } from '../../api/aerion'
import { applyDesktopConnection, isDesktopConnectionShell } from '../../desktop/connection'
import { publicErrorText } from '../../format'
import { appState, persistSettings, t } from '../state'

const PAGE_STEP = 200
const router = useRouter()

const routing = computed(() => appState.routing)
const routeConfigYaml = ref(appState.settings.routeConfigYaml)
const customInspected = ref<RouteInspectResult | null>(null)
const ruleSearch = ref('')
const visibleLimit = ref(PAGE_STEP)
const saving = ref(false)
const error = ref('')
const message = ref('')

const testTarget = ref('example.com:443')
const testNetwork = ref<'tcp' | 'udp'>('tcp')
const testingRule = ref(false)
const testResult = ref<RouteTestResult | null>(null)

watch(() => appState.settings.routeConfigYaml, () => {
  routeConfigYaml.value = appState.settings.routeConfigYaml
})

watch(ruleSearch, () => {
  visibleLimit.value = PAGE_STEP
})

const effectiveStats = computed(() => {
  if (customInspected.value) {
    return {
      hasRules: customInspected.value.has_rules,
      ruleCount: customInspected.value.rule_count,
      proxyGroupCount: customInspected.value.proxy_group_count,
      ruleProviderCount: customInspected.value.rule_provider_count,
      rules: customInspected.value.rules ?? customInspected.value.rules_preview,
    }
  }
  return {
    hasRules: routing.value.hasRules,
    ruleCount: routing.value.ruleCount,
    proxyGroupCount: routing.value.proxyGroupCount,
    ruleProviderCount: routing.value.ruleProviderCount,
    rules: routing.value.rules.length > 0 ? routing.value.rules : routing.value.rulesPreview,
  }
})

const filteredRules = computed(() => {
  const q = ruleSearch.value.trim().toLowerCase()
  const all = effectiveStats.value.rules
  if (!q) return all
  return all.filter((rule) => rule.toLowerCase().includes(q))
})

const visibleRules = computed(() => filteredRules.value.slice(0, visibleLimit.value))

onMounted(async () => {
  const custom = appState.settings.routeConfigYaml.trim()
  if (!custom) return
  try {
    customInspected.value = await aerionInspectRoute(custom)
  } catch {
    customInspected.value = null
  }
})

function decisionColor(decision: RouteTestResult['decision']): string {
  if (decision === 'DIRECT') return 'success'
  if (decision === 'REJECT') return 'error'
  return 'primary'
}

async function runRuleTest() {
  const target = testTarget.value.trim()
  if (!target) return
  const activeYaml = (routeConfigYaml.value.trim() || routing.value.routeConfigYaml || '').trim()
  if (!activeYaml) {
    error.value = t('routing_rules_missing')
    return
  }
  error.value = ''
  testingRule.value = true
  try {
    const activeNode = appState.nodes[appState.vpn?.nodeIndex ?? appState.preferredNodeIndex]
    testResult.value = await aerionTestRoute({
      config_yaml: activeYaml,
      geoip_dir: appState.settings.geoipDir.trim() || undefined,
      global_proxy: appState.settings.routingMode === 'global' ? activeNode?.name : undefined,
      selected_proxy: appState.settings.routingMode === 'rule' ? activeNode?.name : undefined,
      target,
      network: testNetwork.value,
    })
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    testingRule.value = false
  }
}

async function saveRouteConfig() {
  error.value = ''
  message.value = ''
  saving.value = true
  try {
    const trimmed = routeConfigYaml.value.trim()
    customInspected.value = trimmed ? await aerionInspectRoute(trimmed) : null
    await persistSettings({ routeConfigYaml: trimmed })
    if (isDesktopConnectionShell() && appState.vpn) {
      const connErr = await applyDesktopConnection()
      if (connErr) {
        error.value = connErr
        return
      }
    }
    message.value = t('settings_saved')
    window.setTimeout(() => { message.value = '' }, 2000)
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    saving.value = false
  }
}

async function clearRouteConfig() {
  error.value = ''
  message.value = ''
  routeConfigYaml.value = ''
  customInspected.value = null
  await persistSettings({ routeConfigYaml: '' })
  if (isDesktopConnectionShell() && appState.vpn) {
    const connErr = await applyDesktopConnection()
    if (connErr) error.value = connErr
  }
}
</script>

<template>
  <section class="liquid-page liquid-page--wide">
    <div class="page-header">
      <v-btn variant="text" size="small" @click="router.push('/settings')">‹</v-btn>
      <div class="page-header-bar subtitle" />
      <div class="page-header-content">
        <h1>{{ t('page_traffic_rules_title') }}</h1>
        <p class="muted">{{ t('page_traffic_rules_subtitle') }}</p>
      </div>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" density="compact" class="mb-3">{{ error }}</v-alert>
    <v-alert v-if="message" color="primary" variant="tonal" density="compact" class="mb-3">{{ message }}</v-alert>

    <div class="page-section">
      <p class="section-label">{{ t('rule_tester_section') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <div class="rule-tester-bar">
            <v-text-field
              v-model="testTarget"
              class="rule-tester-input"
              :placeholder="t('rule_tester_target')"
              variant="outlined"
              density="compact"
              hide-details
              @keydown.enter="runRuleTest"
            />
            <v-btn-toggle
              v-model="testNetwork"
              mandatory
              divided
              density="compact"
              variant="outlined"
            >
              <v-btn value="tcp" size="small">TCP</v-btn>
              <v-btn value="udp" size="small">UDP</v-btn>
            </v-btn-toggle>
            <v-btn
              color="primary"
              size="small"
              :loading="testingRule"
              @click="runRuleTest"
            >
              {{ t('rule_tester_run') }}
            </v-btn>
          </div>
          <div v-if="testResult" class="d-flex align-center flex-wrap gap-2 mt-3">
            <v-chip :color="decisionColor(testResult.decision)" size="small" variant="tonal">
              {{ testResult.decision }}{{ testResult.outbound ? ` · ${testResult.outbound}` : '' }}
            </v-chip>
            <span class="text-caption text-medium-emphasis">{{ t('rule_tester_matched') }}:</span>
            <code class="text-body-2">{{ testResult.matched_rule }}</code>
          </div>
        </v-card-text>
      </v-card>
    </div>

    <div class="page-section">
      <p class="section-label">{{ t('section_traffic_rules') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <div class="d-flex align-center justify-space-between flex-wrap gap-2">
            <div v-if="effectiveStats.hasRules" class="rules-summary">
              <p class="font-weight-bold mb-0">{{ effectiveStats.ruleCount }} {{ t('traffic_rules_count_suffix') }}</p>
              <p class="muted">
                {{ effectiveStats.proxyGroupCount }} {{ t('traffic_rules_groups_suffix') }} ·
                {{ effectiveStats.ruleProviderCount }} {{ t('traffic_rules_providers_suffix') }}
              </p>
            </div>
            <p v-else class="muted mb-0">{{ t('traffic_rules_none') }}</p>
            <v-text-field
              v-if="effectiveStats.rules.length > 0"
              v-model="ruleSearch"
              class="rule-search"
              :placeholder="t('search_rules')"
              variant="outlined"
              density="compact"
              clearable
              hide-details
            />
          </div>

          <div v-if="visibleRules.length" class="rule-list-box mt-3">
            <div v-for="(rule, index) in visibleRules" :key="`${index}-${rule}`" class="rule-list-row">
              <code class="text-caption">{{ rule }}</code>
            </div>
          </div>
          <div v-if="filteredRules.length > visibleLimit" class="d-flex align-center gap-2 mt-2">
            <span class="muted">{{ filteredRules.length - visibleLimit }} {{ t('traffic_rules_more_suffix') }}</span>
            <v-btn size="x-small" variant="tonal" @click="visibleLimit += PAGE_STEP">
              {{ t('load_more') }}
            </v-btn>
          </div>

          <p class="muted mt-3">{{ t('traffic_rules_hint') }}</p>
          <v-textarea
            v-model="routeConfigYaml"
            class="mt-3"
            :label="t('traffic_rules_config_label')"
            variant="outlined"
            density="compact"
            rows="8"
            auto-grow
          />
          <div class="d-flex gap-2 mt-2">
            <v-btn color="primary" size="small" :loading="saving" @click="saveRouteConfig">{{ t('common_save_settings') }}</v-btn>
            <v-btn variant="outlined" size="small" @click="clearRouteConfig">{{ t('common_clear_selection') }}</v-btn>
          </div>
        </v-card-text>
      </v-card>
    </div>
  </section>
</template>
