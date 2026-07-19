<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  closeWindow,
  isWindowMaximized,
  maximizeWindow,
  minimizeWindow,
  onWindowMaximizedChanged,
} from '../../platform/electron'
import { t } from '../state'

const props = defineProps<{ title: string }>()

const maximized = ref(false)
const isMac = window.electronAPI.getDesktopPlatform() === 'darwin'
let cleanupMaximized: (() => void) | null = null

const displayTitle = computed(() => props.title.trim())

onMounted(async () => {
  maximized.value = await isWindowMaximized()
  cleanupMaximized = onWindowMaximizedChanged((value) => {
    maximized.value = value
  })
})

onUnmounted(() => {
  cleanupMaximized?.()
})

function onDragAreaDblClick() {
  void maximizeWindow()
}
</script>

<template>
  <header class="desktop-titlebar" :class="{ 'desktop-titlebar--mac': isMac }">
    <div
      class="desktop-titlebar__drag"
      @dblclick="onDragAreaDblClick"
    >
      <div v-if="!isMac" class="desktop-titlebar__brand">
        <img src="/logo.png" alt="">
        <span>{{ displayTitle }}</span>
      </div>
    </div>

    <div class="desktop-titlebar__controls">
      <template v-if="isMac">
        <button
          type="button"
          class="desktop-titlebar__btn desktop-titlebar__btn--close"
          :aria-label="t('window_close')"
          @click="closeWindow()"
        >
          <span aria-hidden="true">×</span>
        </button>
        <button
          type="button"
          class="desktop-titlebar__btn desktop-titlebar__btn--minimize"
          :aria-label="t('window_minimize')"
          @click="minimizeWindow()"
        >
          <span aria-hidden="true">−</span>
        </button>
        <button
          type="button"
          class="desktop-titlebar__btn desktop-titlebar__btn--maximize"
          :aria-label="maximized ? t('window_restore') : t('window_maximize')"
          @click="maximizeWindow()"
        >
          <span aria-hidden="true">{{ maximized ? '⧉' : '□' }}</span>
        </button>
      </template>
      <template v-else>
        <button
          type="button"
          class="desktop-titlebar__btn desktop-titlebar__btn--minimize"
          :aria-label="t('window_minimize')"
          @click="minimizeWindow()"
        >
          <span aria-hidden="true">−</span>
        </button>
        <button
          type="button"
          class="desktop-titlebar__btn desktop-titlebar__btn--maximize"
          :aria-label="maximized ? t('window_restore') : t('window_maximize')"
          @click="maximizeWindow()"
        >
          <span aria-hidden="true">{{ maximized ? '⧉' : '□' }}</span>
        </button>
        <button
          type="button"
          class="desktop-titlebar__btn desktop-titlebar__btn--close"
          :aria-label="t('window_close')"
          @click="closeWindow()"
        >
          <span aria-hidden="true">×</span>
        </button>
      </template>
    </div>
  </header>
</template>
