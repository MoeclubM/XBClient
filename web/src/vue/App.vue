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

const NAV_ITEMS = [
  { path: '/home', icon: '⌂', label: () => t('nav_nodes') },
  { path: '/plans', icon: '◫', label: () => t('nav_plans') },
  { path: '/profile', icon: '◉', label: () => t('nav_profile') },
  { path: '/settings', icon: '⚙', label: () => t('nav_settings') },
]

const activeNavIndex = computed(() => {
  const idx = NAV_ITEMS.findIndex((item) => {
    if (item.path === '/settings') return route.path.startsWith('/settings')
    return route.path === item.path
  })
  return idx >= 0 ? idx : 0
})

const navPillStyle = computed(() => {
  const count = NAV_ITEMS.length
  const pct = 100 / count
  return {
    left: `calc(${activeNavIndex.value * pct}% + 2px)`,
    width: `calc(${pct}% - 4px)`,
  }
})

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
      <v-card class="glass-panel pa-5" max-width="560">
        <p class="text-error font-weight-bold mb-0">{{ t('bootstrap_config_missing') }}：{{ bootstrapError }}</p>
      </v-card>
    </main>

    <template v-else>
      <v-main class="liquid-main">
        <RouterView />
      </v-main>

      <nav v-if="showNav" class="liquid-bottom-nav">
        <div class="nav-pill" :style="navPillStyle" />
        <v-btn
          v-for="(item, index) in NAV_ITEMS"
          :key="item.path"
          :value="item.path"
          :class="{ 'v-btn--active': activeNavIndex === index }"
          @click="router.push(item.path)"
        >
          <span class="nav-symbol">{{ item.icon }}</span>
          <span>{{ item.label() }}</span>
        </v-btn>
      </nav>
    </template>
  </v-app>
</template>
