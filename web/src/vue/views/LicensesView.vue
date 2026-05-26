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
        <p class="eyebrow">Open Source</p>
        <h1>{{ t('licenses') }}</h1>
        <p class="muted">Tauri 多平台版依赖的主要项目 / Core open source project dependencies.</p>
      </div>
    </header>

    <div class="stack">
      <v-card v-for="[name, url] in items" :key="name" class="glass-card pa-4">
        <div class="section-row">
          <strong>{{ name }}</strong>
          <v-btn variant="outlined" @click="openExternal(url)">Open</v-btn>
        </div>
      </v-card>
    </div>
  </section>
</template>
