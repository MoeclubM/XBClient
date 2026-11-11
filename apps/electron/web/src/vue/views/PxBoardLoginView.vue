<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  confirmPxBoardPasswordReset,
  loadPxBoardAuthMeta,
  loginPxBoard,
  loginPxBoardPassword,
  registerPxBoardAccount,
  requestPxBoardPasswordReset,
  type PxBoardAuthMeta,
} from '../../api/auth/pxboard'
import { PxBoardRpcError } from '../../api/pxboard'
import { publicErrorText } from '../../format'
import { appState, t } from '../state'
import AppearanceControls from '../components/AppearanceControls.vue'

const router = useRouter()
const mode = ref<'login' | 'register' | 'reset'>('login')
const busy = ref(false)
const browserPending = ref(false)
const error = ref('')
const info = ref('')
const loadingMeta = ref(false)
const meta = ref<PxBoardAuthMeta | null>(null)

const email = ref('')
const password = ref('')
const mfaCode = ref('')
const mfaRequired = ref(false)

const selectedProviderKey = ref('')
const registerValues = reactive<Record<string, any>>({})

const resetEmail = ref('')
const resetToken = ref('')
const resetPassword = ref('')
const resetSent = ref(false)

const activeRegProvider = computed(() => {
  const list = meta.value?.registration || []
  return list.find((item) => item.key === selectedProviderKey.value) || list[0] || null
})

const loginNeedsBrowserChallenge = computed(
  () => Boolean(meta.value?.challenge.enabled && meta.value?.challenge.login),
)

const registerNeedsBrowserChallenge = computed(
  () => Boolean(meta.value?.challenge.enabled && meta.value?.challenge.register),
)

function syncRegisterDefaults() {
  const provider = activeRegProvider.value
  if (!provider) return
  for (const field of provider.fields) {
    if (!(field.key in registerValues)) {
      registerValues[field.key] = field.default ?? (field.type === 'boolean' ? false : '')
    }
  }
}

async function loadMeta() {
  loadingMeta.value = true
  try {
    meta.value = await loadPxBoardAuthMeta()
    if (!selectedProviderKey.value && meta.value.registration.length > 0) {
      selectedProviderKey.value = meta.value.registration[0].key
    }
    syncRegisterDefaults()
    error.value = ''
  } catch (e) {
    error.value = publicErrorText(e)
  } finally {
    loadingMeta.value = false
  }
}

onMounted(loadMeta)

let controller: AbortController | null = null

async function submitPasswordLogin() {
  if (!email.value.trim() || !password.value || busy.value) return
  busy.value = true
  error.value = ''
  info.value = ''
  try {
    await loginPxBoardPassword({
      email: email.value,
      password: password.value,
      code: mfaCode.value,
    })
    await router.replace('/home')
  } catch (e) {
    if (e instanceof PxBoardRpcError && e.code === 'mfa_required') {
      mfaRequired.value = true
      error.value = e.message || '请输入两步验证码'
    } else {
      error.value = publicErrorText(e)
    }
  } finally {
    busy.value = false
  }
}

async function submitRegister() {
  const provider = activeRegProvider.value
  if (!provider || busy.value) return
  busy.value = true
  error.value = ''
  info.value = ''
  try {
    const payload: Record<string, unknown> = {}
    for (const field of provider.fields) {
      const raw = registerValues[field.key]
      payload[field.key] = field.type === 'number' ? Number(raw) : typeof raw === 'string' ? raw.trim() : raw
    }
    info.value = await registerPxBoardAccount(provider, payload)
    if (typeof payload.email === 'string') email.value = payload.email
    mode.value = 'login'
  } catch (e) {
    error.value = publicErrorText(e)
  } finally {
    busy.value = false
  }
}

async function submitResetRequest() {
  if (!resetEmail.value.trim() || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await requestPxBoardPasswordReset(resetEmail.value)
    resetSent.value = true
    info.value = '重置码已发送至邮箱，请填写重置码与新密码。'
  } catch (e) {
    error.value = publicErrorText(e)
  } finally {
    busy.value = false
  }
}

async function submitResetConfirm() {
  if (!resetToken.value.trim() || resetPassword.value.length < 10 || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await confirmPxBoardPasswordReset(resetToken.value, resetPassword.value)
    info.value = t('forgot_password_sent')
    email.value = resetEmail.value.trim()
    resetToken.value = ''
    resetPassword.value = ''
    resetSent.value = false
    mode.value = 'login'
  } catch (e) {
    error.value = publicErrorText(e)
  } finally {
    busy.value = false
  }
}

async function startBrowserLogin(providerId?: string) {
  controller?.abort()
  const attempt = new AbortController()
  controller = attempt
  busy.value = true
  browserPending.value = true
  error.value = ''
  try {
    await loginPxBoard(attempt.signal, providerId)
    await router.replace('/home')
  } catch (e) {
    if (!attempt.signal.aborted) error.value = publicErrorText(e)
  } finally {
    if (controller === attempt) {
      busy.value = false
      browserPending.value = false
    }
  }
}

function cancel() {
  controller?.abort()
  busy.value = false
  browserPending.value = false
}

