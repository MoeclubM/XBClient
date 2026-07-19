<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { failureText, field } from '../../api/helpers'
import { xboardRequest, type XboardBody } from '../../api/xboard'
import { formatUnixDateTime, numericValue, publicErrorText } from '../../format'
import { appState, t } from '../state'

interface TicketItem {
  id: number
  subject: string
  level: number
  status: number
  createdAt: number
  updatedAt: number
}

interface TicketMessage {
  id: number
  message: string
  createdAt: number
  isMe: boolean
}

const tickets = ref<TicketItem[]>([])
const selectedTicket = ref<TicketItem | null>(null)
const detailRows = ref<TicketMessage[]>([])
const subject = ref('')
const content = ref('')
const level = ref(0)
const reply = ref('')
const error = ref('')
const message = ref('')
const loading = ref(false)

const ticketClosed = computed(() => selectedTicket.value?.status === 1)

const levelOptions = [
  { value: 0, label: t('ticket_level_low') },
  { value: 1, label: t('ticket_level_medium') },
  { value: 2, label: t('ticket_level_high') },
]

function ticketRows(value: unknown): TicketItem[] {
  return (value as Array<Record<string, unknown>>).map((item) => ({
    id: Math.round(numericValue(item.id)),
    subject: field(item, 'subject'),
    level: Math.round(numericValue(item.level)),
    status: Math.round(numericValue(item.status)),
    createdAt: Math.round(numericValue(item.created_at)),
    updatedAt: Math.round(numericValue(item.updated_at)),
  }))
}

function messageRows(value: unknown): TicketMessage[] {
  return (value as Array<Record<string, unknown>>).map((item) => {
    if (typeof item.is_me !== 'boolean') throw new Error('ticket message is_me must be boolean')
    return {
      id: Math.round(numericValue(item.id)),
      message: field(item, 'message'),
      createdAt: Math.round(numericValue(item.created_at)),
      isMe: item.is_me,
    }
  })
}

function ticketStatusText(status: number): string {
  if (status === 0) return t('ticket_status_open')
  if (status === 1) return t('ticket_status_closed')
  throw new Error(`ticket status is invalid: ${status}`)
}

function ticketLevelText(value: number): string {
  if (value === 0) return t('ticket_level_low')
  if (value === 1) return t('ticket_level_medium')
  if (value === 2) return t('ticket_level_high')
  throw new Error(`ticket level is invalid: ${value}`)
}

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
    tickets.value = ticketRows(response.body.data)
    const active = tickets.value.find((ticket) => ticket.id === selectedTicket.value?.id) || tickets.value[0]
    if (active) await selectTicket(active)
    else {
      selectedTicket.value = null
      detailRows.value = []
    }
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

async function selectTicket(ticket: TicketItem) {
  selectedTicket.value = ticket
  detailRows.value = []
  const response = await xboardRequest<XboardBody<Record<string, unknown>>>('tickets', {
    baseUrl: appState.baseUrl,
    authData: appState.authData,
    params: { id: ticket.id },
  })
  const text = failureText(response)
  if (text) {
    error.value = text
    return
  }
  detailRows.value = messageRows(response.body.data!.message)
}

async function createTicket() {
  error.value = ''
  message.value = ''
  const response = await xboardRequest<XboardBody>('ticket_save', {
    baseUrl: appState.baseUrl,
    authData: appState.authData,
    params: { subject: subject.value, level: level.value, message: content.value },
  })
  const text = failureText(response)
  if (text) {
    error.value = text
    return
  }
  subject.value = ''
  content.value = ''
  level.value = 0
  message.value = t('ticket_created')
  await loadTickets()
}

async function replyTicket() {
  const ticket = selectedTicket.value as TicketItem
  error.value = ''
  message.value = ''
  const response = await xboardRequest<XboardBody>('ticket_reply', {
    baseUrl: appState.baseUrl,
    authData: appState.authData,
    params: { id: ticket.id, message: reply.value },
  })
  const text = failureText(response)
  if (text) {
    error.value = text
    return
  }
  reply.value = ''
  await selectTicket(ticket)
}

