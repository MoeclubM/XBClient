<script setup lang="ts">
import { computed, ref } from 'vue'
import { appState, persistSettings, t } from '../state'
import type { AppSettings } from '../../store'

const langOpen = ref(false)
const themeOpen = ref(false)

const languageOptions: Array<{ value: AppSettings['appLanguage']; label: string }> = [
  { value: 'system', label: 'System' },
  { value: 'zh-CN', label: '中文' },
  { value: 'en', label: 'English' },
  { value: 'ja', label: '日本語' },
  { value: 'ru', label: 'Русский' },
  { value: 'fa', label: 'فارسی' },
]

const themeOptions: Array<{ value: AppSettings['themeMode']; label: string }> = [
  { value: 'system', label: t('theme_system') },
  { value: 'light', label: t('theme_light') },
  { value: 'dark', label: t('theme_dark') },
]

const currentLanguageLabel = computed(() => {
  const found = languageOptions.find((item) => item.value === appState.settings.appLanguage)
  return found?.label ?? 'System'
})

const currentThemeLabel = computed(() => {
  const found = themeOptions.find((item) => item.value === appState.settings.themeMode)
  return found?.label ?? t('theme_system')
})

const themeGlyph = computed(() => {
  const mode = appState.settings.themeMode
  if (mode === 'dark') return 'moon'
  if (mode === 'light') return 'sun'
  return 'auto'
})

async function pickLanguage(value: AppSettings['appLanguage']) {
  langOpen.value = false
  await persistSettings({ appLanguage: value })
}

async function pickTheme(value: AppSettings['themeMode']) {
  themeOpen.value = false
  await persistSettings({ themeMode: value })
}
</script>

<template>
  <div class="appearance-controls">
    <v-menu v-model="langOpen" location="bottom end" :close-on-content-click="false">
      <template #activator="{ props: menuProps }">
        <button
          v-bind="menuProps"
          type="button"
          class="pref-icon-btn"
          :title="`${t('language')}: ${currentLanguageLabel}`"
          :aria-label="t('language')"
        >
          <svg class="pref-icon" viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="1.6" />
            <path
              d="M3 12h18M12 3c2.6 2.8 4 6 4 9s-1.4 6.2-4 9M12 3c-2.6 2.8-4 6-4 9s1.4 6.2 4 9"
              fill="none"
              stroke="currentColor"
              stroke-width="1.6"
            />
          </svg>
        </button>
      </template>
      <v-list class="pref-menu" density="compact" nav>
        <v-list-subheader>{{ t('language') }}</v-list-subheader>
        <v-list-item
          v-for="item in languageOptions"
          :key="item.value"
          :active="appState.settings.appLanguage === item.value"
          @click="pickLanguage(item.value)"
        >
          <v-list-item-title>{{ item.label }}</v-list-item-title>
        </v-list-item>
      </v-list>
    </v-menu>

    <v-menu v-model="themeOpen" location="bottom end" :close-on-content-click="false">
      <template #activator="{ props: menuProps }">
        <button
          v-bind="menuProps"
          type="button"
          class="pref-icon-btn"
          :title="`${t('theme')}: ${currentThemeLabel}`"
          :aria-label="t('theme')"
        >
          <svg v-if="themeGlyph === 'sun'" class="pref-icon" viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="12" r="4.2" fill="currentColor" />
            <g stroke="currentColor" stroke-width="1.6" stroke-linecap="round">
              <path d="M12 2.5v2.2M12 19.3v2.2M4.7 4.7l1.6 1.6M17.7 17.7l1.6 1.6M2.5 12h2.2M19.3 12h2.2M4.7 19.3l1.6-1.6M17.7 6.3l1.6-1.6" />
            </g>
          </svg>
          <svg v-else-if="themeGlyph === 'moon'" class="pref-icon" viewBox="0 0 24 24" aria-hidden="true">
            <path
              d="M18.5 14.2a7.2 7.2 0 0 1-8.7-8.7 8.8 8.8 0 1 0 8.7 8.7Z"
              fill="none"
              stroke="currentColor"
              stroke-width="1.6"
              stroke-linejoin="round"
            />
          </svg>
          <svg v-else class="pref-icon" viewBox="0 0 24 24" aria-hidden="true">
            <rect x="4" y="5" width="16" height="14" rx="3" fill="none" stroke="currentColor" stroke-width="1.6" />
            <path d="M12 5v14" stroke="currentColor" stroke-width="1.6" />
            <path d="M4 12h16" stroke="currentColor" stroke-width="1.6" opacity="0.45" />
          </svg>
        </button>
      </template>
      <v-list class="pref-menu" density="compact" nav>
        <v-list-subheader>{{ t('theme') }}</v-list-subheader>
        <v-list-item
          v-for="item in themeOptions"
          :key="item.value"
          :active="appState.settings.themeMode === item.value"
          @click="pickTheme(item.value)"
        >
          <v-list-item-title>{{ item.label }}</v-list-item-title>
        </v-list-item>
      </v-list>
    </v-menu>
  </div>
</template>
