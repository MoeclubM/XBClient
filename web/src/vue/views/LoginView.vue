<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { syncGuestAuthConfig } from '../../api/guestConfig'
import { onOAuthCallback, openInAppBrowser, takeOAuthCallback } from '../../api/system'
import { normalizeBaseUrl, xboardRequest } from '../../api/xboard'
import { publicErrorText } from '../../format'
import { saveSession } from '../../store/persist'
import { isDesktopShell } from '../../platform/shell'
import { appState, store, t } from '../state'
import type { OAuthProvider } from '../../store'
import AppearanceControls from '../components/AppearanceControls.vue'

type AuthMode = 'login' | 'register'

interface AuthBody {
  data?: { auth_data?: string; email?: string }
  message?: string
}

interface ConfirmOAuthBody {
  data?: string
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
const verifySending = ref(false)
const tokenLoading = ref(false)
const oauthConfirm = ref<{ token: string; provider: string; email: string } | null>(null)

const baseUrl = computed(() => appState.buildConfig?.default_api_url ?? appState.baseUrl)
const appName = computed(() => appState.buildConfig?.app_name || 'XBClient')
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
  message.value = ''
  error.value = ''
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
      if (captcha.value.trim()) params.recaptcha_data = captcha.value.trim()
    }
    const response = await xboardRequest<AuthBody>(mode.value, { baseUrl: baseUrl.value, params })
    if (!response.ok) {
      error.value = response.body?.message ?? response.error ?? `HTTP ${response.status}`
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
  forgotLoading.value = true
  error.value = ''
  message.value = ''
  try {
    const response = await xboardRequest<{ message?: string }>('forget_password', {
      baseUrl: baseUrl.value,
      params: { email: accountEmail },
    })
    if (!response.ok) {
      error.value = response.body?.message ?? response.error ?? `HTTP ${response.status}`
      return
    }
    message.value = response.body?.message ?? t('forgot_password_sent')
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
    const token = captcha.value.trim()
    const params: Record<string, string> = { email: email.value.trim() }
    if (token) {
      params.recaptcha_data = token
      params.recaptcha_v3_token = token
      params.cf_turnstile_response = token
    }
    const response = await xboardRequest<{ message?: string }>('send_email_verify', { baseUrl: baseUrl.value, params })
    if (!response.ok) {
      error.value = response.body?.message ?? response.error ?? `HTTP ${response.status}`
      return
    }
    message.value = response.body?.message ?? t('email_verify_sent')
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    verifySending.value = false
  }
}

async function openOAuth(provider: OAuthProvider) {
  if (!appState.buildConfig) throw new Error(t('config_not_loaded'))
  const url = new URL(
    `/api/v1/passport/auth/oauth/${encodeURIComponent(provider.driver)}/redirect`,
    `${normalizeBaseUrl(baseUrl.value)}/`,
  )
  url.searchParams.set('scene', mode.value)
  url.searchParams.set('redirect', 'dashboard')
  url.searchParams.set('client', 'app')
  url.searchParams.set('app_scheme', appState.buildConfig.oauth_callback_scheme)
  if (mode.value === 'register' && inviteCode.value.trim()) url.searchParams.set('invite_code', inviteCode.value.trim())
  await openInAppBrowser(url.toString(), `${provider.label || provider.driver} OAuth`)
  message.value = t('oauth_opened_waiting_callback')
}

async function startOAuth(provider: OAuthProvider) {
  error.value = ''
  message.value = ''
  try {
    await openOAuth(provider)
  } catch (err) {
    error.value = publicErrorText(err, t('oauth_open_failed'))
  }
}

async function checkOAuthCallback() {
  if (!oauthCallbackSupported.value) return
  const callbackUrl = await takeOAuthCallback()
  if (!callbackUrl) return
  const uri = new URL(callbackUrl.replace(/^([a-z][a-z0-9+.-]*):([^/])/i, '$1://$2'))
  const oauthError = uri.searchParams.get('oauth_error')
  if (oauthError) {
    error.value = `${t('oauth_open_failed')}: ${oauthError}`
    return
  }
  const oauthSuccess = uri.searchParams.get('oauth_success')
  if (oauthSuccess) {
    message.value = oauthSuccess
    return
  }
  const confirmToken = uri.searchParams.get('oauth_confirm_token') ?? ''
  if (confirmToken) {
    mode.value = 'register'
    oauthConfirm.value = {
      token: confirmToken,
      provider: uri.searchParams.get('oauth_provider') ?? '',
      email: uri.searchParams.get('oauth_email') ?? '',
    }
    return
  }
  const verify = uri.searchParams.get('verify') || uri.searchParams.get('token') || ''
  if (verify) await loginWithVerify(verify)
}

