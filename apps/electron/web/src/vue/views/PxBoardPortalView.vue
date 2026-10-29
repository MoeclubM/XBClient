<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { openPxBoardPortal, pxboardRequest } from '../../api/pxboard'
import { clearSession } from '../../store/persist'
import { appState, store, t } from '../state'
const route = useRoute()
const router = useRouter()
const error = ref('')
const busy = ref(false)
const plans = computed(() => route.path === '/plans')
async function open() {
  busy.value = true; error.value = ''
  try { await openPxBoardPortal(plans.value ? '/user/plans' : '/user') }
  catch (e) { error.value = e instanceof Error ? e.message : String(e) }
  finally { busy.value = false }
}
async function logout() {
  try { await pxboardRequest('/auth/logout', {}); store().reset(); await clearSession(); await router.replace('/login') }
  catch (e) { error.value = e instanceof Error ? e.message : String(e) }
}
</script>
<template>
  <section class="liquid-page"><h1 class="mb-6">{{ plans ? t('nav_plans') : t('nav_profile') }}</h1>
    <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
    <template v-if="!plans"><p>{{ appState.email }}</p><p class="mb-6">{{ appState.subscription.summary }}</p></template>
    <v-btn color="primary" :loading="busy" @click="open">{{ plans ? t('nav_plans') : t('panel_user_center') }}</v-btn>
    <v-btn v-if="!plans" variant="text" class="ms-3" @click="logout">{{ t('logout') }}</v-btn>
  </section>
</template>
