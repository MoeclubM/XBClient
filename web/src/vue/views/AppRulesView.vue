<script setup lang="ts">
import { useRouter } from 'vue-router'
import { t } from '../state'

const router = useRouter()
</script>

<template>
  <section class="liquid-page">
    <div class="page-header">
      <v-btn variant="text" size="small" @click="router.push('/settings')">‹</v-btn>
      <div class="page-header-bar subtitle" />
      <div class="page-header-content">
        <p class="muted">{{ t('nav_settings') }}</p>
        <h1>{{ t('page_app_rules_title') }}</h1>
      </div>
    </div>

    <v-alert color="primary" variant="tonal">
      {{ t('vpn_app_rules_android_only') }}
    </v-alert>
  </section>
</template>
