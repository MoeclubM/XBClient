<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { androidListInstalledApps } from '../../api/system'
import { publicErrorText } from '../../format'
import { appState, persistSettings, t } from '../state'
import type { InstalledAppItem } from '../../api/system'

const apps = ref<InstalledAppItem[]>([])
const query = ref('')
const error = ref('')
const loading = ref(false)

const selectedPackages = computed(() => new Set(
  (appState.settings.appRuleMode === 'allow' ? appState.settings.allowedApps : appState.settings.excludedApps)
    .split(/[,;\s]+/)
    .filter(Boolean),
))

const filteredApps = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return apps.value
  return apps.value.filter((app) => app.label.toLowerCase().includes(q) || app.packageName.toLowerCase().includes(q))
})

async function loadApps() {
  loading.value = true
  error.value = ''
  try {
    apps.value = (await androidListInstalledApps()).apps
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

async function switchMode(mode: 'exclude' | 'allow') {
  await persistSettings({ appRuleMode: mode })
}

async function togglePackage(packageName: string) {
  const next = new Set(selectedPackages.value)
  if (next.has(packageName)) next.delete(packageName)
  else next.add(packageName)
  const text = Array.from(next).join('\n')
  if (appState.settings.appRuleMode === 'allow') await persistSettings({ allowedApps: text })
  else await persistSettings({ excludedApps: text })
}

onMounted(() => {
  if (appState.capabilities?.vpn) void loadApps()
})
</script>

<template>
  <section class="liquid-page">
    <header class="liquid-header">
      <div>
        <p class="eyebrow">{{ t('nav_settings') }}</p>
        <h1>{{ t('page_app_rules_title') }}</h1>
        <p class="muted">{{ t('page_app_rules_subtitle') }}</p>
      </div>
    </header>

    <v-alert v-if="!appState.capabilities?.vpn" color="primary" variant="tonal" class="mb-4">
      {{ t('vpn_app_rules_android_only') }}
    </v-alert>
    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <v-card v-if="appState.capabilities?.vpn" class="glass-card pa-4">
      <v-btn-toggle :model-value="appState.settings.appRuleMode" class="liquid-toggle mb-4" mandatory rounded="pill">
        <v-btn value="exclude" @click="switchMode('exclude')">{{ t('mode_exclude') }}</v-btn>
        <v-btn value="allow" @click="switchMode('allow')">{{ t('mode_allow') }}</v-btn>
      </v-btn-toggle>
      <v-text-field v-model="query" :label="t('search_app_label')" />
      <p class="muted">
        {{ appState.settings.appRuleMode === 'allow' ? t('app_rules_allow_desc') : t('app_rules_exclude_desc') }}
      </p>
    </v-card>

    <v-card v-if="appState.capabilities?.vpn" class="glass-card pa-2 mt-4">
      <button
        v-for="app in filteredApps"
        :key="app.packageName"
        class="node-row"
        :class="{ active: selectedPackages.has(app.packageName) }"
        @click="togglePackage(app.packageName)"
      >
        <span>
          <strong>{{ app.label }}</strong>
          <small>{{ app.packageName }}</small>
        </span>
        <span>{{ selectedPackages.has(app.packageName) ? '✓' : '' }}</span>
      </button>
      <p v-if="!loading && !filteredApps.length" class="muted pa-3">{{ t('app_rules_none_selected') }}</p>
    </v-card>
  </section>
</template>
