package moe.telecom.xbclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal class PxBoardAuthAdapter(
    private val name: String,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<XbClientUiState>,
    private val persist: suspend (XbClientUiState) -> Unit,
    private val message: (String) -> Unit,
    private val open: (String) -> Unit,
    private val onLogin: () -> Unit,
) {
    fun refreshProviders() {
        scope.launch(Dispatchers.IO) {
            try {
                val setup = PxBoardApi.request("/setup") as JSONObject
                val extensions = setup.getJSONArray("extensions")
                val enabled = (0 until extensions.length()).any { extensions.getString(it) == "oauth" }
                val rows = if (enabled) PxBoardApi.request("/extensions/oauth/providers") as JSONArray else JSONArray()
                val providers = List(rows.length()) { index ->
                    val row = rows.getJSONObject(index)
                    OAuthProvider(row.getString("id"), row.getString("name"))
                }
                state.update { it.copy(oauthProviders = providers) }
            } catch (e: Exception) { message(e.message ?: e.toString()) }
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
                val flow = PxBoardApi.request("/auth/native", body = JSONObject().put("name", name).put("challenge", challenge)) as JSONObject
                coroutineContext.ensureActive()
                val url = android.net.Uri.parse(flow.getString("authorizationUrl")).buildUpon()
                if (providerId != null) url.appendQueryParameter("provider", providerId)
                open(url.build().toString())
                val expiresAt = Instant.parse(flow.getString("expiresAt")).toEpochMilli()
                while (System.currentTimeMillis() < expiresAt) {
                    delay(flow.getLong("intervalSeconds") * 1000L)
                    val result = PxBoardApi.request("/auth/native/${flow.getString("id")}/token", body = JSONObject().put("proof", proof)) as JSONObject
                    coroutineContext.ensureActive()
                    if (result.getString("status") == "pending") continue
                    check(result.getString("status") == "approved") { "Invalid PxBoard authorization response" }
                    val user = result.getJSONObject("user")
                    state.update { it.copy(authData = "PxBoard " + result.getString("token"), userEmail = user.getString("email"), nativeLoginPending = false) }
                    persist(state.value)
                    onLogin()
                    return@launch
                }
                throw IllegalStateException("Authorization expired. Please sign in again.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message(e.message ?: e.toString()) }
            finally { if (attempt == loginAttempt) state.update { it.copy(nativeLoginPending = false) } }
        }
    }
}
