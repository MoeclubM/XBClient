<script setup lang="ts">
import { openExternal } from '../../api/system'
import { t } from '../state'

const items = [
  ['XBClient', 'https://github.com/MoeclubM/XBClient'],
  ['Aerion', 'https://github.com/MoeclubM/Aerion'],
  ['NodeRS', 'https://github.com/MoeclubM/NodeRS'],
  ['Electron', 'https://www.electronjs.org'],
  ['Vue', 'https://vuejs.org'],
  ['Vuetify', 'https://vuetifyjs.com'],
]
</script>

<template>
  <section class="liquid-page">
    <div class="page-header">
      <div class="page-header-bar subtitle" />
      <div class="page-header-content">
        <p class="muted">{{ t('open_source') }}</p>
        <h1>{{ t('licenses') }}</h1>
      </div>
    </div>

    <div class="stack">
      <v-card v-for="[name, url] in items" :key="name" class="panel-card">
        <v-card-text>
          <div class="d-flex align-center justify-space-between">
            <strong>{{ name }}</strong>
            <v-btn variant="outlined" size="small" @click="openExternal(url)">{{ t('open_link') }}</v-btn>
          </div>
        </v-card-text>
      </v-card>
    </div>
  </section>
</template>
