<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { xboardRequest } from '../../api/xboard'
import { dataRows, failureText, field, rowId, type Row } from '../../api/helpers'
import { publicErrorText } from '../../format'
import { appState, t } from '../state'

interface XboardBody<T = unknown> {
  data?: T
  message?: string
  status?: string
}

const tickets = ref<Row[]>([])
const selectedTicket = ref<Row | null>(null)
const detailRows = ref<Row[]>([])
const subject = ref('')
const message = ref('')
const reply = ref('')
const error = ref('')
const loading = ref(false)

async function loadTickets() {
  loading.value = true
  error.value = ''
  try {
    const response = await xboardRequest<XboardBody>('tickets', { baseUrl: appState.baseUrl, authData: appState.authData })
    const text = failureText(response)
    if (text) {
      error.value = text
      return
    }
    tickets.value = dataRows(response.body?.data)
    if (!selectedTicket.value && tickets.value[0]) await selectTicket(tickets.value[0])
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

async function selectTicket(ticket: Row) {
  selectedTicket.value = ticket
  detailRows.value = []
  const id = rowId(ticket)
  const response = await xboardRequest<XboardBody>('tickets', {
    baseUrl: appState.baseUrl,
    authData: appState.authData,
    params: { id },
  })
  const text = failureText(response)
  if (text) {
    error.value = text
    return
  }
  detailRows.value = dataRows(response.body?.data)
}

async function createTicket() {
  const response = await xboardRequest<XboardBody>('ticket_save', {
    baseUrl: appState.baseUrl,
    authData: appState.authData,
    params: { subject: subject.value, message: message.value },
  })
  const text = failureText(response)
  if (text) {
    error.value = text
    return
  }
  subject.value = ''
  message.value = ''
  await loadTickets()
}

async function replyTicket() {
  if (!selectedTicket.value) return
  const response = await xboardRequest<XboardBody>('ticket_reply', {
    baseUrl: appState.baseUrl,
    authData: appState.authData,
    params: { id: rowId(selectedTicket.value), message: reply.value },
  })
  const text = failureText(response)
  if (text) {
    error.value = text
    return
  }
  reply.value = ''
  await selectTicket(selectedTicket.value)
}

onMounted(loadTickets)
</script>

<template>
  <section class="liquid-page">
    <header class="liquid-header">
      <div>
        <p class="eyebrow">{{ appState.email || t('nav_services') }}</p>
        <h1>{{ t('nav_services') }}</h1>
      </div>
      <v-btn class="glass-button" :loading="loading" @click="loadTickets">{{ loading ? t('refreshing') : t('refresh') }}</v-btn>
    </header>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <v-card class="glass-card pa-4 mb-4">
      <p class="eyebrow">{{ t('ticket_new') }}</p>
      <v-text-field v-model="subject" :label="t('ticket_title')" />
      <v-textarea v-model="message" :label="t('ticket_message')" rows="3" variant="outlined" />
      <v-btn color="primary" block @click="createTicket">{{ t('ticket_submit') }}</v-btn>
    </v-card>

    <div class="tickets-grid">
      <v-card class="glass-card pa-3">
        <p class="eyebrow">{{ t('ticket_list') }}</p>
        <button
          v-for="ticket in tickets"
          :key="rowId(ticket)"
          class="node-row"
          :class="{ active: selectedTicket && rowId(selectedTicket) === rowId(ticket) }"
          @click="selectTicket(ticket)"
        >
          <span>
            <strong>{{ field(ticket, ['subject', 'title']) || `#${rowId(ticket)}` }}</strong>
            <small>{{ field(ticket, ['status']) || 'open' }}</small>
          </span>
        </button>
      </v-card>

      <v-card class="glass-card pa-4">
        <p class="eyebrow">{{ selectedTicket ? field(selectedTicket, ['subject', 'title']) : t('ticket_detail') }}</p>
        <div class="stack">
          <div v-for="(row, index) in detailRows" :key="`${rowId(row)}-${index}`" class="glass-chip">
            <strong>{{ field(row, ['user_name', 'email', 'role']) || t('message_sender') }}</strong>
            <span class="preline">{{ field(row, ['message', 'content', 'reply']) }}</span>
          </div>
        </div>
        <div v-if="selectedTicket" class="mt-4">
          <v-textarea v-model="reply" :label="t('ticket_reply')" rows="3" variant="outlined" />
          <v-btn color="primary" block @click="replyTicket">{{ t('ticket_send') }}</v-btn>
        </div>
      </v-card>
    </div>
  </section>
</template>
