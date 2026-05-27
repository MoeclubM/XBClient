<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getVersion } from '../../platform/electron'
import { autostartSetEnabled, openInAppBrowser, parseSocksAddr, systemProxyClear, systemProxySet } from '../../api/system'
import { publicErrorText } from '../../format'
import { appState, persistSettings, t } from '../state'
import type { AppSettings } from '../../store'

const router = useRouter()
const error = ref('')
const message = ref('')
const appVersion = ref('')

const nodeDns = ref(appState.settings.nodeDns)
const overseasDns = ref(appState.settings.overseasDns)
const directDns = ref(appState.settings.directDns)
const vpnDnsMode = ref(appState.settings.vpnDnsMode)
const virtualDnsPool = ref(appState.settings.virtualDnsPool)
const nodeTestTarget = ref(appState.settings.nodeTestTarget)

watch(() => appState.settings, (s) => {
  nodeDns.value = s.nodeDns
  overseasDns.value = s.overseasDns
  directDns.value = s.directDns
  vpnDnsMode.value = s.vpnDnsMode
  virtualDnsPool.value = s.virtualDnsPool
  nodeTestTarget.value = s.nodeTestTarget
}, { deep: true })

onMounted(async () => {
  appVersion.value = await getVersion().catch((err) => {
    error.value = publicErrorText(err)
    return ''
  })
})

async function setTheme(value: AppSettings['themeMode']) {
  await persistSettings({ themeMode: value })
}

async function setLanguage(value: AppSettings['appLanguage']) {
  await persistSettings({ appLanguage: value })
}

async function toggleProxy(value: boolean) {
  await persistSettings({ autoApplyProxy: value })
  if (!value) await systemProxyClear()
  else if (appState.vpn?.socksAddr) {
    const parsed = parseSocksAddr(appState.vpn.socksAddr)
    await systemProxySet(parsed.host, parsed.port)
  }
}

async function toggleAutostart(value: boolean) {
  await autostartSetEnabled(value)
  await persistSettings({ autostart: value })
}

async function setIpv6(value: boolean | null) {
  await persistSettings({ vpnIpv6Enabled: value === true })
}

async function saveDnsSettings() {
  error.value = ''
  message.value = ''
  if (!nodeDns.value.trim() || !overseasDns.value.trim() || !directDns.value.trim() || !nodeTestTarget.value.trim() || !virtualDnsPool.value.trim()) {
    error.value = t('dns_fields_required')
    return
  }
  await persistSettings({
    nodeDns: nodeDns.value.trim(),
    overseasDns: overseasDns.value.trim(),
    directDns: directDns.value.trim(),
    vpnDnsMode: vpnDnsMode.value,
    virtualDnsPool: virtualDnsPool.value.trim(),
    nodeTestTarget: nodeTestTarget.value.trim(),
  })
  message.value = t('settings_saved')
  setTimeout(() => { message.value = '' }, 2000)
}

const languageOptions = [
  { value: 'system', label: 'System' },
  { value: 'zh-CN', label: '中文' },
  { value: 'en', label: 'English' },
  { value: 'ja', label: '日本語' },
  { value: 'ru', label: 'Русский' },
  { value: 'fa', label: 'فارسی' },
]

const themeOptions = [
  { value: 'system', label: t('theme_system') },
  { value: 'light', label: t('theme_light') },
  { value: 'dark', label: t('theme_dark') },
]

const dnsModeOptions = [
  { value: 'over_tcp', label: t('dns_mode_over_tcp') },
  { value: 'virtual', label: t('dns_mode_virtual') },
  { value: 'direct', label: t('dns_mode_direct') },
]
</script>