async function closeTicket() {
  const ticket = selectedTicket.value as TicketItem
  error.value = ''
  message.value = ''
  const response = await xboardRequest<XboardBody>('ticket_close', {
    baseUrl: appState.baseUrl,
    authData: appState.authData,
    params: { id: ticket.id },
  })
  const text = failureText(response)
  if (text) {
    error.value = text
    return
  }
  message.value = t('ticket_closed')
  await loadTickets()
}

onMounted(loadTickets)
</script>

<template>
  <section class="liquid-page">
    <div class="page-header">
      <div class="page-header-bar subtitle" />
      <div class="page-header-content">
        <h1>{{ t('ticket_center') }}</h1>
        <p>{{ appState.email || t('service_tickets_desc') }}</p>
      </div>
      <v-btn variant="outlined" :loading="loading" @click="loadTickets">
        {{ loading ? t('refreshing') : t('refresh') }}
      </v-btn>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="message" color="primary" variant="tonal" class="mb-4">{{ message }}</v-alert>

    <div class="page-section">
      <p class="section-label">{{ t('ticket_new') }}</p>
      <v-card class="panel-card">
        <v-card-text>
          <v-text-field v-model="subject" :label="t('ticket_title')" variant="outlined" density="comfortable" />
          <v-select
            v-model="level"
            class="mt-2"
            :label="t('ticket_level')"
            :items="levelOptions"
            item-title="label"
            item-value="value"
            variant="outlined"
            density="comfortable"
          />
          <v-textarea v-model="content" :label="t('ticket_message')" rows="3" variant="outlined" class="mt-2" />
          <v-btn color="primary" block class="mt-2" @click="createTicket">{{ t('ticket_submit') }}</v-btn>
        </v-card-text>
      </v-card>
    </div>

    <v-row>
      <v-col cols="12" md="5">
        <p class="section-label">{{ t('ticket_list') }}</p>
        <v-card class="panel-card">
          <v-list lines="two" density="comfortable">
            <v-list-item
              v-for="ticket in tickets"
              :key="ticket.id"
              :active="Boolean(selectedTicket && selectedTicket.id === ticket.id)"
              active-color="primary"
              @click="selectTicket(ticket)"
            >
              <v-list-item-title>{{ ticket.subject }}</v-list-item-title>
              <v-list-item-subtitle>
                {{ ticketStatusText(ticket.status) }} · {{ ticketLevelText(ticket.level) }} · {{ formatUnixDateTime(ticket.updatedAt) }}
              </v-list-item-subtitle>
            </v-list-item>
          </v-list>
          <v-card-text v-if="!loading && !tickets.length">
            <p class="muted">{{ t('tickets_empty') }}</p>
          </v-card-text>
        </v-card>
      </v-col>

      <v-col cols="12" md="7">
        <div class="section-row mb-3">
          <p class="section-label mb-0">
            {{ selectedTicket ? selectedTicket.subject : t('ticket_detail') }}
          </p>
          <v-btn
            v-if="selectedTicket && !ticketClosed"
            variant="outlined"
            size="small"
            @click="closeTicket"
          >
            {{ t('ticket_close') }}
          </v-btn>
        </div>
        <v-card class="panel-card">
          <v-card-text>
            <div class="stack">
              <v-card
                v-for="row in detailRows"
                :key="row.id"
                :color="row.isMe ? 'primary' : undefined"
                :variant="row.isMe ? 'tonal' : 'outlined'"
              >
                <v-card-text>
                  <div class="d-flex align-center justify-space-between gap-2 mb-2">
                    <strong>{{ row.isMe ? t('ticket_sender_me') : t('ticket_sender_support') }}</strong>
                    <span class="text-caption text-medium-emphasis">{{ formatUnixDateTime(row.createdAt) }}</span>
                  </div>
                  <p class="preline mb-0">{{ row.message }}</p>
                </v-card-text>
              </v-card>
            </div>
            <div v-if="selectedTicket && !ticketClosed" class="mt-4">
              <v-textarea v-model="reply" :label="t('ticket_reply')" rows="3" variant="outlined" />
              <v-btn color="primary" block class="mt-2" @click="replyTicket">{{ t('ticket_send') }}</v-btn>
            </div>
            <p v-else-if="selectedTicket" class="muted mt-4">{{ t('ticket_closed_no_reply') }}</p>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>
  </section>
</template>
