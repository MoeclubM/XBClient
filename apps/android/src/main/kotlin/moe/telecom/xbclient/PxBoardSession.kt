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

internal class PxBoardSession(
    private val name: String,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<XbClientUiState>,
    private val persist: suspend (XbClientUiState) -> Unit,
    private val message: (String) -> Unit,
    private val open: (String) -> Unit,
) {
    private var loginJob: Job? = null
    private var loginAttempt = 0
    fun cancelLogin() { loginAttempt++; loginJob?.cancel(); state.update { it.copy(nativeLoginPending = false) } }
    fun login() {
        cancelLogin()
        val attempt = loginAttempt
        loginJob = scope.launch(Dispatchers.IO) {
            state.update { it.copy(nativeLoginPending = true) }
            try {
                val proof = UUID.randomUUID().toString() + UUID.randomUUID().toString()
                val challenge = MessageDigest.getInstance("SHA-256").digest(proof.toByteArray()).joinToString("") { "%02x".format(it) }
                val flow = PxBoardApi.request("/auth/native", body = JSONObject().put("name", name).put("challenge", challenge)) as JSONObject
                coroutineContext.ensureActive()
                open(flow.getString("authorizationUrl"))
                val expiresAt = Instant.parse(flow.getString("expiresAt")).toEpochMilli()
                while (System.currentTimeMillis() < expiresAt) {
                    delay(flow.getLong("intervalSeconds") * 1000L)
                    val result = PxBoardApi.request("/auth/native/${flow.getString("id")}/token", body = JSONObject().put("proof", proof)) as JSONObject
                    coroutineContext.ensureActive()
                    if (result.getString("status") == "pending") continue
                    val user = result.getJSONObject("user")
                    state.update { it.copy(authData = "PxBoard " + result.getString("token"), userEmail = user.getString("email"), nativeLoginPending = false) }
                    persist(state.value)
                    refresh()
                    return@launch
                }
                throw IllegalStateException("Authorization expired. Please sign in again.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message(e.message ?: e.toString()) }
            finally { if (attempt == loginAttempt) state.update { it.copy(nativeLoginPending = false) } }
        }
    }
    fun portal(path: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val link = PxBoardApi.request("/auth/browser-link", state.value.authData, JSONObject().put("redirectPath", path)) as JSONObject
                open(link.getString("url"))
            } catch (e: Exception) { message(e.message ?: e.toString()) }
        }
    }
    fun refresh() {
        if (state.value.nodesLoading || state.value.authData.isEmpty()) return
        val auth = state.value.authData
        state.update { it.copy(nodesLoading = true) }
        scope.launch(Dispatchers.IO) {
            try {
                val user = PxBoardApi.request("/me", auth) as JSONObject
                val plugins = PxBoardApi.request("/plugins", auth) as JSONArray
                val extensions = (0 until plugins.length()).map { plugins.getJSONObject(it) }.filter { it.getBoolean("enabled") }.map { it.getString("id") }.toSet()
                val accounts = PxBoardApi.request("/extensions/access/accounts?mine=1", auth) as JSONArray
                val account = if (accounts.length() == 0) null else accounts.getJSONObject(0)
                val used = (account?.getDouble("usedGiB") ?: 0.0) * 1073741824.0
                val quota = (account?.getDouble("quotaGiB") ?: 0.0) * 1073741824.0
                val expiry = account?.optString("expiresAt").orEmpty()
                val blocked = when {
                    account == null -> SUBSCRIPTION_BLOCK_NO_PLAN
                    account.getString("status") != "active" -> "suspended"
                    expiry.isNotEmpty() && !Instant.parse(expiry).isAfter(Instant.now()) -> SUBSCRIPTION_BLOCK_EXPIRED
                    !account.getBoolean("unlimited") && used >= quota -> SUBSCRIPTION_BLOCK_TRAFFIC
                    else -> ""
                }
                var nodes = emptyList<AnyTlsNode>()
                var routing = JSONObject().put("route_config_yaml", "").put("rule_count", 0).put("rules_preview", JSONArray())
                if (blocked.isEmpty()) {
                    val response = PxBoardApi.request("/extensions/subscriptions/configuration?format=aerion", auth) as JSONObject
                    nodes = response.getJSONArray("nodes").toAnyTlsNodeList()
                    val result = XboardApi.request("anytls_nodes", BuildConfig.DEFAULT_API_URL, "", JSONObject().put("subscribe_url", PxBoardApi.configurationUrl()).put("flag", "meta").put("authorization", PxBoardApi.authorization(auth)))
                    if (!result.getBoolean("ok")) throw IllegalStateException(resultError(result))
                    routing = result.getJSONObject("routing")
                }
                if (state.value.authData != auth) return@launch
                val summary = buildList {
                    add("${formatTrafficBytes(used)} / " + if (account?.optBoolean("unlimited") == true) "∞" else formatTrafficBytes(quota))
                    if (expiry.isNotEmpty()) add(formatUnixDate(Instant.parse(expiry).epochSecond))
                }.joinToString(" · ")
                state.update { it.copy(userEmail = user.getString("email"), nativeExtensions = extensions, subscriptionSummary = summary, subscriptionBlockReason = blocked,
                    subscriptionTrafficUsedBytes = used.toLong(), subscriptionTrafficTotalBytes = quota.toLong(), anyTlsNodes = nodes, nodesLoading = false,
                    selectedNodeIndex = it.selectedNodeIndex.coerceIn(0, (nodes.size - 1).coerceAtLeast(0)), nodeTestResults = emptyMap(),
                    nodesUpdatedAt = System.currentTimeMillis(), routeConfigYaml = routing.optString("route_config_yaml").takeUnless { value -> value == "null" }.orEmpty(),
                    routeRuleCount = routing.getInt("rule_count"), routeRulesPreview = routing.getJSONArray("rules_preview").let { values -> List(values.length()) { index -> values.getString(index) } }) }
                persist(state.value)
            } catch (e: Exception) { state.update { it.copy(nodesLoading = false) }; message(e.message ?: e.toString()) }
        }
    }
}
