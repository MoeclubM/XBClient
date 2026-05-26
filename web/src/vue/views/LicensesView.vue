<script setup lang="ts">
import { onMounted, ref } from 'vue'

const text = ref('')

onMounted(async () => {
  text.value = await fetch('/open_source_licenses.txt').then((response) => response.text()).catch(() => '无法加载开源许可。')
})
</script>

<template>
  <section class="liquid-page">
    <header class="liquid-header">
      <div>
        <p class="eyebrow">Open Source</p>
        <h1>开源许可</h1>
      </div>
    </header>
    <v-card class="glass-card pa-4">
      <pre class="license-text">{{ text }}</pre>
    </v-card>
  </section>
</template>
