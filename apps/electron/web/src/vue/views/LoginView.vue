<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { syncGuestAuthConfig } from '../../api/guestConfig'
import { failureText } from '../../api/helpers'
import { onOAuthCallback, takeOAuthCallback } from '../../api/system'
import { xboardRequest } from '../../api/xboard'
import { publicErrorText } from '../../format'
import { saveSession } from '../../store/persist'
import { isDesktopShell } from '../../platform/shell'
import { appState, store, t } from '../state'
import { openXboardOAuth, parseXboardCallback, loginXboardWithVerify, confirmXboardOAuth } from '../../api/auth/xboard'
import type { OAuthProvider } from '../../store'
import AppearanceControls from '../components/AppearanceControls.vue'

type AuthMode = 'login' | 'register'

interface AuthBody {
  data?: { auth_data?: string; token?: string }
  message?: string
}

const router = useRouter()
const isDesktop = isDesktopShell()
const mode = ref<AuthMode>('login')
const email = ref('')
const password = ref('')
const inviteCode = ref('')
const emailCode = ref('')
const captcha = ref('')
const message = ref('')
const error = ref('')
const loading = ref(false)
const configLoading = ref(false)
const forgotLoading = ref(false)
const resetMode = ref(false)
const verifySending = ref(false)
const tokenLoading = ref(false)
const oauthConfirm = ref<{ token: string; provider: string; email: string } | null>(null)

const baseUrl = computed(() => {
  if (!appState.buildConfig?.default_api_url) throw new Error('XBCLIENT_DEFAULT_API_URL is required in build config')
  return appState.buildConfig.default_api_url
})
const appName = computed(() => {
  if (!appState.buildConfig?.app_name) throw new Error('XBCLIENT_APP_NAME is required in build config')
  return appState.buildConfig.app_name
})
const oauthCallbackSupported = computed(() => appState.capabilities?.oauth_callback === true)

let unlistenOAuth: (() => void) | null = null

onMounted(() => {
  void refreshGuestConfig()
  if (oauthCallbackSupported.value) {
    void checkOAuthCallback()
    unlistenOAuth = onOAuthCallback(() => { void checkOAuthCallback() })
    window.addEventListener('focus', onWindowFocus)
  } else {
    window.addEventListener('focus', onWindowFocus)
  }
})

onUnmounted(() => {
  unlistenOAuth?.()
  window.removeEventListener('focus', onWindowFocus)
})

function onWindowFocus() {
  void refreshGuestConfig()
  if (oauthCallbackSupported.value) void checkOAuthCallback()
}

async function refreshGuestConfig() {
  if (!baseUrl.value) return
  error.value = ''
  configLoading.value = true
  try {
    await syncGuestAuthConfig(baseUrl.value)
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    configLoading.value = false
  }
}

function switchMode(next: AuthMode) {
  mode.value = next
  resetMode.value = false
  message.value = ''
  error.value = ''
}

function putCaptchaParam(params: Record<string, string>) {
  const token = captcha.value.trim()
  if (!token) return
  const type = appState.registerCaptchaType.trim()
  if (type === 'turnstile') {
    params.turnstile_token = token
    return
  }
  if (type === 'recaptcha-v3') {
    params.recaptcha_v3_token = token
    return
  }
  if (type === 'recaptcha') {
    params.recaptcha_data = token
    return
  }
  throw new Error(`unsupported captcha_type: ${type}`)
}

async function submit() {
  loading.value = true
  message.value = ''
  error.value = ''
  try {
    const params: Record<string, string> = { email: email.value.trim(), password: password.value }
    if (mode.value === 'register') {
      if (inviteCode.value.trim()) params.invite_code = inviteCode.value.trim()
      if (emailCode.value.trim()) params.email_code = emailCode.value.trim()
      putCaptchaParam(params)
    }
    const response = await xboardRequest<AuthBody>(mode.value, { baseUrl: baseUrl.value, params })
    if (!response.ok) {
      error.value = failureText(response)
      return
    }
    const authData = response.body?.data?.auth_data
    if (!authData) {
      if (mode.value === 'register') {
        switchMode('login')
        message.value = response.body?.message ?? t('register_done_login')
        return
      }
      error.value = t('login_auth_missing')
      return
    }
    await finishLogin(authData, email.value.trim())
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    loading.value = false
  }
}

