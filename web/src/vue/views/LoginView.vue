<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { openInAppBrowser, takeOAuthCallback } from '../../api/system'
import { normalizeBaseUrl, xboardRequest } from '../../api/xboard'
import { publicErrorText } from '../../format'
import { enabled } from '../../reward'
import { saveSession } from '../../store/persist'
import { parseOAuthProviders } from '../../api/helpers'
import { appState, persistSettings, store, t } from '../state'
import type { OAuthProvider } from '../../store'

type AuthMode = 'login' | 'register'

interface AuthBody {
  data?: { auth_data?: string; email?: string }
  message?: string
}

interface GuestConfigBody {
  data?: {
    oauth_providers?: unknown
    is_invite_force?: number | boolean | string
    is_email_verify?: number | boolean | string
    is_captcha?: number | boolean | string
  }
  message?: string
}

interface ConfirmOAuthBody {
  data?: string
  message?: string
}

const router = useRouter()
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
const appName = computed(() => appState.buildConfig?.app_name || 'App')
const oauthCallbackSupported = computed(() => appState.capabilities?.platform === 'android')

onMounted(() => {
  if (baseUrl.value) void loadGuestConfig()
  if (oauthCallbackSupported.value) void checkOAuthCallback()
  window.addEventListener('focus', checkOAuthCallback)
})

onUnmounted(() => window.removeEventListener('focus', checkOAuthCallback))

async function loadGuestConfig(showSuccess = false) {
  error.value = ''
  configLoading.value = true
  try {
    const response = await xboardRequest<GuestConfigBody>('guest_config', { baseUrl: baseUrl.value })
    if (!response.ok) {
      error.value = response.body?.message ?? response.error ?? `HTTP ${response.status}`
      return
    }
    const data = response.body?.data ?? {}
    store().setAuthConfig({
      oauthProviders: parseOAuthProviders(data.oauth_providers),
      inviteForce: enabled(data.is_invite_force),
      registerEmailVerifyEnabled: enabled(data.is_email_verify),
      registerCaptchaEnabled: enabled(data.is_captcha),
    })
    if (showSuccess) message.value = '服务配置已同步。'
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
        message.value = response.body?.message ?? '注册完成，请登录。'
        return
      }
      error.value = '登录响应缺少 auth_data'
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
    error.value = publicErrorText(err, '找回密码失败')
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
  if (!appState.buildConfig) throw new Error('构建配置尚未加载。')
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
  message.value = '已打开 OAuth 页面，等待应用链接自动回调。'
}

async function startOAuth(provider: OAuthProvider) {
  error.value = ''
  message.value = ''
  try {
    await openOAuth(provider)
  } catch (err) {
    error.value = publicErrorText(err, 'OAuth 打开失败')
  }
}

async function checkOAuthCallback() {
  if (!oauthCallbackSupported.value) return
  const callbackUrl = await takeOAuthCallback()
  if (!callbackUrl) return
  const uri = new URL(callbackUrl)
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
    error.value = 'OAuth 登录响应缺少 auth_data'
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
  <main class="auth-shell">
    <form class="auth-card glass-card" @submit.prevent="submit">
      <div class="auth-toolbar">
        <v-select
          :model-value="appState.settings.appLanguage"
          :items="['system', 'zh-CN', 'en', 'ja', 'ru', 'fa']"
          density="compact"
          @update:model-value="persistSettings({ appLanguage: $event })"
        />
        <v-select
          :model-value="appState.settings.themeMode"
          :items="['system', 'light', 'dark']"
          density="compact"
          @update:model-value="persistSettings({ themeMode: $event })"
        />
      </div>

      <div class="auth-brand">
        <img src="/logo.png" :alt="appName">
        <h1>{{ appName }}</h1>
      </div>

      <v-card class="glass-panel pa-4">
        <v-btn-toggle v-model="mode" class="liquid-toggle mb-4" mandatory rounded="pill" divided>
          <v-btn value="login" @click="switchMode('login')">{{ t('login') }}</v-btn>
          <v-btn value="register" @click="switchMode('register')">{{ t('register') }}</v-btn>
        </v-btn-toggle>

        <v-text-field v-model="email" :label="t('email')" type="email" autocomplete="username" />
        <v-text-field v-model="password" :label="t('password')" type="password" autocomplete="current-password">
          <template v-if="mode === 'login'" #append-inner>
            <button class="text-button" type="button" @click="forgotPassword">
              {{ forgotLoading ? t('refreshing') : t('forgot_password') }}
            </button>
          </template>
        </v-text-field>

        <template v-if="mode === 'register'">
          <v-text-field v-model="inviteCode" :label="`${t('invite_code')}${appState.inviteForce ? ' *' : ''}`" />
          <v-text-field v-if="appState.registerCaptchaEnabled" v-model="captcha" :label="t('captcha_token')" />
          <div v-if="appState.registerEmailVerifyEnabled" class="verify-row">
            <v-text-field v-model="emailCode" :label="t('email_code')" />
            <v-btn class="verify-button" color="secondary" :loading="verifySending" @click="sendEmailVerify">
              {{ t('send_email_verify') }}
            </v-btn>
          </div>
        </template>

        <v-btn class="mt-2" block color="primary" size="large" type="submit" :loading="loading">
          {{ mode === 'login' ? t('login') : t('register') }}
        </v-btn>
      </v-card>

      <v-card v-if="oauthCallbackSupported || oauthConfirm" class="glass-panel pa-4">
        <div class="section-row">
          <h2>{{ t('auth_options') }}</h2>
          <v-btn variant="text" size="small" :loading="configLoading" @click="loadGuestConfig(true)">同步</v-btn>
        </div>
        <div v-if="oauthCallbackSupported && appState.oauthProviders.length" class="stack">
          <v-btn
            v-for="provider in appState.oauthProviders"
            :key="provider.driver"
            variant="outlined"
            @click="startOAuth(provider)"
          >
            {{ mode === 'login' ? t('oauth_login') : t('oauth_register') }} · {{ provider.label || provider.driver }}
          </v-btn>
        </div>
        <v-alert v-if="oauthConfirm" color="primary" variant="tonal" density="compact">
          确认使用 {{ oauthConfirm.provider || 'OAuth' }} 注册{{ oauthConfirm.email ? `：${oauthConfirm.email}` : '' }}
          <v-btn class="ml-2" size="small" :loading="tokenLoading" @click="confirmOAuthRegister">确认</v-btn>
        </v-alert>
      </v-card>

      <v-alert v-if="message" color="primary" variant="tonal">{{ message }}</v-alert>
      <v-alert v-if="error" color="error" variant="tonal">{{ error }}</v-alert>
    </form>
  </main>
</template>
