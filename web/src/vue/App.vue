<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, RouterView, useRouter } from 'vue-router'
import { useTheme } from 'vuetify'
import { appState, applyDocumentTheme, bootstrapApp, preventDesktopZoom, showStartupAd, t } from './state'

const route = useRoute()
const router = useRouter()
const theme = useTheme()
const ready = ref(false)
const bootstrapError = ref('')
let cleanupZoom: (() => void) | null = null

const showNav = computed(() => Boolean(appState.authData) && !route.meta.hideNav)
const themeName = computed(() => {
  if (appState.settings.themeMode === 'light' || appState.settings.themeMode === 'dark') return appState.settings.themeMode
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
})

watch(themeName, (value) => {
  theme.change(value)
  applyDocumentTheme()
}, { immediate: true })

onMounted(async () => {
  cleanupZoom = preventDesktopZoom()
  try {
    await bootstrapApp()
    await showStartupAd().catch((error) => console.error('show app open ad failed', error))
    if (!appState.authData && route.path !== '/login') await router.replace('/login')
    if (appState.authData && route.path === '/login') await router.replace('/home')
  } catch (error) {
    bootstrapError.value = error instanceof Error ? error.message : String(error)
  } finally {
    ready.value = true
  }
})

onUnmounted(() => cleanupZoom?.())
</script>

<template>
  <v-app class="liquid-app">
    <main v-if="!ready" class="startup-view">
      <div class="startup-card">
        <img src="/logo.png" alt="Logo">
        <p>{{ t('startup_loading') }}</p>
      </div>
    </main>

    <main v-else-if="bootstrapError" class="startup-view">
      <v-card class="glass-card pa-5" max-width="560">
        <p class="text-error font-weight-bold mb-0">{{ t('bootstrap_config_missing') }}：{{ bootstrapError }}</p>
      </v-card>
    </main>

    <template v-else>
      <v-main class="liquid-main">
        <RouterView />
      </v-main>

      <v-bottom-navigation
        v-if="showNav"
        class="liquid-bottom-nav"
        :model-value="route.path"
        grow
        mandatory
      >
        <v-btn value="/home" @click="router.push('/home')">
          <span class="nav-symbol">⌂</span>
          <span>{{ t('nav_nodes') }}</span>
        </v-btn>
        <v-btn value="/plans" @click="router.push('/plans')">
          <span class="nav-symbol">◫</span>
          <span>{{ t('nav_plans') }}</span>
        </v-btn>
        <v-btn value="/profile" @click="router.push('/profile')">
          <span class="nav-symbol">◉</span>
          <span>{{ t('nav_profile') }}</span>
        </v-btn>
        <v-btn value="/settings" @click="router.push('/settings')">
          <span class="nav-symbol">⚙</span>
          <span>{{ t('nav_settings') }}</span>
        </v-btn>
      </v-bottom-navigation>
    </template>
  </v-app>
</template>