async function forgotPassword() {
  const accountEmail = email.value.trim()
  if (!accountEmail) {
    error.value = t('email_required')
    return
  }
  if (!resetMode.value) {
    resetMode.value = true
    error.value = ''
    message.value = ''
    return
  }
  forgotLoading.value = true
  error.value = ''
  message.value = ''
  try {
    const response = await xboardRequest<{ message?: string }>('forget_password', {
      baseUrl: baseUrl.value,
      params: {
        email: accountEmail,
        password: password.value,
        email_code: emailCode.value.trim(),
      },
    })
    if (!response.ok) {
      error.value = failureText(response)
      return
    }
    resetMode.value = false
    password.value = ''
    emailCode.value = ''
    message.value = t('forgot_password_sent')
  } catch (err) {
    error.value = publicErrorText(err, t('forgot_password_failed'))
  } finally {
    forgotLoading.value = false
  }
}

async function sendEmailVerify() {
  verifySending.value = true
  error.value = ''
  message.value = ''
  try {
    const params: Record<string, string> = { email: email.value.trim() }
    putCaptchaParam(params)
    const response = await xboardRequest<{ message?: string }>('send_email_verify', { baseUrl: baseUrl.value, params })
    if (!response.ok) {
      error.value = failureText(response)
      return
    }
    message.value = response.body?.message ?? t('email_verify_sent')
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    verifySending.value = false
  }
}

async function startOAuth(provider: OAuthProvider) {
  error.value = ''
  message.value = ''
  try {
    await openXboardOAuth(provider, mode.value, inviteCode.value)
    message.value = t('oauth_opened_waiting_callback')
  } catch (err) {
    error.value = publicErrorText(err, t('oauth_open_failed'))
  }
}

async function checkOAuthCallback() {
  if (!oauthCallbackSupported.value) return
  const url = await takeOAuthCallback()
  if (!url) return
  try {
    const result = parseXboardCallback(url)
    if (result.type === 'message') message.value = result.message
    else if (result.type === 'confirm') {
      mode.value = 'register'
      oauthConfirm.value = result.confirm
    } else {
      const session = await loginXboardWithVerify(result.verify)
      await finishLogin(session.authData, session.email)
    }
  } catch (err) {
    error.value = publicErrorText(err)
  }
}

async function confirmOAuthRegister() {
  if (!oauthConfirm.value) return
  tokenLoading.value = true
  error.value = ''
  try {
    const session = await confirmXboardOAuth(oauthConfirm.value.token)
    await finishLogin(session.authData, session.email)
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    tokenLoading.value = false
  }
}

async function finishLogin(authData: string, accountEmail: string) {
  store().setSession({ baseUrl: baseUrl.value, authData, email: accountEmail })
  await saveSession({ authData, email: accountEmail })
  await router.replace('/home')
}
</script>

