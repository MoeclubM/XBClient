<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { failureText } from '../../api/helpers'
import { xboardRequest } from '../../api/xboard'
import { formatTrafficBytes, formatUnixDateTime, numericValue, publicErrorText } from '../../format'
import { appState, t } from '../state'

interface XboardBody {
  data?: unknown
  message?: string
  status?: string
}

interface TrafficLog {
  download: number
  upload: number
  rate: number
  recordedAt: number
}

const loading = ref(false)
const error = ref('')
const logs = ref<TrafficLog[]>([])

const rawTotal = computed(() => logs.value.reduce((sum, item) => sum + item.upload + item.download, 0))
const billedTotal = computed(() => logs.value.reduce((sum, item) => sum + (item.upload + item.download) * item.rate, 0))

function logRows(value: unknown): TrafficLog[] {
  return (value as Array<Record<string, unknown>>).map((item) => ({
    download: numericValue(item.d),
    upload: numericValue(item.u),
    rate: numericValue(item.server_rate),
    recordedAt: Math.round(numericValue(item.record_at)),
  }))
}

async function loadLogs() {
  loading.value = true
  error.value = ''
  try {
    const response = await xboardRequest<XboardBody>('traffic_logs', { baseUrl: appState.baseUrl, authData: appState.authData })
    const text = failureText(response)
    if (text) {
      error.value = text
      return
    }
    logs.value = logRows(response.body.data)
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

onMounted(loadLogs)
</script>

<template>
  <section class="liquid-page">
    <div class="page-header">
      <div class="page-header-bar subtitle" />
      <div class="page-header-content">
        <h1>{{ t('nav_traffic_logs') }}</h1>
        <p>{{ t('service_traffic_desc') }}</p>
      </div>
      <v-btn variant="outlined" :loading="loading" @click="loadLogs">
        {{ loading ? t('refreshing') : t('refresh') }}
      </v-btn>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <div class="metric-grid page-section">
      <div class="metric-cell">
        <span>{{ t('traffic_raw_total') }}</span>
        <strong>{{ formatTrafficBytes(rawTotal) }}</strong>
      </div>
      <div class="metric-cell">
        <span>{{ t('traffic_billed_total') }}</span>
        <strong>{{ formatTrafficBytes(billedTotal) }}</strong>
      </div>
    </div>

    <div class="page-section">
      <p class="section-label">{{ t('traffic_log_list') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <div class="stack">
            <div v-for="log in logs" :key="`${log.recordedAt}-${log.upload}-${log.download}`" class="glass-chip">
              <div class="row-chip">
                <span>
                  <strong>{{ formatTrafficBytes((log.upload + log.download) * log.rate) }}</strong>
                  <small>{{ formatUnixDateTime(log.recordedAt) }} · x{{ log.rate }}</small>
                </span>
                <span class="glass-badge">{{ formatTrafficBytes(log.upload + log.download) }}</span>
              </div>
              <p class="muted">
                ↑ {{ formatTrafficBytes(log.upload) }} · ↓ {{ formatTrafficBytes(log.download) }}
              </p>
            </div>
          </div>
          <p v-if="!loading && !logs.length" class="muted">{{ t('traffic_logs_empty') }}</p>
        </v-card-text>
      </v-card>
    </div>
  </section>
</template>
