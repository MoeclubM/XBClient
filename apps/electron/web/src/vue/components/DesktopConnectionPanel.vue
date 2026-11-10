<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { relaunchElevated } from '../../api/system'
import {
  buildTerminalProxyCommand,
  desktopConnectionBusy,
  setRoutingMode,
  setSystemProxyEnabled,
  setTunEnabled,
} from '../../desktop/connection'
import { formatDuration, formatSpeedBps, formatTrafficBytes, publicErrorText } from '../../format'
import { appState, persistSettings, t } from '../state'

const error = ref('')
const duration = ref(0)
const copiedShell = ref(false)
const relaunching = ref(false)
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
const canRelaunchAdmin = computed(
  () => tunNeedsElevation.value && appState.capabilities?.platform === 'windows',
)
const httpPortText = computed(() => {
  if (!appState.httpProxyAddr) return '-'
  return `:${appState.httpProxyAddr.split(':').pop()}`
})
const socksPortText = computed(() => {
  const addr = appState.vpn?.socksAddr || appState.vpn?.tunSocksAddr || ''
  if (!addr) return '-'
  return `:${addr.split(':').pop()}`
})

onUnmounted(() => { if (durationTimer) window.clearInterval(durationTimer) })

watch(
  () => appState.vpn?.sessionId,
  (sessionId) => {
    if (sessionId != null) {
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

async function onRelaunchAdmin() {
  error.value = ''
  relaunching.value = true
  try {
    await relaunchElevated()
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    relaunching.value = false
  }
}

async function copyTerminal(shell: 'powershell' | 'cmd' | 'bash') {
  const cmd = buildTerminalProxyCommand(shell)
  if (!cmd) return
  await navigator.clipboard.writeText(cmd)
  copiedShell.value = true
  window.setTimeout(() => { copiedShell.value = false }, 1500)
}
</script>

<template>
  <div class="page-section">
    <p class="section-label">{{ t('section_connection') }}</p>
    <v-card class="panel-card desktop-conn-card">
      <v-card-text>
        <div class="d-flex align-center justify-space-between flex-wrap gap-2 mb-3">
          <div class="d-flex align-center gap-2">
            <span class="status-dot" :class="{ 'status-dot--on': connected }" />
            <h2 class="text-subtitle-1 font-weight-bold mb-0">
              {{ connected ? t('status_connected') : t('status_disconnected') }}
            </h2>
          </div>
          <div class="d-flex align-center flex-wrap gap-2">
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
            <v-menu v-if="connected">
              <template #activator="{ props }">
                <v-btn v-bind="props" variant="outlined" size="small">
                  {{ copiedShell ? t('copied') : t('copy_terminal_proxy') }}
                </v-btn>
              </template>
              <v-list density="compact" class="pref-menu">
                <v-list-item title="PowerShell" @click="copyTerminal('powershell')" />
                <v-list-item title="CMD" @click="copyTerminal('cmd')" />
                <v-list-item title="Bash" @click="copyTerminal('bash')" />
              </v-list>
            </v-menu>
          </div>
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
          <div class="d-flex align-center justify-space-between flex-wrap gap-2">
            <span>{{ t('tun_admin_required') }}</span>
            <v-btn
              v-if="canRelaunchAdmin"
              size="small"
              color="warning"
              variant="flat"
              :loading="relaunching"
              @click="onRelaunchAdmin"
            >
              {{ t('relaunch_as_admin') }}
            </v-btn>
          </div>
        </v-alert>

        <div class="desktop-toggle-grid">
          <div class="desktop-toggle-item">
            <v-switch
              color="primary"
              density="compact"
              :model-value="appState.settings.tunEnabled"
              :label="t('tun_mode')"
              :hint="t('tun_mode_desc')"
              :disabled="busy"
              persistent-hint
              @update:model-value="onTunToggle"
            />
          </div>
          <div class="desktop-toggle-item">
            <v-switch
              color="primary"
              density="compact"
              :model-value="appState.settings.systemProxyEnabled"
              :label="t('system_proxy_toggle')"
              :hint="t('system_proxy_toggle_desc')"
              :disabled="busy"
              persistent-hint
              @update:model-value="onProxyToggle"
            />
          </div>
        </div>

        <div v-if="connected && appState.vpn" class="metric-grid metric-grid--4 mt-3">
          <div class="metric-cell">
            <span>{{ t('live_speed') }}</span>
            <strong class="mono-text">
              ↑ {{ formatSpeedBps(appState.uploadSpeedBps) }} · ↓ {{ formatSpeedBps(appState.downloadSpeedBps) }}
            </strong>
          </div>
          <div class="metric-cell">
            <span>{{ t('session_traffic') }}</span>
            <strong class="mono-text">
              ↑ {{ formatTrafficBytes(appState.vpn.uploadBytes) }} · ↓ {{ formatTrafficBytes(appState.vpn.downloadBytes) }}
            </strong>
          </div>
          <div class="metric-cell">
            <span>{{ t('session_duration') }} · {{ t('active_sessions') }}</span>
            <strong class="mono-text">{{ formatDuration(duration) }} · {{ appState.activeSessions }}</strong>
          </div>
          <div class="metric-cell">
            <span>{{ t('local_ports') }}</span>
            <strong class="mono-text">HTTP {{ httpPortText }} · SOCKS {{ socksPortText }}</strong>
          </div>
        </div>
      </v-card-text>
    </v-card>
  </div>
</template>
