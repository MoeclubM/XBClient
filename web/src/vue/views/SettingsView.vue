<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getVersion } from '@tauri-apps/api/app'
import { useRouter } from 'vue-router'
import { autostartSetEnabled, openInAppBrowser, parseSocksAddr, systemProxyClear, systemProxySet } from '../../api/system'
import { xboardRequest } from '../../api/xboard'
import { publicErrorText } from '../../format'
import { enabled } from '../../reward'
import { appState, persistSettings, t } from '../state'
import { store } from '../state'
import type { AppSettings } from '../../store'

interface XboardBody {
  data?: unknown
  message?: string
}

const router = useRouter()
const error = ref('')
const appVersion = ref('')

onMounted(async () => {
  appVersion.value = await getVersion().catch((err) => {
    error.value = publicErrorText(err)
    return ''
  })
  await loadRewardConfig()
})

async function loadRewardConfig() {
  if (!appState.authData || !appState.capabilities?.admob) return
  const response = await xboardRequest<XboardBody>('admob_reward_config', { baseUrl: appState.baseUrl, authData: appState.authData })
  if (!response.ok || !response.body?.data || typeof response.body.data !== 'object') {
    store().setProfile({ paymentEnabled: false })
    store().setAdmobConfig({
      admobCloudEnabled: false,
      planRewardAdEnabled: false,
      pointsRewardAdEnabled: false,
      appOpenAdEnabled: false,
      planRewardedAdUnitId: '',
      planRewardSsvUserId: '',
      planRewardSsvCustomData: '',
      pointsRewardedAdUnitId: '',
      pointsRewardSsvUserId: '',
      pointsRewardSsvCustomData: '',
      appOpenAdUnitId: '',
    })
    error.value = response.body?.message ?? response.error ?? `HTTP ${response.status}`
    return
  }
  const data = response.body.data as Record<string, unknown>
  const adEnabled = enabled(data.ad_enabled)
  store().setProfile({ paymentEnabled: enabled(data.payment_enabled) })
  store().setAdmobConfig({
    admobCloudEnabled: adEnabled,
    planRewardAdEnabled: adEnabled && enabled(data.plan_reward_ad_enabled),
    pointsRewardAdEnabled: adEnabled && enabled(data.points_reward_ad_enabled),
    appOpenAdEnabled: enabled(data.app_open_ad_enabled),
    planRewardedAdUnitId: String(data.plan_rewarded_ad_unit_id ?? ''),
    planRewardSsvUserId: String(data.plan_ssv_user_id ?? ''),
    planRewardSsvCustomData: String(data.plan_ssv_custom_data ?? ''),
    pointsRewardedAdUnitId: String(data.points_rewarded_ad_unit_id ?? ''),
    pointsRewardSsvUserId: String(data.points_ssv_user_id ?? ''),
    pointsRewardSsvCustomData: String(data.points_ssv_custom_data ?? ''),
    appOpenAdUnitId: String(data.app_open_ad_unit_id ?? ''),
    githubProjectUrl: String(data.github_project_url ?? ''),
  })
}

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
</script>

<template>
  <section class="liquid-page">
    <header class="liquid-header">
      <div>
        <p class="eyebrow">{{ t('app_version') }} {{ appVersion || '-' }}</p>
        <h1>{{ t('nav_settings') }}</h1>
      </div>
    </header>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <v-card class="glass-card pa-4">
      <v-select
        :model-value="appState.settings.appLanguage"
        :label="t('language')"
        :items="['system', 'zh-CN', 'en', 'ja', 'ru', 'fa']"
        @update:model-value="setLanguage"
      />
      <v-select
        class="mt-3"
        :model-value="appState.settings.themeMode"
        :label="t('theme')"
        :items="[
          { title: t('theme_system'), value: 'system' },
          { title: t('theme_light'), value: 'light' },
          { title: t('theme_dark'), value: 'dark' },
        ]"
        @update:model-value="setTheme"
      />
    </v-card>

    <v-card class="glass-card pa-4 mt-4">
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
      <v-switch
        color="primary"
        :model-value="appState.settings.vpnIpv6Enabled"
        :label="t('enable_ipv6')"
        @update:model-value="setIpv6"
      />
      <v-btn v-if="appState.capabilities?.vpn" class="mt-2" block variant="outlined" @click="router.push('/settings/app-rules')">
        {{ t('section_app_rules') }}
      </v-btn>
    </v-card>

    <v-card class="glass-card pa-4 mt-4">
      <p class="eyebrow">{{ t('about') }}</p>
      <h2>{{ appState.buildConfig?.app_name || 'XBClient' }}</h2>
      <div class="stack mt-3">
        <v-btn v-if="appState.githubProjectUrl" variant="outlined" @click="openInAppBrowser(appState.githubProjectUrl, t('source_code'))">
          {{ t('source_code') }}
        </v-btn>
        <v-btn variant="outlined" @click="router.push('/settings/licenses')">{{ t('licenses') }}</v-btn>
      </div>
    </v-card>
  </section>
</template>
