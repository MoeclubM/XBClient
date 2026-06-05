<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { appState, persistSettings, t } from '../state'

const router = useRouter()

const routing = computed(() => appState.routing)
const routeConfigYaml = ref(appState.settings.routeConfigYaml)

watch(() => appState.settings.routeConfigYaml, () => {
  routeConfigYaml.value = appState.settings.routeConfigYaml
})

async function saveRouteConfig() {
  await persistSettings({ routeConfigYaml: routeConfigYaml.value.trim() })
}

async function clearRouteConfig() {
  routeConfigYaml.value = ''
  await persistSettings({ routeConfigYaml: '' })
}
</script>

<template>
  <section class="liquid-page">
    <div class="page-header">
      <v-btn variant="text" size="small" @click="router.push('/settings')">‹</v-btn>
      <div class="page-header-bar subtitle" />
      <div class="page-header-content">
        <h1>{{ t('page_traffic_rules_title') }}</h1>
        <p class="muted">{{ t('page_traffic_rules_subtitle') }}</p>
      </div>
    </div>

    <div class="page-section">
      <p class="section-label">{{ t('section_traffic_rules') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <div v-if="routing.hasRules" class="rules-summary">
            <p>{{ routing.ruleCount }} {{ t('traffic_rules_count_suffix') }}</p>
            <p class="muted">
              {{ routing.proxyGroupCount }} {{ t('traffic_rules_groups_suffix') }} ·
              {{ routing.ruleProviderCount }} {{ t('traffic_rules_providers_suffix') }}
            </p>
          </div>
          <p v-else class="muted">{{ t('traffic_rules_none') }}</p>

          <v-list v-if="routing.rulesPreview.length" class="mt-4" density="compact" lines="one">
            <v-list-item v-for="(rule, index) in routing.rulesPreview" :key="`${index}-${rule}`">
              <code class="text-body-2">{{ rule }}</code>
            </v-list-item>
          </v-list>
          <p v-if="routing.ruleCount > routing.rulesPreview.length" class="muted mt-2">
            {{ routing.ruleCount - routing.rulesPreview.length }} {{ t('traffic_rules_more_suffix') }}
          </p>

          <p class="muted mt-4">{{ t('traffic_rules_hint') }}</p>
          <v-textarea
            v-model="routeConfigYaml"
            class="mt-4"
            :label="t('traffic_rules_config_label')"
            variant="outlined"
            rows="10"
            auto-grow
          />
          <div class="d-flex gap-2 mt-3">
            <v-btn color="primary" @click="saveRouteConfig">{{ t('common_save_settings') }}</v-btn>
            <v-btn variant="outlined" @click="clearRouteConfig">{{ t('common_clear_selection') }}</v-btn>
          </div>
        </v-card-text>
      </v-card>
    </div>
  </section>
</template>
