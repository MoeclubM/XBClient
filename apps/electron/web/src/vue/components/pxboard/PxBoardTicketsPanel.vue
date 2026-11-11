<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { pxboardBatch, pxboardPublicUrl, pxboardRpc } from '../../../api/pxboard'
import { publicErrorText } from '../../../format'
import { t } from '../../state'

interface TicketMessage {
  id: string
  author?: string
  content: string
  createdAt?: string
}

interface TicketRow {
  id: string
  subject: string
  status: string
  updatedAt?: string
  thread?: string
  messages: TicketMessage[]
}

interface HelpArticle {
  id: string
  title: string
  content: string
  published?: boolean
}

const tab = ref<'tickets' | 'help'>('tickets')
const loading = ref(false)
const submitting = ref(false)
const uploading = ref(false)
const error = ref('')
const message = ref('')

const tickets = ref<TicketRow[]>([])
const selectedId = ref('')
const subject = ref('')
const content = ref('')
const replyText = ref('')

const articles = ref<HelpArticle[]>([])
const search = ref('')

const currentTicket = computed(() => tickets.value.find((item) => item.id === selectedId.value) || null)
const filteredArticles = computed(() => {
  const q = search.value.trim().toLowerCase()
  return articles.value.filter(
    (row) => row.published !== false && (!q || (row.title + ' ' + row.content).toLowerCase().includes(q)),
  )
})

function statusLabel(status: string): string {
  if (status === 'open') return '等待回复'
  if (status === 'answered') return '已回复'
  if (status === 'closed') return t('ticket_status_closed')
  return status
}

function formatIso(value?: string): string {
  if (!value) return ''
  const ts = Date.parse(value)
  return Number.isFinite(ts) ? new Date(ts).toLocaleString() : value
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const batch = await pxboardBatch([
      { key: 'tickets', service: 'support', method: 'tickets.list', input: { mine: true } },
      { key: 'articles', service: 'support', method: 'articles.list' },
    ])
    if (batch.tickets?.status === 200 && Array.isArray(batch.tickets.data)) {
      tickets.value = (batch.tickets.data as Array<Record<string, any>>).map((row) => ({
        id: String(row.id ?? ''),
        subject: String(row.subject ?? ''),
        status: String(row.status ?? 'open'),
        updatedAt: typeof row.updatedAt === 'string' ? row.updatedAt : '',
        thread: typeof row.thread === 'string' ? row.thread : '',
        messages: Array.isArray(row.messages)
          ? row.messages.map((m: Record<string, any>, idx: number) => ({
              id: String(m.id ?? idx),
              author: typeof m.author === 'string' ? m.author : '用户',
              content: String(m.content ?? ''),
              createdAt: typeof m.createdAt === 'string' ? m.createdAt : '',
            }))
          : [],
      }))
      if (!selectedId.value && tickets.value.length > 0) {
        selectedId.value = tickets.value[0].id
      } else if (selectedId.value && !tickets.value.some((item) => item.id === selectedId.value)) {
        selectedId.value = tickets.value[0]?.id || ''
      }
    }
    if (batch.articles?.status === 200 && Array.isArray(batch.articles.data)) {
      articles.value = (batch.articles.data as Array<Record<string, any>>).map((row) => ({
        id: String(row.id ?? ''),
        title: String(row.title ?? ''),
        content: String(row.content ?? ''),
        published: row.published !== false,
      }))
    }
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

async function uploadImage(event: Event, target: 'create' | 'reply') {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 2 * 1024 * 1024) {
    error.value = '图片大小不能超过 2 MB'
    return
  }
  uploading.value = true
  error.value = ''
  try {
    const base64 = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result).split(',')[1] || '')
      reader.onerror = () => reject(new Error('读取图片失败'))
      reader.readAsDataURL(file)
    })
    const created = await pxboardRpc<{ url: string }>('support', 'attachments.create', { data: base64 })
    const md = '![图片](' + pxboardPublicUrl(created.url) + ')'
    if (target === 'create') {
      content.value = content.value ? content.value + '\n' + md : md
    } else {
      replyText.value = replyText.value ? replyText.value + '\n' + md : md
    }
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    uploading.value = false
  }
}

async function createTicket() {
  if (!subject.value.trim() || !content.value.trim() || submitting.value) return
  submitting.value = true
  error.value = ''
  message.value = ''
  try {
    const created = await pxboardRpc<{ id: string }>('support', 'tickets.create', {
      subject: subject.value.trim(),
      content: content.value.trim(),
    })
    subject.value = ''
    content.value = ''
    selectedId.value = created.id
    message.value = '工单已提交'
    await load()
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    submitting.value = false
  }
}

async function sendReply() {
  if (!currentTicket.value || !replyText.value.trim() || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    await pxboardRpc('support', 'tickets.reply', {
      id: currentTicket.value.id,
      message: replyText.value.trim(),
    })
    replyText.value = ''
    await load()
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    submitting.value = false
  }
}