<template>
  <section class="liquid-page">
    <!-- Page Header -->
    <div class="page-header">
      <div class="page-header-bar subtitle" />
      <div class="page-header-content">
        <h1>{{ t('nav_settings') }}</h1>
        <p>{{ t('page_settings_subtitle') }}</p>
      </div>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="message" color="primary" variant="tonal" class="mb-4">{{ message }}</v-alert>

    <!-- Appearance Section -->
    <div class="page-section">
      <p class="section-label">{{ t('section_appearance') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <v-select
            :model-value="appState.settings.appLanguage"
            :label="t('language')"
            :items="languageOptions"
            item-title="label"
            item-value="value"
            variant="outlined"
            @update:model-value="setLanguage"
          />
          <v-select
            class="mt-3"
            :model-value="appState.settings.themeMode"
            :label="t('theme')"
            :items="themeOptions"
            item-title="label"
            item-value="value"
            variant="outlined"
            @update:model-value="setTheme"
          />
          <v-btn class="mt-3" variant="outlined" block @click="router.push('/login')">
            {{ t('setting_reset_onboarding') }}
          </v-btn>
        </v-card-text>
      </v-card>
    </div>

    <!-- App Rules Section -->
    <div v-if="appState.capabilities?.vpn" class="page-section">
      <p class="section-label">{{ t('section_app_rules') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <p class="muted">
            {{ appState.settings.appRuleMode === 'allow' ? t('app_rules_allow_desc') : t('app_rules_exclude_desc') }}
          </p>
          <div class="d-flex gap-2 mt-3">
            <v-btn color="primary" @click="router.push('/settings/app-rules')">
              {{ t('action_select_apps') }}
            </v-btn>
          </div>
        </v-card-text>
      </v-card>
    </div>

    <!-- DNS Section -->
    <div class="page-section">
      <p class="section-label">DNS</p>
      <v-card class="panel-card">
        <v-card-text>
          <v-text-field
            v-model="nodeDns"
            :label="t('node_dns')"
            variant="outlined"
            density="comfortable"
          />
          <v-text-field
            v-model="overseasDns"
            class="mt-2"
            :label="t('dns_overseas_label')"
            variant="outlined"
            density="comfortable"
          />
          <v-text-field
            v-model="directDns"
            class="mt-2"
            :label="t('dns_direct_label')"
            variant="outlined"
            density="comfortable"
          />
          <p class="text-body-2 font-weight-bold mt-4 mb-1">{{ t('dns_mode') }}</p>
          <v-btn-toggle
            v-model="vpnDnsMode"
            class="liquid-toggle mt-1"
            mandatory
            rounded="pill"
            divided
          >
            <v-btn
              v-for="mode in dnsModeOptions"
              :key="mode.value"
              :value="mode.value"
              size="small"
            >{{ mode.label }}</v-btn>
          </v-btn-toggle>
          <v-text-field
            v-model="virtualDnsPool"
            class="mt-3"
            :label="t('dns_virtual_pool')"
            variant="outlined"
            density="comfortable"
            :hint="t('dns_virtual_pool_help')"
            persistent-hint
          />
          <div class="d-flex align-center justify-space-between mt-4">
            <span>{{ t('enable_ipv6') }}</span>
            <v-switch
              color="primary"
              :model-value="appState.settings.vpnIpv6Enabled"
              hide-details
              @update:model-value="setIpv6"
            />
          </div>
        </v-card-text>
      </v-card>
    </div>

    <!-- Node Test Section -->
    <div class="page-section">
      <p class="section-label">{{ t('section_node_test') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <v-text-field
            v-model="nodeTestTarget"
            :label="t('node_test_target')"
            variant="outlined"
            density="comfortable"
            :hint="t('node_test_help')"
            persistent-hint
          />
          <v-btn class="mt-4" color="primary" block @click="saveDnsSettings">
            {{ t('common_save_settings') }}
          </v-btn>
        </v-card-text>
      </v-card>
    </div>

    <!-- System Section -->
    <div v-if="appState.capabilities?.system_proxy || appState.capabilities?.autostart" class="page-section">
      <p class="section-label">{{ t('section_system') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <v-switch
            v-if="appState.capabilities?.system_proxy"
            color="primary"
            :model-value="appState.settings.autoApplyProxy"
            :label="t('system_proxy')"
            :hint="t('system_proxy_desc')"
            persistent-hint
            @update:model-value="toggleProxy($event === true)"
          />
          <v-switch
            v-if="appState.capabilities?.autostart"
            color="primary"
            :model-value="appState.settings.autostart"
            :label="t('autostart')"
            :hint="t('autostart_desc')"
            persistent-hint
            @update:model-value="toggleAutostart($event === true)"
          />
        </v-card-text>
      </v-card>
    </div>

    <!-- About Section -->
    <div class="page-section">
      <p class="section-label">{{ t('about') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <p class="text-h6 font-weight-bold">{{ appState.buildConfig?.app_name || 'XBClient' }}</p>
          <p class="muted">{{ t('app_version') }} {{ appVersion || '-' }}</p>
          <div class="d-flex flex-wrap gap-2 mt-3">
            <v-btn
              v-if="appState.githubProjectUrl"
              variant="outlined"
              size="small"
              @click="openInAppBrowser(appState.githubProjectUrl, t('source_code'))"
            >
              {{ t('source_code') }}
            </v-btn>
            <v-btn variant="outlined" size="small" @click="router.push('/settings/licenses')">
              {{ t('licenses') }}
            </v-btn>
          </div>
        </v-card-text>
      </v-card>
    </div>
  </section>
</template>
