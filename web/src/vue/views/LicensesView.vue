<script setup lang="ts">
import { openExternal } from '../../api/system'
import { t } from '../state'

const items = [
  ['XBClient', 'https://github.com/MoeclubM/XBClient'],
  ['Aerion', 'https://github.com/MoeclubM/Aerion'],
  ['NodeRS', 'https://github.com/MoeclubM/NodeRS'],
  ['Tauri', 'https://tauri.app'],
  ['Vue', 'https://vuejs.org'],
  ['Vuetify', 'https://vuetifyjs.com'],
]
</script>

<template>
  <section class="liquid-page">
    <header class="liquid-header">
      <div>
        <p class="eyebrow">{{ t('open_source') }}</p>
        <h1>{{ t('licenses') }}</h1>
        <p class="muted">{{ t('licenses_desc') }}</p>
      </div>
    </header>

    <div class="stack">
      <v-card v-for="[name, url] in items" :key="name" class="glass-card pa-4">
        <div class="section-row">
          <strong>{{ name }}</strong>
          <v-btn variant="outlined" @click="openExternal(url)">{{ t('open_link') }}</v-btn>
        </div>
      </v-card>
    </div>
  </section>
</template>
