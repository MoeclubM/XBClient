<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  desktopConnectionBusy,
  setRoutingMode,
  setSystemProxyEnabled,
  setTunEnabled,
} from '../../desktop/connection'
import { formatDuration, formatTrafficBytes } from '../../format'
import { appState, persistSettings, t } from '../state'

const error = ref('')
const duration = ref(0)
let connectedAt = 0
let durationTimer = 0

const routingModes = [
  { value: 'rule' as const, label: () => t('routing_mode_rule') },
  { value: 'global' as const, label: () => t('routing_mode_global') },
  { value: 'direct' as const, label: () => t('routing_mode_direct') },
]

const connected = computed(() => Boolean(appState.vpn))
const busy = computed(() => desktopConnectionBusy())
const tunNeedsElevation = computed(
  () => appState.settings.tunEnabled && appState.capabilities?.vpn && appState.capabilities.tun_elevated === false,
)

watch(
  () => appState.vpn,
  (session) => {
    if (session) {
      connectedAt = Date.now()
      duration.value = 0
      if (durationTimer) window.clearInterval(durationTimer)
      durationTimer = window.setInterval(() => {
        duration.value = Date.now() - connectedAt
      }, 1000)
      return
    }
    if (durationTimer) window.clearInterval(durationTimer)
    duration.value = 0
  },
  { immediate: true },
)

async function run(action: () => Promise<string | null>) {
  error.value = ''
  const message = await action()
  if (message) error.value = message
}

async function onRoutingMode(mode: 'rule' | 'global' | 'direct') {
  if (mode === appState.settings.routingMode) return
  await persistSettings({ routingMode: mode })
  await run(() => setRoutingMode(mode))
}

async function onTunToggle(value: boolean | null) {
  await persistSettings({ tunEnabled: value === true })
  await run(() => setTunEnabled(value === true))
}

async function onProxyToggle(value: boolean | null) {
  await persistSettings({ systemProxyEnabled: value === true })
  await run(() => setSystemProxyEnabled(value === true))
}
</script>

<template>
  <div class="page-section">
    <p class="section-label">{{ t('section_connection') }}</p>
    <v-card class="panel-card connection-card">
      <v-card-text>
        <div class="d-flex align-center justify-space-between flex-wrap gap-2 mb-3">
          <h2 class="text-h6 mb-0">
            {{ connected ? t('status_connected') : t('status_disconnected') }}
          </h2>
          <v-btn-toggle
            :model-value="appState.settings.routingMode"
            class="routing-toggle"
            mandatory
            rounded="pill"
            divided
            density="compact"
            :disabled="busy"
            @update:model-value="onRoutingMode($event as 'rule' | 'global' | 'direct')"
          >
            <v-btn
              v-for="mode in routingModes"
              :key="mode.value"
              :value="mode.value"
              size="small"
            >
              {{ mode.label() }}
            </v-btn>
          </v-btn-toggle>
        </div>

        <v-alert v-if="error" color="error" variant="tonal" density="compact" class="mb-3">
          {{ error }}
        </v-alert>

        <v-alert
          v-if="tunNeedsElevation"
          color="warning"
          variant="tonal"
          density="compact"
          class="mb-3"
        >
          {{ t('tun_admin_required') }}
        </v-alert>

        <v-switch
          color="primary"
          :model-value="appState.settings.tunEnabled"
          :label="t('tun_mode')"
          :hint="t('tun_mode_desc')"
          :disabled="busy"
          persistent-hint
          @update:model-value="onTunToggle"
        />
        <v-switch
          color="primary"
          :model-value="appState.settings.systemProxyEnabled"
          :label="t('system_proxy_toggle')"
          :hint="t('system_proxy_toggle_desc')"
          :disabled="busy"
          persistent-hint
          class="mt-1"
          @update:model-value="onProxyToggle"
        />

        <div v-if="connected && appState.vpn" class="metric-grid mt-4">
          <div class="metric-cell">
            <span>{{ t('session_duration') }}</span>
            <strong>{{ formatDuration(duration) }}</strong>
          </div>
          <div class="metric-cell">
            <span>{{ t('session_traffic') }}</span>
            <strong>{{ formatTrafficBytes(appState.vpn.uploadBytes + appState.vpn.downloadBytes) }}</strong>
          </div>
        </div>
      </v-card-text>
    </v-card>
  </div>
</template>
