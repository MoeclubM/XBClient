<script setup lang="ts">
import { useRouter } from 'vue-router'
import { appState, t } from '../state'

const router = useRouter()

const serviceItems = [
  { path: '/tickets', icon: '✉', title: () => t('ticket_center'), desc: () => t('service_tickets_desc') },
  { path: '/promotion', icon: '↗', title: () => t('nav_promotion'), desc: () => t('service_promotion_desc') },
  { path: '/traffic', icon: '⇅', title: () => t('nav_traffic_logs'), desc: () => t('service_traffic_desc') },
]
</script>

<template>
  <section class="liquid-page">
    <div class="page-header">
      <div class="page-header-bar subtitle" />
      <div class="page-header-content">
        <h1>{{ t('service_center') }}</h1>
        <p>{{ appState.email || t('service_center_desc') }}</p>
      </div>
    </div>

    <div class="service-grid">
      <v-card
        v-for="item in serviceItems"
        :key="item.path"
        class="panel-card service-card interactive-card"
        role="link"
        tabindex="0"
        @click="router.push(item.path)"
        @keydown.enter="router.push(item.path)"
        @keydown.space.prevent="router.push(item.path)"
      >
        <v-card-text>
          <v-avatar color="primary" variant="tonal" class="mb-4">{{ item.icon }}</v-avatar>
          <h2 class="text-h6">{{ item.title() }}</h2>
          <p class="muted">{{ item.desc() }}</p>
          <v-btn color="primary" variant="tonal" class="mt-4">
            {{ t('open_link') }}
          </v-btn>
        </v-card-text>
      </v-card>
    </div>
  </section>
</template>
