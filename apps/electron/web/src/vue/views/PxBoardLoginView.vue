<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { loginPxBoard, loadPxBoardProviders } from '../../api/auth/pxboard'
import type { OAuthProvider } from '../../store'
import { appState, t } from '../state'
import AppearanceControls from '../components/AppearanceControls.vue'
const router = useRouter()
const busy = ref(false)
const error = ref('')
const providers = ref<OAuthProvider[]>([])
const loadingProviders = ref(false)
async function loadProviders() {
  loadingProviders.value = true
  try { providers.value = await loadPxBoardProviders(); error.value = '' }
  catch (e) { error.value = e instanceof Error ? e.message : String(e) }
  finally { loadingProviders.value = false }
}
onMounted(loadProviders)
let controller: AbortController | null = null
async function login(providerId?: string) {
  controller?.abort()
  const attempt = new AbortController()
  controller = attempt
  busy.value = true
  error.value = ''
  try { await loginPxBoard(attempt.signal, providerId); await router.replace('/home') }
  catch (e) { if (!attempt.signal.aborted) error.value = e instanceof Error ? e.message : String(e) }
  finally { if (controller === attempt) busy.value = false }
}
function cancel() { controller?.abort(); busy.value = false }
onUnmounted(cancel)
</script>
<template>
  <main class="auth-shell"><section class="auth-layout"><header class="auth-top"><AppearanceControls /></header>
    <div class="auth-form-card pa-6"><h1 class="mb-6">{{ appState.buildConfig?.app_name }}</h1>
      <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
      <v-btn color="primary" block :disabled="busy" @click="login()">{{ busy ? t('panel_authorizing') : t('login') }}</v-btn>
      <v-btn v-for="provider in providers" :key="provider.driver" block variant="outlined" class="mt-3" :disabled="busy" @click="login(provider.driver)">{{ t('oauth_login') }} · {{ provider.label }}</v-btn>
      <v-progress-linear v-if="loadingProviders" indeterminate class="mt-3" />
      <v-btn v-if="error" block variant="text" class="mt-2" :disabled="loadingProviders" @click="loadProviders">{{ t('refresh') }}</v-btn>
      <v-btn v-if="busy"  block variant="text" class="mt-2" @click="cancel">{{ t('panel_cancel') }}</v-btn>
    </div>
  </section></main>
</template>
