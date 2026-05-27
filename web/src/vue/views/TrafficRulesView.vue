<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { appState, t } from '../state'

const router = useRouter()

const routing = computed(() => appState.routing)
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <button class="back-btn" type="button" @click="router.back()">‹</button>
      <div>
        <h1>{{ t('page_traffic_rules_title') }}</h1>
        <p class="muted">{{ t('page_traffic_rules_subtitle') }}</p>
      </div>
    </header>

    <div class="page-section">
      <p class="section-label">{{ t('section_traffic_rules') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <div v-if="routing.hasRules" class="rules-summary">
            <p>{{ t('traffic_rules_count', routing.ruleCount) }}</p>
            <p class="muted">
              {{ t('traffic_rules_groups', routing.proxyGroupCount) }} ·
              {{ t('traffic_rules_providers', routing.ruleProviderCount) }}
            </p>
          </div>
          <p v-else class="muted">{{ t('traffic_rules_none') }}</p>

          <ul v-if="routing.rulesPreview.length" class="rules-preview mt-4">
            <li v-for="(rule, index) in routing.rulesPreview" :key="`${index}-${rule}`">
              <code>{{ rule }}</code>
            </li>
          </ul>
          <p v-if="routing.ruleCount > routing.rulesPreview.length" class="muted mt-2">
            {{ t('traffic_rules_more', routing.ruleCount - routing.rulesPreview.length) }}
          </p>

          <p class="muted mt-4">{{ t('traffic_rules_hint') }}</p>
        </v-card-text>
      </v-card>
    </div>
  </div>
</template>

<style scoped>
.rules-preview {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 0.5rem;
}

.rules-preview code {
  display: block;
  padding: 0.5rem 0.75rem;
  border-radius: 8px;
  background: rgba(var(--v-theme-on-surface), 0.06);
  font-size: 0.85rem;
  word-break: break-all;
}
</style>
