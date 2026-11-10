<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { openAppDataDir, relaunchElevated } from '../../api/system'
import { clearAllLogs, logsPaused, setLogsPaused } from '../../desktop/telemetry'
import { publicErrorText } from '../../format'
import { appState, t } from '../state'

type LevelFilter = 'all' | 'info' | 'warn' | 'error'

const levelFilter = ref<LevelFilter>('all')
const keyword = ref('')
const copied = ref(false)
const relaunching = ref(false)
const error = ref('')
const streamEl = ref<HTMLElement | null>(null)
const stickToBottom = ref(true)

const tunActive = computed(() => Boolean(appState.vpn && !appState.vpn.socksAddr && !appState.vpn.routeMode))
const canElevate = computed(
  () => appState.capabilities?.platform === 'windows' && appState.capabilities?.tun_elevated === false,
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

function normalizedLevel(raw: string): 'info' | 'warn' | 'error' {
  const lower = raw.toLowerCase()
  if (lower === 'error' || lower === 'fatal') return 'error'
  if (lower === 'warn' || lower === 'warning') return 'warn'
  return 'info'
}

const filteredLogs = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return appState.logs.filter((entry) => {
    const lvl = normalizedLevel(entry.level)
    if (levelFilter.value !== 'all' && lvl !== levelFilter.value) return false
    if (q && !entry.message.toLowerCase().includes(q) && !entry.level.toLowerCase().includes(q)) return false
    return true
  })
})

function formatTime(ts: number): string {
  const d = new Date(ts)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  const ms = String(d.getMilliseconds()).padStart(3, '0')
  return `${hh}:${mm}:${ss}.${ms}`
}

function onStreamScroll() {
  const el = streamEl.value
  if (!el) return
  stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 32
}

async function scrollToEnd() {
  await nextTick()
  const el = streamEl.value
  if (el && stickToBottom.value) el.scrollTop = el.scrollHeight
}

watch(() => filteredLogs.value.length, () => {
  if (!logsPaused.value) void scrollToEnd()
})

onMounted(() => {
  void scrollToEnd()
})

async function handleOpenDataDir() {
  error.value = ''
  try {
    await openAppDataDir()
  } catch (err) {
    error.value = publicErrorText(err)
  }
}

async function handleElevate() {
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

async function handleCopy() {
  const content = filteredLogs.value
    .map((item) => `${formatTime(item.timestamp)} [${item.level.toUpperCase()}] ${item.message}`)
    .join('\n')
  await navigator.clipboard.writeText(content)
  copied.value = true
  window.setTimeout(() => { copied.value = false }, 1500)
}

async function handleClear() {
  error.value = ''
  try {
    await clearAllLogs()
  } catch (err) {
    error.value = publicErrorText(err)
  }
}
</script>

<template>
  <section class="liquid-page liquid-page--wide">
    <div class="page-header">
      <div class="page-header-bar" />
      <div class="page-header-content">
        <h1>{{ t('nav_logs') }}</h1>
      </div>
      <v-btn variant="outlined" size="small" @click="handleOpenDataDir">
        {{ t('open_data_dir') }}
      </v-btn>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" density="compact" class="mb-3">
      {{ error }}
    </v-alert>

    <div class="page-section">
      <p class="section-label">{{ t('section_diagnostics') }}</p>
      <div class="diag-grid">
        <div class="metric-cell">
          <span>{{ t('diag_privilege') }}</span>
          <div class="d-flex align-center justify-space-between gap-2">
            <strong>{{ appState.capabilities?.tun_elevated ? t('diag_admin') : t('diag_standard') }}</strong>
            <v-btn
              v-if="canElevate"
              size="x-small"
              color="warning"
              variant="tonal"
              :loading="relaunching"
              @click="handleElevate"
            >
              {{ t('relaunch_as_admin') }}
            </v-btn>
          </div>
        </div>
        <div class="metric-cell">
          <span>{{ t('tun_mode') }}</span>
          <strong>{{ tunActive ? t('status_connected') : t('status_disconnected') }}</strong>
        </div>
        <div class="metric-cell">
          <span>{{ t('system_proxy_toggle') }}</span>
          <strong>{{ appState.systemProxyActive ? t('status_connected') : t('status_disconnected') }}</strong>
        </div>
        <div class="metric-cell">
          <span>{{ t('local_ports') }}</span>
          <strong class="mono-text">HTTP {{ httpPortText }} · SOCKS {{ socksPortText }}</strong>
        </div>
      </div>
    </div>

    <div class="page-section mb-0">
      <v-card class="panel-card">
        <v-card-text>
          <div class="log-toolbar mb-3">
            <v-btn-toggle
              v-model="levelFilter"
              mandatory
              divided
              density="compact"
              variant="outlined"
            >
              <v-btn value="all" size="small">{{ t('filter_all') }}</v-btn>
              <v-btn value="info" size="small">INFO</v-btn>
              <v-btn value="warn" size="small">WARN</v-btn>
              <v-btn value="error" size="small">ERROR</v-btn>
            </v-btn-toggle>
            <v-text-field
              v-model="keyword"
              class="log-search"
              :placeholder="t('logs_search')"
              variant="outlined"
              density="compact"
              clearable
              hide-details
            />
            <div class="d-flex align-center gap-2">
              <v-btn
                size="small"
                :variant="logsPaused ? 'flat' : 'tonal'"
                :color="logsPaused ? 'warning' : undefined"
                @click="setLogsPaused(!logsPaused)"
              >
                {{ logsPaused ? t('logs_resume') : t('logs_pause') }}
              </v-btn>
              <v-btn
                size="small"
                variant="outlined"
                :disabled="filteredLogs.length === 0"
                @click="handleCopy"
              >
                {{ copied ? t('copied') : t('copy') }}
              </v-btn>
              <v-btn
                size="small"
                variant="outlined"
                :disabled="appState.logs.length === 0"
                @click="handleClear"
              >
                {{ t('common_clear_selection') }}
              </v-btn>
            </div>
          </div>

          <div ref="streamEl" class="log-stream" @scroll="onStreamScroll">
            <p v-if="filteredLogs.length === 0" class="muted mb-0 pa-2">
              {{ t('logs_empty') }}
            </p>
            <div
              v-for="entry in filteredLogs"
              :key="entry.id"
              class="log-row"
              :class="`log-row--${normalizedLevel(entry.level)}`"
            >
              <span class="log-row__time">{{ formatTime(entry.timestamp) }}</span>
              <span class="log-row__level">{{ entry.level.toUpperCase() }}</span>
              <span class="log-row__msg">{{ entry.message }}</span>
            </div>
          </div>
        </v-card-text>
      </v-card>
    </div>
  </section>
</template>