function verifyFromCallback(value: string): string {
  const matched = /[?&](?:verify|token)=([^&]+)/.exec(value.trim())
  return matched ? decodeURIComponent(matched[1]) : value.trim()
}

async function confirmOAuthRegister() {
  if (!oauthConfirm.value) return
  tokenLoading.value = true
  try {
    const response = await xboardRequest<ConfirmOAuthBody>('confirm_oauth_register', {
      baseUrl: baseUrl.value,
      params: { token: oauthConfirm.value.token },
    })
    if (!response.ok) {
      error.value = response.body?.message ?? response.error ?? `HTTP ${response.status}`
      return
    }
    await loginWithVerify(verifyFromCallback(response.body?.data ?? ''))
  } catch (err) {
    error.value = publicErrorText(err)
  } finally {
    tokenLoading.value = false
  }
}

async function loginWithVerify(verify: string) {
  const response = await xboardRequest<AuthBody>('token_login', {
    baseUrl: baseUrl.value,
    params: { verify: verifyFromCallback(verify) },
  })
  if (!response.ok) {
    error.value = response.body?.message ?? response.error ?? `HTTP ${response.status}`
    return
  }
  const authData = response.body?.data?.auth_data
  if (!authData) {
    error.value = t('oauth_login_missing')
    return
  }
  await finishLogin(authData, response.body?.data?.email ?? email.value.trim())
}

async function finishLogin(authData: string, accountEmail: string) {
  store().setSession({ baseUrl: baseUrl.value, authData, email: accountEmail })
  await saveSession({ authData, email: accountEmail })
  await router.replace('/home')
}
</script>

<template>
  <main class="auth-shell" :class="{ 'auth-shell--desktop': isDesktop }">
    <div class="auth-atmosphere" aria-hidden="true" />
    <form class="auth-layout" @submit.prevent="submit">
      <header class="auth-top">
        <AppearanceControls />
      </header>

      <section class="auth-hero glass-panel">
        <div class="auth-logo-wrap">
          <img class="auth-logo" src="/logo.png" :alt="appName">
        </div>
        <div class="auth-hero-copy">
          <p class="auth-eyebrow">{{ t('login') }} · {{ appName }}</p>
          <h1>{{ appName }}</h1>
          <p class="auth-tagline muted">{{ t('page_settings_subtitle') }}</p>
        </div>
      </section>

      <v-card class="auth-form-card glass-panel">
        <v-card-text>
          <v-btn-toggle v-model="mode" class="liquid-toggle mb-4" mandatory rounded="pill" divided>
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
            <template v-if="mode === 'login'" #append-inner>
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
            <v-text-field
              v-if="appState.registerCaptchaEnabled"
              v-model="captcha"
              :label="t('captcha_token')"
              variant="outlined"
              density="comfortable"
              class="mt-2"
            />
            <div v-if="appState.registerEmailVerifyEnabled" class="verify-row mt-2">
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
                @click="sendEmailVerify"
              >
                {{ t('send_email_verify') }}
              </v-btn>
            </div>
          </template>

          <v-btn class="mt-4" block color="primary" size="large" type="submit" :loading="loading">
            {{ mode === 'login' ? t('login') : t('register') }}
          </v-btn>
        </v-card-text>
      </v-card>

      <v-card
        v-if="oauthCallbackSupported && (appState.oauthProviders.length || oauthConfirm || configLoading)"
        class="auth-oauth-card glass-panel"
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
              {{ mode === 'login' ? t('oauth_login') : t('oauth_register') }} · {{ provider.label || provider.driver }}
            </v-btn>
          </div>
          <v-alert v-if="oauthConfirm" color="primary" variant="tonal" density="compact" class="mt-3">
            {{ t('oauth_confirm_register') }} · {{ oauthConfirm.provider || 'OAuth' }}{{ oauthConfirm.email ? `：${oauthConfirm.email}` : '' }}
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
