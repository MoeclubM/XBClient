package moe.telecom.xbclient

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val Context.passVpnDataStore by preferencesDataStore(name = XBCLIENT_PREFS)

data class XbClientUiState(
    val loaded: Boolean = false,
    val authMode: AuthMode = AuthMode.LOGIN,
    val screen: PassScreen = PassScreen.NODES,
    val authData: String = "",
    val subscribeToken: String = "",
    val subscribeUrl: String = "",
    val subscriptionSummary: String = "",
    val anyTlsNodes: List<AnyTlsNode> = emptyList(),
    val selectedNodeIndex: Int = 0,
    val nodeTestResults: Map<Int, String> = emptyMap(),
    val invites: List<InviteItem> = emptyList(),
    val excludedApps: String = "",
    val allowedApps: String = "",
    val appRuleMode: String = MODE_EXCLUDE,
    val nodeDns: String = DEFAULT_NODE_DNS,
    val overseasDns: String = DEFAULT_OVERSEAS_DNS,
    val directDns: String = DEFAULT_DIRECT_DNS,
    val nodeTestTarget: String = DEFAULT_NODE_TEST_TARGET,
    val vpnDnsMode: String = DNS_MODE_OVER_TCP,
    val vpnIpv6Enabled: Boolean = true,
    val vpnRequested: Boolean = false,
    val vpnStarting: Boolean = false,
    val nodesLoading: Boolean = false,
    val nodesTesting: Boolean = false,
    val invitesLoading: Boolean = false,
    val installedApps: List<InstalledAppItem> = emptyList(),
    val appSearchQuery: String = "",
    val nodeSwitchSheet: Boolean = false,
    val nodeSwitchConnect: Boolean = false,
    val adEnabled: Boolean = false,
    val paymentEnabled: Boolean = true,
    val adRewardedAdUnitId: String = "",
    val adRewardAmount: Int = 0,
    val adRewardItem: String = "⭐",
    val adSsvUserId: String = "",
    val adSsvCustomData: String = "",
    val oauthProviders: List<OAuthProvider> = emptyList(),
    val oauthConfirmToken: String = "",
    val oauthConfirmProvider: String = "",
    val oauthConfirmEmail: String = ""
) {
    val isLoggedIn: Boolean
        get() = authData.isNotEmpty()

    val isRefreshing: Boolean
        get() = nodesLoading || invitesLoading || nodesTesting
}

sealed interface XbClientEvent {
    data class Message(val text: String) : XbClientEvent
    data class RequestVpnPermission(val nodeIndex: Int) : XbClientEvent
    data class ShowRewardAd(val adUnitId: String, val userId: String, val customData: String) : XbClientEvent
}

class XbClientViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val _uiState = MutableStateFlow(XbClientUiState())
    val uiState = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<XbClientEvent>()
    val events = _events.asSharedFlow()
    private var pendingNodeSwitchConnect: Boolean? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            loadStoredState()
            loadInstalledApps()
            refreshOAuthProviders()
            val state = _uiState.value
            if (state.authData.isNotEmpty()) {
                refreshSubscriptionAndNodes()
                refreshInvites()
                refreshRewardConfig()
            }
        }
    }

    fun showLogin() {
        _uiState.update { it.copy(authMode = AuthMode.LOGIN) }
    }

    fun showRegister() {
        _uiState.update { it.copy(authMode = AuthMode.REGISTER) }
    }

    fun openScreen(screen: PassScreen) {
        _uiState.update { it.copy(screen = screen) }
        when (screen) {
            PassScreen.PROFILE -> {
                refreshSubscriptionAndNodes()
                refreshInvites()
                refreshRewardConfig()
            }
            PassScreen.NODE_SELECT -> refreshSubscriptionAndNodes()
            PassScreen.APP_RULES -> Unit
            PassScreen.SETTINGS -> Unit
            PassScreen.NODES -> refreshSubscriptionAndNodes()
        }
    }

    fun refreshCurrentPage() {
        when (_uiState.value.screen) {
            PassScreen.PROFILE -> {
                refreshSubscriptionAndNodes()
                refreshInvites()
                refreshRewardConfig()
            }
            PassScreen.NODE_SELECT -> refreshSubscriptionAndNodes()
            PassScreen.SETTINGS, PassScreen.APP_RULES -> Unit
            PassScreen.NODES -> refreshSubscriptionAndNodes()
        }
    }

    fun navigateBack() {
        val state = _uiState.value
        if (!state.isLoggedIn && state.authMode == AuthMode.REGISTER) {
            showLogin()
            return
        }
        if (!state.isLoggedIn) {
            return
        }
        when (state.screen) {
            PassScreen.NODE_SELECT -> openScreen(PassScreen.NODES)
            PassScreen.APP_RULES -> openScreen(PassScreen.SETTINGS)
            PassScreen.SETTINGS -> openScreen(PassScreen.PROFILE)
            PassScreen.NODES, PassScreen.PROFILE -> Unit
        }
    }

    fun login(email: String, password: String) {
        val params = JSONObject()
        putString(params, "email", email.trim())
        putString(params, "password", password)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = XboardApi.request("login", defaultApiUrl(), "", params)
                val body = requireSuccessfulBody("登录", result)
                val data = body.getJSONObject("data")
                val next = _uiState.value.copy(
                    authMode = AuthMode.LOGIN,
                    screen = PassScreen.NODES,
                    authData = data.getString("auth_data"),
                    subscribeToken = data.optString("token", _uiState.value.subscribeToken),
                    subscribeUrl = data.optString("subscribe_url", _uiState.value.subscribeUrl)
                )
                _uiState.value = next
                persistStoredState(next)
                emitMessage("登录成功。")
                refreshSubscriptionAndNodes()
                refreshInvites()
                refreshRewardConfig()
            } catch (error: Exception) {
                emitMessage("登录失败：${error.message}")
            }
        }
    }

    fun register(email: String, password: String, inviteCode: String, emailCode: String, captcha: String) {
        val params = JSONObject()
        putString(params, "email", email.trim())
        putString(params, "password", password)
        putString(params, "invite_code", inviteCode.trim())
        putString(params, "email_code", emailCode.trim())
        putCaptcha(params, captcha)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = XboardApi.request("register", defaultApiUrl(), "", params)
                val body = requireSuccessfulBody("注册", result)
                val data = body.getJSONObject("data")
                val next = _uiState.value.copy(
                    authMode = AuthMode.LOGIN,
                    screen = PassScreen.NODES,
                    authData = data.getString("auth_data"),
                    subscribeToken = data.optString("token", _uiState.value.subscribeToken),
                    subscribeUrl = data.optString("subscribe_url", _uiState.value.subscribeUrl)
                )
                _uiState.value = next
                persistStoredState(next)
                emitMessage("注册成功。")
                refreshSubscriptionAndNodes()
                refreshInvites()
                refreshRewardConfig()
            } catch (error: Exception) {
                emitMessage("注册失败：${error.message}")
            }
        }
    }

    fun sendEmailVerify(email: String, captcha: String) {
        val params = JSONObject()
        putString(params, "email", email.trim())
        putCaptcha(params, captcha)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = XboardApi.request("send_email_verify", defaultApiUrl(), "", params)
                requireSuccessfulBody("发送邮箱验证码", result)
                emitMessage("邮箱验证码已发送。")
            } catch (error: Exception) {
                emitMessage("发送邮箱验证码失败：${error.message}")
            }
        }
    }

    fun refreshOAuthProviders() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = XboardApi.request("guest_config", defaultApiUrl(), "", JSONObject())
                val body = requireSuccessfulBody("访客配置", result)
                val providers = body.optJSONObject("data")
                    ?.optJSONArray("oauth_providers")
                    ?.toOAuthProviderList()
                    ?: emptyList()
                _uiState.update { it.copy(oauthProviders = providers) }
            } catch (error: Exception) {
                emitMessage("OAuth 配置加载失败：${error.message}")
            }
        }
    }

    fun openOAuthPage(context: Context, scene: String, driver: String, inviteCode: String = "") {
        val builder = Uri.parse("${defaultApiUrl().trimEnd('/')}/api/v1/passport/auth/oauth/$driver/redirect")
            .buildUpon()
            .appendQueryParameter("scene", scene)
            .appendQueryParameter("redirect", "dashboard")
            .appendQueryParameter("client", "app")
        if (scene == "register" && inviteCode.trim().isNotEmpty()) {
            builder.appendQueryParameter("invite_code", inviteCode.trim())
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, builder.build()))
    }

    fun handleOAuthCallback(uri: Uri) {
        val error = uri.getQueryParameter("oauth_error").orEmpty()
        if (error.isNotEmpty()) {
            emitMessage("OAuth 失败：$error")
            return
        }
        val success = uri.getQueryParameter("oauth_success").orEmpty()
        if (success.isNotEmpty()) {
            emitMessage(success)
            return
        }
        val confirmToken = uri.getQueryParameter("oauth_confirm_token").orEmpty()
        if (confirmToken.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    authMode = AuthMode.REGISTER,
                    oauthConfirmToken = confirmToken,
                    oauthConfirmProvider = uri.getQueryParameter("oauth_provider").orEmpty(),
                    oauthConfirmEmail = uri.getQueryParameter("oauth_email").orEmpty()
                )
            }
            emitMessage("请确认 OAuth 注册。")
            return
        }
        val verify = uri.getQueryParameter("verify").orEmpty()
        if (verify.isNotEmpty()) {
            completeOAuthLogin(verify)
        }
    }

    fun confirmOAuthRegister() {
        val token = _uiState.value.oauthConfirmToken
        if (token.isEmpty()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = requireSuccessfulBody(
                    "OAuth 注册确认",
                    XboardApi.request("confirm_oauth_register", defaultApiUrl(), "", JSONObject().put("token", token))
                )
                completeOAuthLogin(verifyFromQuickLoginUrl(body.getString("data")))
            } catch (error: Exception) {
                emitMessage("OAuth 注册失败：${error.message}")
            }
        }
    }

    fun clearOAuthConfirm() {
        _uiState.update { it.copy(oauthConfirmToken = "", oauthConfirmProvider = "", oauthConfirmEmail = "") }
    }

    fun logout() {
        val next = XbClientUiState(loaded = true, oauthProviders = _uiState.value.oauthProviders)
        _uiState.value = next
        persistState(next)
    }

    fun refreshSubscriptionAndNodes() {
        val current = _uiState.value
        if (current.authData.isEmpty() || current.nodesLoading) {
            return
        }
        _uiState.update { it.copy(nodesLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val subscribeResult = XboardApi.request("user_subscribe", defaultApiUrl(), current.authData, JSONObject())
                val subscribeBody = requireSuccessfulBody("订阅同步", subscribeResult)
                val data = subscribeBody.getJSONObject("data")
                val subscribeUrl = data.optString("subscribe_url", current.subscribeUrl)
                if (subscribeUrl.isEmpty()) {
                    throw IllegalStateException("订阅地址为空。")
                }
                val nodesResult = XboardApi.request(
                    "anytls_nodes",
                    defaultApiUrl(),
                    "",
                    JSONObject().put("subscribe_url", subscribeUrl).put("flag", "meta")
                )
                if (!nodesResult.optBoolean("ok")) {
                    throw IllegalStateException(resultError(nodesResult))
                }
                val nodes = nodesResult.getJSONArray("nodes").toAnyTlsNodeList()
                val next = _uiState.value.copy(
                    subscribeToken = data.optString("token", current.subscribeToken),
                    subscribeUrl = subscribeUrl,
                    subscriptionSummary = subscriptionSummary(data),
                    anyTlsNodes = nodes,
                    selectedNodeIndex = _uiState.value.selectedNodeIndex.coerceIn(0, (nodes.size - 1).coerceAtLeast(0)),
                    nodeTestResults = emptyMap(),
                    nodesLoading = false
                )
                _uiState.value = next
                persistStoredState(next)
            } catch (error: Exception) {
                _uiState.update { it.copy(nodesLoading = false) }
                emitMessage("节点同步失败：${error.message}")
            }
        }
    }

    fun refreshInvites() {
        val authData = _uiState.value.authData
        if (authData.isEmpty() || _uiState.value.invitesLoading) {
            return
        }
        _uiState.update { it.copy(invitesLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = XboardApi.request("invite_fetch", defaultApiUrl(), authData, JSONObject())
                val body = requireSuccessfulBody("邀请码加载", result)
                val invites = extractDataArray(body).toInviteItemList()
                _uiState.update { it.copy(invites = invites, invitesLoading = false) }
            } catch (error: Exception) {
                _uiState.update { it.copy(invitesLoading = false) }
                emitMessage("邀请码加载失败：${error.message}")
            }
        }
    }

    fun generateInvite() {
        val authData = _uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = XboardApi.request("invite_save", defaultApiUrl(), authData, JSONObject())
                requireSuccessfulBody("生成邀请码", result)
                emitMessage("邀请码已生成。")
                refreshInvites()
            } catch (error: Exception) {
                emitMessage("生成邀请码失败：${error.message}")
            }
        }
    }

    fun refreshRewardConfig() {
        val authData = _uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            loadRewardConfig(authData)
        }
    }

    fun requestRewardAd() {
        val authData = _uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = loadRewardConfig(authData)
                if (config.first.isEmpty()) {
                    emitMessage("广告暂未开启。")
                    return@launch
                }
                emitEvent(XbClientEvent.ShowRewardAd(config.first, config.second, config.third))
            } catch (error: Exception) {
                clearRewardConfig()
                emitMessage("广告暂未开启。")
            }
        }
    }

    fun onRewardAdEarned(amount: Int, type: String) {
        emitMessage("广告观看完成：$amount${type.ifBlank { _uiState.value.adRewardItem }}")
        refreshSubscriptionAndNodes()
        refreshRewardConfig()
    }

    fun openPaymentPage(context: Context) {
        val authData = _uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = XboardApi.request(
                    "quick_login_url",
                    defaultApiUrl(),
                    authData,
                    JSONObject().put("redirect", "plan")
                )
                val body = requireSuccessfulBody("网页登录", result)
                val loginUrl = body.getString("data")
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)))
                }
            } catch (error: Exception) {
                emitMessage("网页支付打开失败：${error.message}")
            }
        }
    }

    private fun completeOAuthLogin(verify: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = XboardApi.request("token_login", defaultApiUrl(), "", JSONObject().put("verify", verify))
                val body = requireSuccessfulBody("OAuth 登录", result)
                val data = body.getJSONObject("data")
                val next = _uiState.value.copy(
                    authMode = AuthMode.LOGIN,
                    screen = PassScreen.NODES,
                    authData = data.getString("auth_data"),
                    subscribeToken = data.optString("token", _uiState.value.subscribeToken),
                    oauthConfirmToken = "",
                    oauthConfirmProvider = "",
                    oauthConfirmEmail = ""
                )
                _uiState.value = next
                persistStoredState(next)
                emitMessage("OAuth 登录成功。")
                refreshSubscriptionAndNodes()
                refreshInvites()
                refreshRewardConfig()
            } catch (error: Exception) {
                emitMessage("OAuth 登录失败：${error.message}")
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

    private fun loadRewardConfig(authData: String): Triple<String, String, String> {
        val result = try {
            XboardApi.request("admob_reward_config", defaultApiUrl(), authData, JSONObject())
        } catch (_: Exception) {
            clearRewardConfig()
            return Triple("", "", "")
        }
        if (!result.optBoolean("ok")) {
            clearRewardConfig()
            return Triple("", "", "")
        }
        val body = result.optJSONObject("body")
        if (body == null || body.optString("status") == "fail") {
            clearRewardConfig()
            return Triple("", "", "")
        }
        val data = body.optJSONObject("data")
        if (data == null) {
            clearRewardConfig()
            return Triple("", "", "")
        }
        if (!data.optBoolean("ad_enabled")) {
            _uiState.update {
                it.copy(
                    adEnabled = false,
                    paymentEnabled = data.optBoolean("payment_enabled"),
                    adRewardedAdUnitId = "",
                    adRewardAmount = 0,
                    adSsvUserId = "",
                    adSsvCustomData = ""
                )
            }
            return Triple("", "", "")
        }
        val adUnitId = data.optString("rewarded_ad_unit_id")
        val userId = data.optString("ssv_user_id")
        val customData = data.optString("ssv_custom_data")
        if (adUnitId.isEmpty() || userId.isEmpty() || customData.isEmpty()) {
            _uiState.update { it.copy(adEnabled = false, paymentEnabled = data.optBoolean("payment_enabled")) }
            return Triple("", "", "")
        }
        _uiState.update {
            it.copy(
                adEnabled = true,
                paymentEnabled = data.optBoolean("payment_enabled"),
                adRewardedAdUnitId = adUnitId,
                adRewardAmount = data.optInt("reward_amount"),
                adRewardItem = data.optString("reward_item", it.adRewardItem).ifBlank { it.adRewardItem },
                adSsvUserId = userId,
                adSsvCustomData = customData
            )
        }
        return Triple(adUnitId, userId, customData)
    }

    private fun clearRewardConfig() {
        _uiState.update {
            it.copy(
                adEnabled = false,
                paymentEnabled = true,
                adRewardedAdUnitId = "",
                adRewardAmount = 0,
                adSsvUserId = "",
                adSsvCustomData = ""
            )
        }
    }

    fun saveDnsAndTestSettings(nodeDns: String, overseasDns: String, directDns: String, nodeTestTarget: String) {
        if (nodeDns.trim().isEmpty() || overseasDns.trim().isEmpty() || directDns.trim().isEmpty() || nodeTestTarget.trim().isEmpty()) {
            emitMessage("DNS 与测试目标不能为空。")
            return
        }
        updateAndPersist {
            it.copy(
                nodeDns = nodeDns.trim(),
                overseasDns = overseasDns.trim(),
                directDns = directDns.trim(),
                nodeTestTarget = nodeTestTarget.trim()
            )
        }
        emitMessage("设置已保存。")
    }

    fun setIpv6Enabled(enabled: Boolean) {
        updateAndPersist { it.copy(vpnIpv6Enabled = enabled) }
    }

    fun switchAppRuleMode(mode: String) {
        if (mode != MODE_ALLOW && mode != MODE_EXCLUDE) {
            return
        }
        updateAndPersist { state ->
            val current = selectedAppPackages(state)
            if (mode == MODE_ALLOW) {
                state.copy(appRuleMode = MODE_ALLOW, allowedApps = current, excludedApps = "")
            } else {
                state.copy(appRuleMode = MODE_EXCLUDE, excludedApps = current, allowedApps = "")
            }
        }
    }

    fun setAppSearchQuery(query: String) {
        _uiState.update { it.copy(appSearchQuery = query) }
    }

    fun setAppSelected(packageName: String, selected: Boolean) {
        updateAndPersist { state ->
            val packages = LinkedHashSet(selectedAppPackages(state).split(Regex("[,;\\s]+")).filter { it.isNotEmpty() })
            if (selected) {
                packages.add(packageName)
            } else {
                packages.remove(packageName)
            }
            val value = packages.joinToString("\n")
            if (state.appRuleMode == MODE_ALLOW) {
                state.copy(allowedApps = value, excludedApps = "")
            } else {
                state.copy(excludedApps = value, allowedApps = "")
            }
        }
    }

    fun clearSelectedApps() {
        updateAndPersist { state ->
            if (state.appRuleMode == MODE_ALLOW) {
                state.copy(allowedApps = "")
            } else {
                state.copy(excludedApps = "")
            }
        }
    }

    fun selectNode(index: Int, returnToNodes: Boolean) {
        val nodes = _uiState.value.anyTlsNodes
        if (index !in nodes.indices) {
            return
        }
        updateAndPersist {
            it.copy(
                selectedNodeIndex = index,
                screen = if (returnToNodes) PassScreen.NODES else it.screen,
                nodeSwitchSheet = false
            )
        }
    }

    fun requestNodeSwitchDialog(connectAfterSelect: Boolean) {
        val state = _uiState.value
        if (!state.loaded) {
            pendingNodeSwitchConnect = connectAfterSelect
            return
        }
        if (state.authData.isEmpty()) {
            return
        }
        if (state.anyTlsNodes.isEmpty()) {
            refreshSubscriptionAndNodes()
            emitMessage("节点正在同步，请稍后再试。")
            return
        }
        _uiState.update { it.copy(nodeSwitchSheet = true, nodeSwitchConnect = connectAfterSelect) }
    }

    fun dismissNodeSwitchDialog() {
        _uiState.update { it.copy(nodeSwitchSheet = false) }
    }

    fun chooseNodeFromDialog(index: Int) {
        val connectAfterSelect = _uiState.value.nodeSwitchConnect
        selectNode(index, returnToNodes = !connectAfterSelect)
        if (connectAfterSelect) {
            requestStartVpn()
        }
    }

    fun testNode(index: Int) {
        val nodes = _uiState.value.anyTlsNodes
        if (index !in nodes.indices) {
            return
        }
        _uiState.update { it.copy(nodeTestResults = it.nodeTestResults + (index to "测试中")) }
        val node = nodes[index]
        viewModelScope.launch(Dispatchers.IO) {
            val text = testNodeBlocking(node)
            _uiState.update { it.copy(nodeTestResults = it.nodeTestResults + (index to text)) }
        }
    }

    fun testAllNodes() {
        val nodes = _uiState.value.anyTlsNodes
        if (_uiState.value.nodesTesting || nodes.isEmpty()) {
            return
        }
        _uiState.update { it.copy(nodesTesting = true, nodeTestResults = emptyMap()) }
        viewModelScope.launch(Dispatchers.IO) {
            for (index in nodes.indices) {
                _uiState.update { it.copy(nodeTestResults = it.nodeTestResults + (index to "测试中")) }
                val text = testNodeBlocking(nodes[index])
                _uiState.update { it.copy(nodeTestResults = it.nodeTestResults + (index to text)) }
            }
            _uiState.update { it.copy(nodesTesting = false) }
            emitMessage("节点测试完成。")
        }
    }

    fun requestStartVpn() {
        try {
            val state = _uiState.value
            if (state.excludedApps.isNotEmpty() && state.allowedApps.isNotEmpty()) {
                throw IllegalStateException("应用排除与应用白名单不能同时填写。")
            }
            if (state.appRuleMode == MODE_ALLOW && state.allowedApps.isEmpty()) {
                throw IllegalStateException("白名单模式尚未选择应用。")
            }
            if (state.anyTlsNodes.isEmpty()) {
                throw IllegalStateException("节点尚未同步完成。")
            }
            val selectedIndex = state.selectedNodeIndex.coerceIn(0, state.anyTlsNodes.size - 1)
            updateAndPersist { it.copy(selectedNodeIndex = selectedIndex) }
            emitEvent(XbClientEvent.RequestVpnPermission(selectedIndex))
        } catch (error: Exception) {
            emitMessage("连接启动失败：${error.message}")
        }
    }

    fun beginVpn(context: Context, nodeIndex: Int) {
        val state = _uiState.value
        val selectedIndex = nodeIndex.coerceIn(0, (state.anyTlsNodes.size - 1).coerceAtLeast(0))
        val intent = Intent(context, XbClientVpnService::class.java).apply {
            action = XbClientVpnService.ACTION_START
            putExtra(XbClientVpnService.EXTRA_NODE, state.anyTlsNodes[selectedIndex].rawJson)
            putExtra(XbClientVpnService.EXTRA_NODES, nodesJson(state.anyTlsNodes))
            putExtra(XbClientVpnService.EXTRA_NODE_INDEX, selectedIndex)
            putExtra(XbClientVpnService.EXTRA_EXCLUDED_APPS, state.excludedApps)
            putExtra(XbClientVpnService.EXTRA_ALLOWED_APPS, state.allowedApps)
            putExtra(XbClientVpnService.EXTRA_NODE_DNS, state.nodeDns)
            putExtra(XbClientVpnService.EXTRA_OVERSEAS_DNS, state.overseasDns)
            putExtra(XbClientVpnService.EXTRA_DIRECT_DNS, state.directDns)
            putExtra(XbClientVpnService.EXTRA_DNS_MODE, state.vpnDnsMode)
            putExtra(XbClientVpnService.EXTRA_IPV6_ENABLED, state.vpnIpv6Enabled)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        _uiState.update { it.copy(vpnStarting = true, screen = PassScreen.NODES, selectedNodeIndex = selectedIndex) }
        emitMessage("连接请求已提交。")
    }

    fun stopVpn(context: Context) {
        val intent = Intent(context, XbClientVpnService::class.java).apply {
            action = XbClientVpnService.ACTION_STOP
        }
        context.startService(intent)
        updateAndPersist { it.copy(vpnRequested = false) }
        emitMessage("停止连接请求已提交。")
    }

    fun onVpnStateChanged(running: Boolean, nodeIndex: Int, error: String) {
        updateAndPersist { state ->
            val selected = if (nodeIndex >= 0 && state.anyTlsNodes.isNotEmpty()) {
                nodeIndex.coerceIn(0, state.anyTlsNodes.size - 1)
            } else {
                state.selectedNodeIndex
            }
            state.copy(vpnRequested = running, vpnStarting = false, selectedNodeIndex = selected)
        }
        if (error.isNotEmpty()) {
            emitMessage(error)
        }
    }

    private fun testNodeBlocking(node: AnyTlsNode): String {
        return try {
            val testNode = JSONObject(node.rawJson)
            val originalHost = testNode.getString("host")
            val resolvedHost = XboardApi.resolveNodeHost(_uiState.value.nodeDns, originalHost)
            if (resolvedHost != originalHost && testNode.optString("sni").isEmpty()) {
                testNode.put("sni", originalHost)
            }
            testNode.put("host", resolvedHost)
            val protocol = testNode.optString("type")
            if (protocol == "hysteria2" || protocol == "hy2") {
                testNode.put("server", resolvedHost)
            }
            val (targetHost, targetPort, targetTls) = targetHostPort(_uiState.value.nodeTestTarget.trim().ifEmpty { DEFAULT_NODE_TEST_TARGET })
            val result = JSONObject(
                RustCore.testAnyTlsNode(
                    JSONObject()
                        .put("node", testNode)
                        .put("target_host", targetHost)
                        .put("target_port", targetPort)
                        .put("target_tls", targetTls)
                        .toString()
                )
            )
            if (result.optBoolean("ok")) {
                "${result.optLong("latency_ms")} ms · ${result.optString("target_host")}:${result.optInt("target_port")}"
            } else {
                readableNodeTestError(result.optString("error", result.toString()))
            }
        } catch (error: Exception) {
            readableNodeTestError(error.message.orEmpty())
        }
    }

    private fun targetHostPort(target: String): Triple<String, Int, Boolean> {
        var targetHost = target
        var targetPort = 80
        var targetTls = false
        var schemeSpecified = false
        if (target.startsWith("http://") || target.startsWith("https://")) {
            val uri = Uri.parse(target)
            targetHost = uri.host ?: throw IllegalStateException("测试目标地址无效。")
            targetTls = uri.scheme == "https"
            schemeSpecified = true
            targetPort = if (uri.port > 0) uri.port else if (targetTls) 443 else 80
        } else {
            val colon = target.lastIndexOf(':')
            if (colon > 0 && target.indexOf(':') == colon) {
                targetHost = target.substring(0, colon)
                targetPort = target.substring(colon + 1).toInt()
            }
        }
        return Triple(targetHost, targetPort, if (schemeSpecified) targetTls else targetPort == 443)
    }

    private suspend fun loadStoredState() {
        val prefs = app.passVpnDataStore.data.first()
        val hasDataStoreState = prefs[Keys.AUTH_DATA] != null ||
            prefs[Keys.NODE_DNS] != null ||
            prefs[Keys.ANYTLS_NODES] != null
        val legacy = app.getSharedPreferences(XBCLIENT_PREFS, Context.MODE_PRIVATE)
        val state = if (hasDataStoreState) {
            XbClientUiState(
                loaded = true,
                authData = prefs[Keys.AUTH_DATA].orEmpty(),
                subscribeToken = prefs[Keys.SUBSCRIBE_TOKEN].orEmpty(),
                subscribeUrl = prefs[Keys.SUBSCRIBE_URL].orEmpty(),
                subscriptionSummary = prefs[Keys.SUBSCRIPTION_SUMMARY].orEmpty(),
                anyTlsNodes = cachedNodes(prefs[Keys.ANYTLS_NODES].orEmpty()),
                selectedNodeIndex = prefs[Keys.SELECTED_NODE_INDEX] ?: 0,
                excludedApps = prefs[Keys.EXCLUDED_APPS].orEmpty(),
                allowedApps = prefs[Keys.ALLOWED_APPS].orEmpty(),
                appRuleMode = prefs[Keys.APP_RULE_MODE] ?: MODE_EXCLUDE,
                nodeDns = prefs[Keys.NODE_DNS] ?: DEFAULT_NODE_DNS,
                overseasDns = prefs[Keys.OVERSEAS_DNS] ?: DEFAULT_OVERSEAS_DNS,
                directDns = prefs[Keys.DIRECT_DNS] ?: DEFAULT_DIRECT_DNS,
                nodeTestTarget = prefs[Keys.NODE_TEST_TARGET] ?: DEFAULT_NODE_TEST_TARGET,
                vpnDnsMode = prefs[Keys.VPN_DNS_MODE] ?: DNS_MODE_OVER_TCP,
                vpnIpv6Enabled = prefs[Keys.VPN_IPV6_ENABLED] ?: true,
                vpnRequested = prefs[Keys.VPN_RUNNING] ?: legacy.getBoolean("vpn_running", false)
            )
        } else {
            XbClientUiState(
                loaded = true,
                authData = legacy.getString("auth_data", "").orEmpty(),
                subscribeToken = legacy.getString("subscribe_token", "").orEmpty(),
                subscribeUrl = legacy.getString("subscribe_url", "").orEmpty(),
                subscriptionSummary = legacy.getString("subscription_summary", "").orEmpty(),
                anyTlsNodes = cachedNodes(legacy.getString("anytls_nodes", "").orEmpty()),
                selectedNodeIndex = legacy.getInt("selected_node_index", 0),
                excludedApps = legacy.getString("excluded_apps", "").orEmpty(),
                allowedApps = legacy.getString("allowed_apps", "").orEmpty(),
                appRuleMode = legacy.getString("app_rule_mode", if (legacy.getString("allowed_apps", "").orEmpty().isNotEmpty()) MODE_ALLOW else MODE_EXCLUDE).orEmpty(),
                nodeDns = legacy.getString("node_dns", DEFAULT_NODE_DNS).orEmpty(),
                overseasDns = legacy.getString("overseas_dns", DEFAULT_OVERSEAS_DNS).orEmpty(),
                directDns = legacy.getString("direct_dns", DEFAULT_DIRECT_DNS).orEmpty(),
                nodeTestTarget = legacy.getString("node_test_target", DEFAULT_NODE_TEST_TARGET).orEmpty(),
                vpnDnsMode = legacy.getString("vpn_dns_mode", DNS_MODE_OVER_TCP).orEmpty(),
                vpnIpv6Enabled = legacy.getBoolean("vpn_ipv6_enabled", true),
                vpnRequested = legacy.getBoolean("vpn_running", false)
            )
        }
        _uiState.value = state.copy(selectedNodeIndex = state.selectedNodeIndex.coerceIn(0, (state.anyTlsNodes.size - 1).coerceAtLeast(0)))
        if (!hasDataStoreState) {
            persistStoredState(_uiState.value)
        }
        pendingNodeSwitchConnect?.let { connectAfterSelect ->
            pendingNodeSwitchConnect = null
            requestNodeSwitchDialog(connectAfterSelect)
        }
    }

    private suspend fun loadInstalledApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = app.packageManager.queryIntentActivities(intent, 0)
            .map { info -> InstalledAppItem(info.loadLabel(app.packageManager).toString(), info.activityInfo.packageName) }
            .filter { it.packageName != app.packageName }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        _uiState.update { it.copy(installedApps = apps) }
    }

    private fun updateAndPersist(block: (XbClientUiState) -> XbClientUiState) {
        var next: XbClientUiState? = null
        _uiState.update { current ->
            block(current).also { next = it }
        }
        persistState(next ?: _uiState.value)
    }

    private fun persistState(state: XbClientUiState) {
        viewModelScope.launch(Dispatchers.IO) {
            persistStoredState(state)
        }
    }

    private suspend fun persistStoredState(state: XbClientUiState) {
        app.passVpnDataStore.edit { prefs ->
            prefs[Keys.AUTH_DATA] = state.authData
            prefs[Keys.SUBSCRIBE_TOKEN] = state.subscribeToken
            prefs[Keys.SUBSCRIBE_URL] = state.subscribeUrl
            prefs[Keys.SUBSCRIPTION_SUMMARY] = state.subscriptionSummary
            prefs[Keys.ANYTLS_NODES] = nodesJson(state.anyTlsNodes)
            prefs[Keys.SELECTED_NODE_INDEX] = state.selectedNodeIndex
            prefs[Keys.EXCLUDED_APPS] = state.excludedApps
            prefs[Keys.ALLOWED_APPS] = state.allowedApps
            prefs[Keys.APP_RULE_MODE] = state.appRuleMode
            prefs[Keys.NODE_DNS] = state.nodeDns
            prefs[Keys.OVERSEAS_DNS] = state.overseasDns
            prefs[Keys.DIRECT_DNS] = state.directDns
            prefs[Keys.NODE_TEST_TARGET] = state.nodeTestTarget
            prefs[Keys.VPN_DNS_MODE] = state.vpnDnsMode
            prefs[Keys.VPN_IPV6_ENABLED] = state.vpnIpv6Enabled
            prefs[Keys.VPN_RUNNING] = state.vpnRequested
        }
        app.getSharedPreferences(XBCLIENT_PREFS, Context.MODE_PRIVATE).edit()
            .putString("auth_data", state.authData)
            .putString("subscribe_token", state.subscribeToken)
            .putString("subscribe_url", state.subscribeUrl)
            .putString("subscription_summary", state.subscriptionSummary)
            .putString("anytls_nodes", nodesJson(state.anyTlsNodes))
            .putInt("selected_node_index", state.selectedNodeIndex)
            .putString("excluded_apps", state.excludedApps)
            .putString("allowed_apps", state.allowedApps)
            .putString("app_rule_mode", state.appRuleMode)
            .putString("node_dns", state.nodeDns)
            .putString("overseas_dns", state.overseasDns)
            .putString("direct_dns", state.directDns)
            .putString("node_test_target", state.nodeTestTarget)
            .putString("vpn_dns_mode", state.vpnDnsMode)
            .putBoolean("vpn_ipv6_enabled", state.vpnIpv6Enabled)
            .putBoolean("vpn_running", state.vpnRequested)
            .apply()
    }

    private fun cachedNodes(value: String): List<AnyTlsNode> =
        if (value.isEmpty()) emptyList() else JSONArray(value).toAnyTlsNodeList()

    private fun nodesJson(nodes: List<AnyTlsNode>): String =
        JSONArray().also { array ->
            for (node in nodes) {
                array.put(JSONObject(node.rawJson))
            }
        }.toString()

    private fun selectedAppPackages(state: XbClientUiState): String =
        if (state.appRuleMode == MODE_ALLOW) state.allowedApps else state.excludedApps

    private fun requireSuccessfulBody(title: String, result: JSONObject): JSONObject {
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(resultError(result))
        }
        val body = result.optJSONObject("body") ?: throw IllegalStateException("$title 响应不是 JSON。")
        if (body.optString("status") == "fail") {
            throw IllegalStateException(body.optString("message"))
        }
        return body
    }

    private fun putString(params: JSONObject, key: String, value: String) {
        if (value.isNotEmpty()) {
            params.put(key, value)
        }
    }

    private fun putCaptcha(params: JSONObject, captcha: String) {
        val token = captcha.trim()
        if (token.isNotEmpty()) {
            params.put("recaptcha_data", token)
            params.put("recaptcha_v3_token", token)
            params.put("cf_turnstile_response", token)
        }
    }

    private fun defaultApiUrl(): String {
        val value = BuildConfig.DEFAULT_API_URL.trim()
        return if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
    }

    private fun emitMessage(text: String) {
        emitEvent(XbClientEvent.Message(text))
    }

    private fun emitEvent(event: XbClientEvent) {
        viewModelScope.launch {
            _events.emit(event)
        }
    }

    private object Keys {
        val AUTH_DATA = stringPreferencesKey("auth_data")
        val SUBSCRIBE_TOKEN = stringPreferencesKey("subscribe_token")
        val SUBSCRIBE_URL = stringPreferencesKey("subscribe_url")
        val SUBSCRIPTION_SUMMARY = stringPreferencesKey("subscription_summary")
        val ANYTLS_NODES = stringPreferencesKey("anytls_nodes")
        val SELECTED_NODE_INDEX = intPreferencesKey("selected_node_index")
        val EXCLUDED_APPS = stringPreferencesKey("excluded_apps")
        val ALLOWED_APPS = stringPreferencesKey("allowed_apps")
        val APP_RULE_MODE = stringPreferencesKey("app_rule_mode")
        val NODE_DNS = stringPreferencesKey("node_dns")
        val OVERSEAS_DNS = stringPreferencesKey("overseas_dns")
        val DIRECT_DNS = stringPreferencesKey("direct_dns")
        val NODE_TEST_TARGET = stringPreferencesKey("node_test_target")
        val VPN_DNS_MODE = stringPreferencesKey("vpn_dns_mode")
        val VPN_IPV6_ENABLED = booleanPreferencesKey("vpn_ipv6_enabled")
        val VPN_RUNNING = booleanPreferencesKey("vpn_running")
    }
}
