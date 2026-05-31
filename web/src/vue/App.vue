<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, RouterView, useRouter } from 'vue-router'
import { useTheme } from 'vuetify'
import { installElectronTraySync } from '../platform/electron-tray-sync'
import { isDesktopShell } from '../platform/shell'
import { appState, applyDocumentTheme, bootstrapApp, preventDesktopZoom, t } from './state'
import DesktopTitleBar from './components/DesktopTitleBar.vue'

const route = useRoute()
const router = useRouter()
const theme = useTheme()
const ready = ref(false)
const bootstrapError = ref('')
const isDesktop = isDesktopShell()
let cleanupZoom: (() => void) | null = null
let cleanupTraySync: (() => void) | null = null

const showNav = computed(() => Boolean(appState.authData) && !route.meta.hideNav)
const appName = computed(() => {
  if (!appState.buildConfig?.app_name) throw new Error('XBCLIENT_APP_NAME is required in build config')
  return appState.buildConfig.app_name
})

const themeName = computed(() => {
  if (appState.settings.themeMode === 'light' || appState.settings.themeMode === 'dark') return appState.settings.themeMode
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
})

watch(themeName, (value) => {
  theme.change(value)
  applyDocumentTheme()
}, { immediate: true })

const NAV_ITEMS = [
  { path: '/home', icon: '⌂', label: () => t('nav_home') },
  { path: '/nodes', icon: '◎', label: () => t('nav_nodes') },
  { path: '/plans', icon: '◫', label: () => t('nav_plans') },
  { path: '/profile', icon: '◉', label: () => t('nav_profile') },
  { path: '/services', icon: '✦', label: () => t('nav_services') },
  { path: '/settings', icon: '⚙', label: () => t('nav_settings') },
]

const activeNavIndex = computed(() => {
  const idx = NAV_ITEMS.findIndex((item) => {
    if (item.path === '/settings') return route.path.startsWith('/settings')
    if (item.path === '/services') return ['/services', '/tickets', '/promotion', '/traffic'].includes(route.path)
    if (item.path === '/nodes') return route.path === '/nodes'
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

function navActive(itemPath: string): boolean {
  if (itemPath === '/settings') return route.path.startsWith('/settings')
  if (itemPath === '/services') return ['/services', '/tickets', '/promotion', '/traffic'].includes(route.path)
  if (itemPath === '/nodes') return route.path === '/nodes'
  return route.path === itemPath
}

onMounted(async () => {
  cleanupZoom = preventDesktopZoom()
  try {
    await bootstrapApp()
    if (isDesktop && appState.capabilities?.tray) cleanupTraySync = installElectronTraySync()
    if (!appState.authData && route.path !== '/login') await router.replace('/login')
    if (appState.authData && route.path === '/login') await router.replace('/home')
  } catch (error) {
    bootstrapError.value = error instanceof Error ? error.message : String(error)
  } finally {
    ready.value = true
  }
})

onUnmounted(() => {
  cleanupZoom?.()
  cleanupTraySync?.()
})
</script>

<template>
  <v-app class="liquid-app" :class="{ 'desktop-app': isDesktop }">
    <div v-if="!ready && isDesktop && appState.buildConfig" class="desktop-frame">
      <DesktopTitleBar :title="appName" />
      <div class="desktop-shell desktop-shell--boot">
        <aside class="desktop-sidebar">
          <div class="desktop-brand">
            <img src="/logo.png" alt="Logo">
            <strong>{{ appName }}</strong>
          </div>
        </aside>
        <main class="desktop-main desktop-main--boot">
          <v-progress-linear indeterminate color="primary" class="mb-4" />
          <p class="muted mb-0">{{ t('startup_loading') }}</p>
        </main>
      </div>
    </div>

    <main v-else-if="!ready" class="startup-view">
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

    <div v-else-if="isDesktop" class="desktop-frame">
      <DesktopTitleBar :title="appName" />
      <div class="desktop-shell">
        <aside v-if="showNav" class="desktop-sidebar">
          <div class="desktop-brand">
            <img src="/logo.png" alt="Logo">
            <strong>{{ appName }}</strong>
          </div>
          <v-btn
            v-for="item in NAV_ITEMS"
            :key="item.path"
            variant="text"
            class="desktop-nav-btn"
            :class="{ 'desktop-nav-btn--active': navActive(item.path) }"
            @click="router.push(item.path)"
          >
            <span class="nav-symbol">{{ item.icon }}</span>
            <span>{{ item.label() }}</span>
          </v-btn>
        </aside>
        <div class="desktop-main">
          <v-main class="liquid-main liquid-main--desktop">
            <RouterView />
          </v-main>
        </div>
      </div>
    </div>

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