onUnmounted(cancel)
</script>

<template>
  <main class="auth-shell">
    <section class="auth-layout">
      <header class="auth-top"><AppearanceControls /></header>
      <div class="auth-form-card pa-6">
        <h1 class="mb-4">{{ meta?.name || appState.buildConfig?.app_name }}</h1>

        <v-tabs
          v-if="(meta?.registration.length || 0) > 0 || meta?.passwordReset"
          v-model="mode"
          density="compact"
          class="mb-4"
        >
          <v-tab value="login">{{ t('login') }}</v-tab>
          <v-tab v-if="(meta?.registration.length || 0) > 0" value="register">{{ t('register') }}</v-tab>
          <v-tab v-if="meta?.passwordReset" value="reset">{{ t('forgot_password') }}</v-tab>
        </v-tabs>

        <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
        <v-alert v-if="info" type="info" variant="tonal" class="mb-4">{{ info }}</v-alert>
        <v-progress-linear v-if="loadingMeta" indeterminate class="mb-4" />

        <template v-if="mode === 'login'">
          <form v-if="!loginNeedsBrowserChallenge" @submit.prevent="submitPasswordLogin">
            <v-text-field
              v-model="email"
              :label="t('email')"
              type="email"
              autocomplete="username"
              density="comfortable"
              :disabled="busy"
            />
            <v-text-field
              v-model="password"
              :label="t('password')"
              type="password"
              autocomplete="current-password"
              density="comfortable"
              :disabled="busy"
            />
            <v-text-field
              v-if="mfaRequired"
              v-model="mfaCode"
              label="两步验证码"
              maxlength="6"
              density="comfortable"
              :disabled="busy"
            />
            <v-btn
              type="submit"
              color="primary"
              block
              :loading="busy && !browserPending"
              :disabled="busy || !email.trim() || !password"
            >
              {{ t('login') }}
            </v-btn>
          </form>

          <v-btn
            :variant="loginNeedsBrowserChallenge ? 'flat' : 'tonal'"
            :color="loginNeedsBrowserChallenge ? 'primary' : undefined"
            block
            class="mt-3"
            :disabled="busy"
            @click="startBrowserLogin()"
          >
            {{ browserPending ? t('panel_authorizing') : loginNeedsBrowserChallenge ? t('login') : '浏览器授权登录' }}
          </v-btn>

          <v-btn
            v-for="provider in meta?.providers || []"
            :key="provider.driver"
            block
            variant="outlined"
            class="mt-2"
            :disabled="busy"
            @click="startBrowserLogin(provider.driver)"
          >
            {{ t('oauth_login') }} · {{ provider.label }}
          </v-btn>
        </template>

        <template v-else-if="mode === 'register' && activeRegProvider">
          <v-select
            v-if="(meta?.registration.length || 0) > 1"
            v-model="selectedProviderKey"
            :items="meta!.registration.map((p) => ({ value: p.key, title: p.title }))"
            density="comfortable"
            @update:model-value="syncRegisterDefaults"
          />
          <template v-if="!registerNeedsBrowserChallenge">
            <template v-for="field in activeRegProvider.fields" :key="field.key">
              <v-checkbox
                v-if="field.type === 'boolean'"
                v-model="registerValues[field.key]"
                :label="field.label"
                density="compact"
              />
              <v-text-field
                v-else
                v-model="registerValues[field.key]"
                :label="field.label"
                :hint="field.hint"
                :type="field.type === 'password' || field.key.toLowerCase().includes('password') ? 'password' : field.type === 'number' ? 'number' : 'text'"
                density="comfortable"
              />
            </template>
            <v-btn color="primary" block :loading="busy" @click="submitRegister">
              {{ t('register') }}
            </v-btn>
          </template>
          <v-btn v-else color="primary" block :disabled="busy" @click="startBrowserLogin()">
            在浏览器中完成注册与登录
          </v-btn>
        </template>

        <template v-else-if="mode === 'reset'">
          <v-text-field v-model="resetEmail" :label="t('email')" type="email" density="comfortable" />
          <v-btn
            variant="tonal"
            color="primary"
            block
            :loading="busy && !resetSent"
            :disabled="!resetEmail.trim()"
            @click="submitResetRequest"
          >
            发送重置邮件
          </v-btn>
          <template v-if="resetSent">
            <v-text-field v-model="resetToken" label="邮件中的重置码" density="comfortable" class="mt-3" />
            <v-text-field v-model="resetPassword" label="新密码（至少 10 位）" type="password" density="comfortable" />
            <v-btn
              color="primary"
              block
              :loading="busy"
              :disabled="!resetToken.trim() || resetPassword.length < 10"
              @click="submitResetConfirm"
            >
              确认重置密码
            </v-btn>
          </template>
        </template>

        <v-btn v-if="browserPending" block variant="text" class="mt-2" @click="cancel">
          {{ t('panel_cancel') }}
        </v-btn>
        <v-btn v-if="error && !meta" block variant="text" class="mt-2" :disabled="loadingMeta" @click="loadMeta">
          {{ t('refresh') }}
        </v-btn>
      </div>
    </section>
  </main>
</template>