async function closeTicket() {
  if (!currentTicket.value || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    await pxboardRpc('support', 'tickets.close', { id: currentTicket.value.id })
    message.value = '工单已关闭'
    await load()
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="liquid-page">
    <div class="page-header">
      <div class="page-header-bar" />
      <div class="page-header-content">
        <h1>{{ t('ticket_center') }}</h1>
      </div>
      <v-btn variant="outlined" size="small" :loading="loading" @click="load">
        {{ loading ? t('refreshing') : t('refresh') }}
      </v-btn>
    </div>

    <v-alert v-if="error" color="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="message" color="primary" variant="tonal" class="mb-4">{{ message }}</v-alert>

    <v-tabs v-model="tab" density="compact" class="mb-4">
      <v-tab value="tickets">{{ t('ticket_list') }}</v-tab>
      <v-tab value="help">使用帮助</v-tab>
    </v-tabs>

    <template v-if="tab === 'tickets'">
      <div class="page-section">
        <p class="section-label">{{ t('ticket_new') }}</p>
        <v-card class="panel-card">
          <v-card-text>
            <v-text-field v-model="subject" :label="t('ticket_title')" density="comfortable" />
            <v-textarea v-model="content" :label="t('ticket_message')" rows="3" auto-grow density="comfortable" />
            <div class="d-flex align-center justify-space-between mt-2">
              <v-btn variant="text" size="small" tag="label" :loading="uploading">
                上传图片
                <input type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden @change="uploadImage($event, 'create')">
              </v-btn>
              <v-btn
                color="primary"
                :loading="submitting"
                :disabled="!subject.trim() || !content.trim() || uploading"
                @click="createTicket"
              >
                {{ t('ticket_submit') }}
              </v-btn>
            </div>
          </v-card-text>
        </v-card>
      </div>

      <div class="page-section">
        <p class="section-label">{{ t('ticket_list') }}</p>
        <v-card v-if="!tickets.length && !loading" class="panel-card">
          <v-card-text><p class="muted mb-0">{{ t('tickets_empty') }}</p></v-card-text>
        </v-card>
        <div v-else class="stack">
          <v-card
            v-for="item in tickets"
            :key="item.id"
            class="panel-card cursor-pointer"
            :variant="item.id === selectedId ? 'outlined' : 'elevated'"
            @click="selectedId = item.id"
          >
            <v-card-text class="d-flex align-center justify-space-between py-3">
              <div>
                <p class="text-body-1 font-weight-bold mb-0">{{ item.subject }}</p>
                <p class="text-caption muted mb-0">{{ formatIso(item.updatedAt) }}</p>
              </div>
              <v-chip size="small" variant="tonal" :color="item.status === 'closed' ? undefined : 'primary'">
                {{ statusLabel(item.status) }}
              </v-chip>
            </v-card-text>
          </v-card>
        </div>
      </div>

      <div v-if="currentTicket" class="page-section">
        <div class="d-flex align-center justify-space-between mb-2">
          <p class="section-label mb-0">{{ currentTicket.subject }}</p>
          <v-btn
            v-if="currentTicket.status !== 'closed'"
            variant="text"
            color="error"
            size="small"
            :disabled="submitting"
            @click="closeTicket"
          >
            关闭工单
          </v-btn>
        </div>
        <v-card class="panel-card">
          <v-card-text>
            <p v-if="currentTicket.thread" class="preline mb-3">{{ currentTicket.thread }}</p>
            <div class="stack">
              <div v-for="entry in currentTicket.messages" :key="entry.id" class="pa-3 rounded bg-surface-light">
                <div class="d-flex justify-space-between text-caption muted mb-1">
                  <strong>{{ entry.author }}</strong>
                  <span>{{ formatIso(entry.createdAt) }}</span>
                </div>
                <p class="preline mb-0">{{ entry.content }}</p>
              </div>
            </div>
            <div v-if="currentTicket.status !== 'closed'" class="mt-4">
              <v-textarea
                v-model="replyText"
                :label="t('ticket_reply')"
                rows="2"
                auto-grow
                density="comfortable"
              />
              <div class="d-flex align-center justify-space-between mt-2">
                <v-btn variant="text" size="small" tag="label" :loading="uploading">
                  上传图片
                  <input type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden @change="uploadImage($event, 'reply')">
                </v-btn>
                <v-btn
                  color="primary"
                  :loading="submitting"
                  :disabled="!replyText.trim() || uploading"
                  @click="sendReply"
                >
                  {{ t('ticket_send') }}
                </v-btn>
              </div>
            </div>
          </v-card-text>
        </v-card>
      </div>
    </template>

    <template v-else>
      <v-text-field
        v-model="search"
        label="搜索帮助文档"
        density="comfortable"
        clearable
        class="mb-3"
      />
      <v-expansion-panels v-if="filteredArticles.length" variant="accordion">
        <v-expansion-panel v-for="art in filteredArticles" :key="art.id" :title="art.title">
          <v-expansion-panel-text>
            <div class="preline">{{ art.content }}</div>
          </v-expansion-panel-text>
        </v-expansion-panel>
      </v-expansion-panels>
      <v-card v-else class="panel-card">
        <v-card-text><p class="muted mb-0">暂无匹配的帮助文档。</p></v-card-text>
      </v-card>
    </template>
  </section>
</template>
