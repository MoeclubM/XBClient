package moe.telecom.xbclient

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

internal class XboardAuthAdapter(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<XbClientUiState>,
    private val defaultApiUrl: () -> String,
    private val persist: suspend (XbClientUiState) -> Unit,
    private val message: (String) -> Unit,
    private val open: (String) -> Unit,
    private val onLogin: () -> Unit,
) {
    private var pendingOAuthCallback: Uri? = null
    private var pendingOAuthState = ""

    fun resumeCallback() {
        pendingOAuthCallback?.let { uri ->
            pendingOAuthCallback = null
            handleOAuthCallback(uri)
        }
    }

    fun refreshOAuthProviders(showErrors: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            try {
                val result = XboardApi.request("guest_config", defaultApiUrl(), "", JSONObject())
                val body = requireSuccessfulBody("访客配置", result)
                val data = body.getJSONObject("data")
                val providers = data.getJSONArray("oauth_providers").toOAuthProviderList()
                uiState.update {
                    it.copy(
                        oauthProviders = providers,
                        inviteForce = data.getInt("is_invite_force") == 1,
                        registerEmailVerifyEnabled = data.getInt("is_email_verify") == 1,
                        registerCaptchaEnabled = data.getInt("is_captcha") == 1,
                        registerCaptchaType = data.getString("captcha_type")
                    )
                }
                persist(uiState.value)
                if (uiState.value.screen == PassScreen.ACCOUNT_SECURITY && uiState.value.authData.isNotEmpty()) {
                    refreshOAuthBindings(showErrors = showErrors)
                }
            } catch (error: Exception) {
                if (showErrors) {
                    message("OAuth 配置加载失败：${error.message}")
                }
            }
        }
    }

    fun openOAuthPage(scene: String, driver: String, inviteCode: String = "") {
        val state = UUID.randomUUID().toString()
        pendingOAuthState = state
        val builder = Uri.parse("${defaultApiUrl().trimEnd('/')}/api/v1/passport/auth/oauth/$driver/redirect")
            .buildUpon()
            .appendQueryParameter("scene", scene)
            .appendQueryParameter("redirect", "dashboard")
            .appendQueryParameter("client", "app")
            .appendQueryParameter("app_scheme", BuildConfig.OAUTH_CALLBACK_SCHEME)
            .appendQueryParameter("state", state)
        if (scene == "register" && inviteCode.trim().isNotEmpty()) {
            builder.appendQueryParameter("invite_code", inviteCode.trim())
        }
        open(builder.build().toString())
    }

    fun closeOAuthWebView() {
        uiState.update { it.copy(oauthWebViewUrl = "") }
    }

    fun handleOAuthCallback(uri: Uri) {
        if (!uiState.value.loaded) {
            pendingOAuthCallback = uri
            return
        }
        closeOAuthWebView()
        val state = uri.getQueryParameter("state")
        if (!state.isNullOrEmpty() && pendingOAuthState.isNotEmpty() && state != pendingOAuthState) {
            message("OAuth callback state 不匹配。")
            return
        }
        pendingOAuthState = ""
        val error = uri.getQueryParameter("oauth_error")
        if (!error.isNullOrEmpty()) {
            message("OAuth 失败：$error")
            return
        }
        val success = uri.getQueryParameter("oauth_success")
        if (!success.isNullOrEmpty()) {
            message(success)
            if (uiState.value.isLoggedIn) {
                refreshOAuthBindings(force = true, showErrors = true)
            }
            return
        }
        val confirmToken = uri.getQueryParameter("oauth_confirm_token")
        if (!confirmToken.isNullOrEmpty()) {
            val provider = uri.getQueryParameter("oauth_provider")
                ?: throw IllegalStateException("OAuth confirm callback missing provider")
            val email = uri.getQueryParameter("oauth_email")
                ?: throw IllegalStateException("OAuth confirm callback missing email")
            uiState.update {
                it.copy(
                    authMode = AuthMode.REGISTER,
                    oauthConfirmToken = confirmToken,
                    oauthConfirmProvider = provider,
                    oauthConfirmEmail = email
                )
            }
            message("请确认 OAuth 注册。")
            return
        }
        val verify = uri.getQueryParameter("verify")
        if (!verify.isNullOrEmpty()) {
            completeOAuthLogin(verify)
        }
    }

    fun confirmOAuthRegister() {
        val token = uiState.value.oauthConfirmToken
        if (token.isEmpty()) {
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val body = requireSuccessfulBody(
                    "OAuth 注册确认",
                    XboardApi.request("confirm_oauth_register", defaultApiUrl(), "", JSONObject().put("token", token))
                )
                completeOAuthLogin(verifyFromQuickLoginUrl(body.getString("data")))
            } catch (error: Exception) {
                message("OAuth 注册失败：${error.message}")
            }
        }
    }

    fun clearOAuthConfirm() {
        uiState.update { it.copy(oauthConfirmToken = "", oauthConfirmProvider = "", oauthConfirmEmail = "") }
    }

    fun refreshOAuthBindings(force: Boolean = false, showLoading: Boolean = false, showErrors: Boolean = false) {
        val authData = uiState.value.authData
        if (authData.isEmpty() || uiState.value.oauthBindingsLoading || uiState.value.oauthProviders.isEmpty()) {
            return
        }
        if (!force && uiState.value.oauthBindings.isNotEmpty()) {
            return
        }
        if (showLoading) {
            uiState.update { it.copy(oauthBindingsLoading = true) }
        }
        scope.launch(Dispatchers.IO) {
            try {
                val body = requireSuccessfulBody("OAuth 绑定状态", XboardApi.request("oauth_bindings", defaultApiUrl(), authData, JSONObject()))
                uiState.update {
                    it.copy(
                        oauthBindings = extractDataArray(body).toOAuthBindingItemList(),
                        oauthBindingsLoading = false
                    )
                }
            } catch (error: Exception) {
                if (showLoading) {
                    uiState.update { it.copy(oauthBindingsLoading = false) }
                }
                if (showErrors) {
                    message("OAuth 绑定状态加载失败：${error.message}")
                }
            }
        }
    }

    fun bindOAuth(driver: String) {
        val authData = uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        val state = UUID.randomUUID().toString()
        pendingOAuthState = state
        scope.launch(Dispatchers.IO) {
            try {
                val body = requireSuccessfulBody(
                    "OAuth 绑定",
                    XboardApi.request(
                        "oauth_bind_prepare",
                        defaultApiUrl(),
                        authData,
                        JSONObject()
                            .put("driver", driver)
                            .put("redirect", "dashboard")
                            .put("client", "app")
                            .put("app_scheme", BuildConfig.OAUTH_CALLBACK_SCHEME)
                    )
                )
                val url = body.getJSONObject("data").getString("authorize_url")
                pendingOAuthState = Uri.parse(url).getQueryParameter("state") ?: state
                open(url)
            } catch (error: Exception) {
                pendingOAuthState = ""
                message("OAuth 绑定失败：${error.message}")
            }
        }
    }

    fun unbindOAuth(driver: String) {
        val authData = uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                requireSuccessfulBody(
                    "OAuth 解绑",
                    XboardApi.request("oauth_unbind", defaultApiUrl(), authData, JSONObject().put("driver", driver))
                )
                message("OAuth 已解绑。")
                refreshOAuthBindings(force = true, showLoading = true, showErrors = true)
            } catch (error: Exception) {
                message("OAuth 解绑失败：${error.message}")
            }
        }
    }

    private fun completeOAuthLogin(verify: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val result = XboardApi.request("token_login", defaultApiUrl(), "", JSONObject().put("verify", verify))
                val body = requireSuccessfulBody("OAuth 登录", result)
                val data = body.getJSONObject("data")
                val next = uiState.value.copy(
                    authMode = AuthMode.LOGIN,
                    screen = PassScreen.CONNECT,
                    authData = data.getString("auth_data"),
                    subscribeToken = data.optString("token"),
                    oauthConfirmToken = "",
                    oauthConfirmProvider = "",
                    oauthConfirmEmail = ""
                )
                uiState.value = next
                persist(next)
                message("OAuth 登录成功。")
                onLogin()
            } catch (error: Exception) {
                message("OAuth 登录失败：${error.message}")
            }
        }
    }

    private fun verifyFromQuickLoginUrl(url: String): String =
        Regex("[?&]verify=([^&]+)")
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.let(Uri::decode)
            ?: throw IllegalStateException("快捷登录地址缺少 verify。")


    private fun requireSuccessfulBody(title: String, result: JSONObject): JSONObject {
        if (!result.getBoolean("ok")) throw IllegalStateException(title + ": " + resultError(result))
        return result.getJSONObject("body").also { it.requireNotXboardFail() }
    }
}
