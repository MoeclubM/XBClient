package moe.telecom.xbclient

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal data class PxBoardAuthField(
    val key: String,
    val label: String,
    val type: String,
    val required: Boolean,
    val defaultValue: String,
    val hint: String,
)

internal data class PxBoardRegProvider(
    val key: String,
    val title: String,
    val procedure: String,
    val fields: List<PxBoardAuthField>,
)

internal data class PxBoardAuthUiState(
    val siteName: String = "",
    val registrationProviders: List<PxBoardRegProvider> = emptyList(),
    val passwordResetEnabled: Boolean = false,
    val loginChallengeRequired: Boolean = false,
    val registerChallengeRequired: Boolean = false,
    val resetChallengeRequired: Boolean = false,
    val mfaRequired: Boolean = false,
    val resetSent: Boolean = false,
    val busy: Boolean = false,
)

internal fun extractQueryParamOrRaw(raw: String, vararg keys: String): String {
    val trimmed = raw.trim()
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
        if (uri != null) {
            for (k in keys) {
                val v = uri.getQueryParameter(k)?.trim()
                if (!v.isNullOrEmpty()) return v
            }
        }
    }
    return trimmed
}

internal class PxBoardAuthAdapter(
    private val name: String,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<XbClientUiState>,
    private val persist: suspend (XbClientUiState) -> Unit,
    private val message: (String) -> Unit,
    private val open: (String) -> Unit,
    private val onLogin: () -> Unit,
) {
    private val _authState = MutableStateFlow(PxBoardAuthUiState())
    val authState = _authState.asStateFlow()

    fun refreshProviders() {
        scope.launch(Dispatchers.IO) {
            try {
                val batch = PxBoardApi.batch(
                    listOf(
                        PxBoardBatchCall("setup", "core", "setup.get"),
                        PxBoardBatchCall("challenge", "core", "auth.challenge"),
                    ),
                )
                val setupRes = batch["setup"]
                if (setupRes?.status != 200 || setupRes.data !is JSONObject) {
                    throw IllegalStateException(setupRes?.errorMessage?.ifEmpty { "读取面板信息失败" } ?: "读取面板信息失败")
                }
                val setup = setupRes.data
                val extArr = setup.optJSONArray("extensions") ?: JSONArray()
                val oauthEnabled = (0 until extArr.length()).any { extArr.optString(it) == "oauth" }
                val rows = if (oauthEnabled) {
                    (PxBoardApi.rpc("oauth", "providers.list") as? JSONArray) ?: JSONArray()
                } else {
                    JSONArray()
                }
                val oauthProviders = List(rows.length()) { index ->
                    val row = rows.getJSONObject(index)
                    OAuthProvider(row.getString("id"), row.getString("name"))
                }
                val regArr = setup.optJSONArray("registration") ?: JSONArray()
                val regProviders = List(regArr.length()) { index ->
                    val item = regArr.getJSONObject(index)
                    val fieldArr = item.optJSONArray("fields") ?: JSONArray()
                    val fields = List(fieldArr.length()) { fIdx ->
                        val f = fieldArr.getJSONObject(fIdx)
                        PxBoardAuthField(
                            key = f.optString("key"),
                            label = f.optString("label"),
                            type = f.optString("type", "text"),
                            required = f.optBoolean("required", false),
                            defaultValue = f.opt("default")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty(),
                            hint = f.optString("hint"),
                        )
                    }
                    PxBoardRegProvider(
                        key = item.optString("key"),
                        title = item.optString("title"),
                        procedure = item.optString("procedure").ifEmpty { item.optString("path") },
                        fields = fields,
                    )
                }
                val challenge = batch["challenge"]?.data as? JSONObject
                val challengeEnabled = challenge?.optBoolean("enabled", false) == true
                state.update { it.copy(oauthProviders = oauthProviders) }
                _authState.update {
                    it.copy(
                        siteName = setup.optString("name"),
                        registrationProviders = regProviders,
                        passwordResetEnabled = setup.optBoolean("passwordReset", false),
                        loginChallengeRequired = challengeEnabled && challenge?.optBoolean("login", false) == true,
                        registerChallengeRequired = challengeEnabled && challenge?.optBoolean("register", false) == true,
                        resetChallengeRequired = challengeEnabled && challenge?.optBoolean("reset", false) == true,
                    )
                }
            } catch (e: Exception) { message(e.message ?: e.toString()) }
        }
    }

    private suspend fun finalizeLogin(token: String, email: String) {
        state.update {
            it.copy(
                authMode = AuthMode.LOGIN,
                screen = PassScreen.CONNECT,
                authData = "PxBoard $token",
                userEmail = email,
                nativeLoginPending = false,
            )
        }
        _authState.update { it.copy(mfaRequired = false, busy = false) }
        persist(state.value)
        onLogin()
    }

    fun loginWithPassword(email: String, password: String, code: String = "") {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isEmpty() || password.isEmpty() || _authState.value.busy) return
        scope.launch(Dispatchers.IO) {
            _authState.update { it.copy(busy = true) }
            try {
                val body = JSONObject()
                    .put("email", trimmedEmail)
                    .put("password", password)
                if (code.trim().isNotEmpty()) body.put("code", code.trim())
                val res = PxBoardApi.rpc("core", "auth.login", body = body) as JSONObject
                finalizeLogin(res.getString("token"), res.optString("email").ifEmpty { trimmedEmail })
            } catch (e: PxBoardRpcException) {
                if (e.code == "mfa_required") {
                    _authState.update { it.copy(mfaRequired = true) }
                }
                message(e.message ?: "登录失败")
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _authState.update { it.copy(busy = false) }
            }
        }
    }

    fun register(provider: PxBoardRegProvider, values: Map<String, Any?>, onSuccess: () -> Unit) {
        if (_authState.value.busy) return
        scope.launch(Dispatchers.IO) {
            _authState.update { it.copy(busy = true) }
            try {
                val slash = provider.procedure.indexOf('/')
                val service = if (slash > 0) provider.procedure.substring(0, slash) else "registration"
                val method = if (slash > 0) provider.procedure.substring(slash + 1).replace('/', '.') else "register"
                val payload = JSONObject()
                for ((k, v) in values) payload.put(k, v)
                val res = PxBoardApi.rpc(service, method, body = payload) as? JSONObject
                message(res?.optString("message")?.ifEmpty { null } ?: "账户已创建，请登录")
                onSuccess()
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _authState.update { it.copy(busy = false) }
            }
        }
    }

    fun requestReset(email: String) {
        val trimmed = email.trim()
        if (trimmed.isEmpty() || _authState.value.busy) return
        scope.launch(Dispatchers.IO) {
            _authState.update { it.copy(busy = true) }
            try {
                PxBoardApi.rpc("core", "auth.resetRequest", body = JSONObject().put("email", trimmed))
                _authState.update { it.copy(resetSent = true) }
                message("重置码已发送至邮箱")
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _authState.update { it.copy(busy = false) }
            }
        }
    }

    fun confirmReset(token: String, password: String, onSuccess: () -> Unit) {
        val resolvedToken = extractQueryParamOrRaw(token, "token")
        if (resolvedToken.isEmpty() || password.length < 10 || _authState.value.busy) return
        scope.launch(Dispatchers.IO) {
            _authState.update { it.copy(busy = true) }
            try {
                PxBoardApi.rpc(
                    "core",
                    "auth.resetPassword",
                    body = JSONObject().put("token", resolvedToken).put("password", password),
                )
                _authState.update { it.copy(resetSent = false) }
                message("密码已重置，请登录")
                onSuccess()
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _authState.update { it.copy(busy = false) }
            }
        }
    }

    private var loginJob: Job? = null
    private var loginAttempt = 0
    fun cancelLogin() { loginAttempt++; loginJob?.cancel(); state.update { it.copy(nativeLoginPending = false) } }
    fun login(providerId: String? = null) {
        cancelLogin()
        val attempt = loginAttempt
        loginJob = scope.launch(Dispatchers.IO) {
            state.update { it.copy(nativeLoginPending = true) }
            try {
                val proof = UUID.randomUUID().toString() + UUID.randomUUID().toString()
                val challenge = MessageDigest.getInstance("SHA-256").digest(proof.toByteArray()).joinToString("") { "%02x".format(it) }
                val flow = PxBoardApi.rpc(
                    "core",
                    "auth.authorizeCreate",
                    body = JSONObject().put("name", name).put("challenge", challenge),
                ) as JSONObject
                coroutineContext.ensureActive()
                val url = Uri.parse(PxBoardApi.publicUrl(flow.getString("authorizationUrl"))).buildUpon()
                if (providerId != null) url.appendQueryParameter("provider", providerId)
                open(url.build().toString())
                val expiresAt = Instant.parse(flow.getString("expiresAt")).toEpochMilli()
                while (System.currentTimeMillis() < expiresAt) {
                    delay(flow.optLong("intervalSeconds", 2L).coerceAtLeast(1L) * 1000L)
                    val result = PxBoardApi.rpc(
                        "core",
                        "auth.authorizeExchange",
                        body = JSONObject().put("id", flow.getString("id")).put("proof", proof),
                    ) as JSONObject
                    coroutineContext.ensureActive()
                    if (result.getString("status") == "pending") continue
                    check(result.getString("status") == "approved") { "Invalid PxBoard authorization response" }
                    val user = result.getJSONObject("user")
                    finalizeLogin(result.getString("token"), user.getString("email"))
                    return@launch
                }
                throw IllegalStateException("Authorization expired. Please sign in again.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message(e.message ?: e.toString()) }
            finally { if (attempt == loginAttempt) state.update { it.copy(nativeLoginPending = false) } }
        }
    }
}
