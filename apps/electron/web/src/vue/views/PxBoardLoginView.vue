<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { loginPxBoard } from '../../api/pxboard'
import { appState, t } from '../state'
import AppearanceControls from '../components/AppearanceControls.vue'
const router = useRouter()
const busy = ref(false)
const error = ref('')
let controller: AbortController | null = null
async function login() {
  controller?.abort()
  const attempt = new AbortController()
  controller = attempt
  busy.value = true
  error.value = ''
  try { await loginPxBoard(attempt.signal); await router.replace('/home') }
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
      <v-btn color="primary" block :disabled="busy" @click="login">{{ busy ? t('panel_authorizing') : t('login') }}</v-btn>
      <v-btn v-if="busy" block variant="text" class="mt-2" @click="cancel">{{ t('panel_cancel') }}</v-btn>
    </div>
  </section></main>
</template>