<template>
  <main class="auth-shell" :class="{ 'auth-shell--desktop': isDesktop }">
    <form class="auth-layout" @submit.prevent="submit">
      <header class="auth-top">
        <AppearanceControls />
      </header>

      <section class="auth-hero panel-card">
        <div class="auth-logo-wrap">
          <img class="auth-logo" src="/logo.png" :alt="appName">
        </div>
        <div class="auth-hero-copy">
          <p class="auth-eyebrow">{{ t('login') }} · {{ appName }}</p>
          <h1>{{ appName }}</h1>
          <p class="auth-tagline muted">{{ t('page_settings_subtitle') }}</p>
        </div>
      </section>

      <v-card class="auth-form-card panel-card">
        <v-card-text>
          <v-btn-toggle v-model="mode" class="mb-4" mandatory rounded="pill" divided>
            <v-btn value="login" @click="switchMode('login')">{{ t('login') }}</v-btn>
            <v-btn value="register" @click="switchMode('register')">{{ t('register') }}</v-btn>
          </v-btn-toggle>

          <v-text-field
            v-model="email"
            :label="t('email')"
            type="email"
            autocomplete="username"
            variant="outlined"
            density="comfortable"
          />
          <v-text-field
            v-model="password"
            :label="t('password')"
            type="password"
            autocomplete="current-password"
            variant="outlined"
            density="comfortable"
            class="mt-2"
          >
            <template v-if="mode === 'login' && !resetMode" #append-inner>
              <button class="text-button" type="button" @click="forgotPassword">
                {{ forgotLoading ? t('refreshing') : t('forgot_password') }}
              </button>
            </template>
          </v-text-field>

          <template v-if="mode === 'register'">
            <v-text-field
              v-model="inviteCode"
              :label="`${t('invite_code')}${appState.inviteForce ? ' *' : ''}`"
              variant="outlined"
              density="comfortable"
              class="mt-2"
            />
          </template>
          <v-text-field
            v-if="appState.registerCaptchaEnabled && (mode === 'register' || resetMode)"
            v-model="captcha"
            :label="t('captcha_token')"
            variant="outlined"
            density="comfortable"
            class="mt-2"
          />
          <div
            v-if="(mode === 'register' && appState.registerEmailVerifyEnabled) || (mode === 'login' && resetMode)"
            class="verify-row mt-2"
          >
            <v-text-field
              v-model="emailCode"
              :label="t('email_code')"
              variant="outlined"
              density="comfortable"
            />
            <v-btn
              class="verify-button"
              variant="outlined"
              :loading="verifySending"
              type="button"
              @click="sendEmailVerify"
            >
              {{ t('send_email_verify') }}
            </v-btn>
          </div>
          <v-btn
            v-if="mode === 'login' && resetMode"
            class="mt-3"
            block
            variant="tonal"
            type="button"
            :loading="forgotLoading"
            @click="forgotPassword"
          >
            {{ t('forgot_password') }}
          </v-btn>

          <v-btn class="mt-4" block color="primary" size="large" type="submit" :loading="loading">
            {{ mode === 'login' ? t('login') : t('register') }}
          </v-btn>
        </v-card-text>
      </v-card>

      <v-card
        v-if="oauthCallbackSupported && (appState.oauthProviders.length || oauthConfirm || configLoading)"
        class="auth-oauth-card panel-card"
      >
        <v-card-text>
          <div class="auth-oauth-head">
            <p class="text-body-1 font-weight-bold mb-0">{{ t('auth_options') }}</p>
            <v-progress-circular v-if="configLoading" indeterminate size="18" width="2" color="primary" />
          </div>
          <div v-if="appState.oauthProviders.length" class="stack mt-3">
            <v-btn
              v-for="provider in appState.oauthProviders"
              :key="provider.driver"
              variant="outlined"
              block
              @click="startOAuth(provider)"
            >
              {{ mode === 'login' ? t('oauth_login') : t('oauth_register') }} · {{ provider.label }}
            </v-btn>
          </div>
          <v-alert v-if="oauthConfirm" color="primary" variant="tonal" density="compact" class="mt-3">
            {{ t('oauth_confirm_register') }} · {{ oauthConfirm.provider }}：{{ oauthConfirm.email }}
            <v-btn class="ml-2" size="small" :loading="tokenLoading" @click="confirmOAuthRegister">
              {{ t('confirm') }}
            </v-btn>
          </v-alert>
        </v-card-text>
      </v-card>

      <v-alert v-if="message" color="primary" variant="tonal">{{ message }}</v-alert>
      <v-alert v-if="error" color="error" variant="tonal">{{ error }}</v-alert>
    </form>
  </main>
</template>
