package moe.telecom.xbclient

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private val Context.passVpnDataStore by preferencesDataStore(name = XBCLIENT_PREFS)
private const val NODE_AUTO_REFRESH_INTERVAL_MS = 30L * 60L * 1000L

data class XbClientUiState(
    val loaded: Boolean = false,
    val authMode: AuthMode = AuthMode.LOGIN,
    val screen: PassScreen = PassScreen.NODES,
    val authData: String = "",
    val subscribeToken: String = "",
    val subscribeUrl: String = "",
    val subscriptionSummary: String = "",
    val subscriptionBlockReason: String = "",
    val nodesUpdatedAt: Long = 0L,
    val userEmail: String = "",
    val balance: Int = 0,
    val commissionBalance: Int = 0,
    val currencySymbol: String = "¥",
    val plans: List<PlanItem> = emptyList(),
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
    val userLoading: Boolean = false,
    val nodesLoading: Boolean = false,
    val plansLoading: Boolean = false,
    val nodesTesting: Boolean = false,
    val invitesLoading: Boolean = false,
    val installedApps: List<InstalledAppItem> = emptyList(),
    val appSearchQuery: String = "",
    val nodeSwitchSheet: Boolean = false,
    val nodeSwitchConnect: Boolean = false,
    val adEnabled: Boolean = false,
    val paymentEnabled: Boolean = true,
    val planRewardAdEnabled: Boolean = false,
    val planRewardedAdUnitId: String = "",
    val pointsRewardAdEnabled: Boolean = false,
    val pointsRewardedAdUnitId: String = "",
    val appOpenAdEnabled: Boolean = false,
    val appOpenAdUnitId: String = "",
    val adRewardLogs: List<AdRewardLogItem> = emptyList(),
    val adRewardLogsLoading: Boolean = false,
    val configUpdatedAt: Long = 0L,
    val githubProjectUrl: String = "",
    val updateAvailable: Boolean = false,
    val latestReleaseVersion: String = "",
    val latestReleaseUrl: String = "",
    val latestDownloadUrl: String = "",
    val oauthProviders: List<OAuthProvider> = emptyList(),
    val oauthConfirmToken: String = "",
    val oauthConfirmProvider: String = "",
    val oauthConfirmEmail: String = "",
    val oauthWebViewUrl: String = ""
) {
    val isLoggedIn: Boolean
        get() = authData.isNotEmpty()

    val subscriptionBlocked: Boolean
        get() = subscriptionBlockReason.isNotEmpty()

    val isRefreshing: Boolean
        get() = userLoading || nodesLoading || plansLoading || invitesLoading || nodesTesting
}

sealed interface XbClientEvent {
    data class Message(val text: String) : XbClientEvent
    data class RequestVpnPermission(val nodeIndex: Int) : XbClientEvent
    data class ShowRewardAd(val adUnitId: String, val userId: String, val customData: String) : XbClientEvent
    data class OpenExternalUrl(val url: String) : XbClientEvent
}

class XbClientViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val _uiState = MutableStateFlow(XbClientUiState())
    val uiState = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<XbClientEvent>()
    val events = _events.asSharedFlow()
    private var pendingNodeSwitchConnect: Boolean? = null
    private var pendingOAuthCallback: Uri? = null
    private var nodeAutoRefreshStarted = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            loadStoredState()
            ensureNodeAutoRefresh()
            loadInstalledApps()
            refreshOAuthProviders()
            val state = _uiState.value
            checkGithubReleaseUpdate(state.githubProjectUrl)
            if (state.authData.isNotEmpty()) {
                refreshSubscriptionAndNodes(force = true)
                refreshUserInfo()
                refreshPlans()
                refreshInvites()
                refreshRewardConfig()
            }
        }
    }

    private fun ensureNodeAutoRefresh() {
        if (nodeAutoRefreshStarted) {
            return
        }
        nodeAutoRefreshStarted = true
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(NODE_AUTO_REFRESH_INTERVAL_MS)
                if (_uiState.value.authData.isNotEmpty()) {
                    refreshSubscriptionAndNodes(force = true)
                }
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
            PassScreen.PROFILE -> Unit
            PassScreen.PLANS -> Unit
            PassScreen.NODE_SELECT -> refreshSubscriptionAndNodes()
            PassScreen.APP_RULES -> Unit
            PassScreen.SETTINGS -> Unit
            PassScreen.NODES -> refreshSubscriptionAndNodes()
        }
    }

    fun refreshCurrentPage() {
        when (_uiState.value.screen) {
            PassScreen.PROFILE -> {
                refreshSubscriptionAndNodes(force = true, showLoading = true, showErrors = true)
                refreshUserInfo(showErrors = true)
                refreshInvites(force = true, showLoading = true, showErrors = true)
                refreshRewardConfig()
            }
            PassScreen.PLANS -> {
                refreshSubscriptionAndNodes(force = true, showLoading = true, showErrors = true)
                refreshPlans(force = true, showLoading = true, showErrors = true)
                refreshRewardConfig()
                refreshUserInfo(showErrors = true)
            }
            PassScreen.NODE_SELECT -> {
                refreshSubscriptionAndNodes(force = true, showLoading = true, showErrors = true)
                refreshUserInfo(showErrors = true)
            }
            PassScreen.SETTINGS, PassScreen.APP_RULES -> refreshUserInfo(showErrors = true)
            PassScreen.NODES -> {
                refreshSubscriptionAndNodes(force = true, showLoading = true, showErrors = true)
                refreshUserInfo(showErrors = true)
            }
        }
    }

    fun navigateBack() {
        val state = _uiState.value
        if (state.updateAvailable) {
            dismissUpdateDialog()
            return
        }
        if (state.oauthWebViewUrl.isNotEmpty()) {
            closeOAuthWebView()
            return
        }
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
            PassScreen.NODES, PassScreen.PLANS, PassScreen.PROFILE -> Unit
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
                refreshSubscriptionAndNodes(force = true)
                refreshUserInfo()
                refreshPlans(force = true)
                refreshInvites(force = true)
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
                refreshSubscriptionAndNodes(force = true)
                refreshUserInfo()
                refreshPlans(force = true)
                refreshInvites(force = true)
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

    fun refreshOAuthProviders(showErrors: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = XboardApi.request("guest_config", defaultApiUrl(), "", JSONObject())
                val body = requireSuccessfulBody("访客配置", result)
                val data = body.optJSONObject("data")
                val providers = data
                    ?.optJSONArray("oauth_providers")
                    ?.toOAuthProviderList()
                    ?: emptyList()
                _uiState.update { it.copy(oauthProviders = providers) }
                persistStoredState(_uiState.value)
            } catch (error: Exception) {
                if (showErrors) {
                    emitMessage("OAuth 配置加载失败：${error.message}")
                }
            }
        }
    }

    fun openOAuthPage(scene: String, driver: String, inviteCode: String = "") {
        val builder = Uri.parse("${defaultApiUrl().trimEnd('/')}/api/v1/passport/auth/oauth/$driver/redirect")
            .buildUpon()
            .appendQueryParameter("scene", scene)
            .appendQueryParameter("redirect", "dashboard")
            .appendQueryParameter("client", "app")
            .appendQueryParameter("app_scheme", BuildConfig.OAUTH_CALLBACK_SCHEME)
        if (scene == "register" && inviteCode.trim().isNotEmpty()) {
            builder.appendQueryParameter("invite_code", inviteCode.trim())
        }
        emitEvent(XbClientEvent.OpenExternalUrl(builder.build().toString()))
    }

    fun closeOAuthWebView() {
        _uiState.update { it.copy(oauthWebViewUrl = "") }
    }

    fun handleOAuthCallback(uri: Uri) {
        if (!_uiState.value.loaded) {
            pendingOAuthCallback = uri
            return
        }
        closeOAuthWebView()
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

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(updateAvailable = false) }
    }

    fun openUpdatePage(context: Context) {
        val url = _uiState.value.latestDownloadUrl.ifEmpty { _uiState.value.latestReleaseUrl }
        if (url.isNotEmpty()) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        dismissUpdateDialog()
    }

    fun logout() {
        val current = _uiState.value
        val next = XbClientUiState(
            loaded = true,
            adEnabled = current.adEnabled,
            paymentEnabled = current.paymentEnabled,
            planRewardAdEnabled = current.planRewardAdEnabled,
            planRewardedAdUnitId = current.planRewardedAdUnitId,
            pointsRewardAdEnabled = current.pointsRewardAdEnabled,
            pointsRewardedAdUnitId = current.pointsRewardedAdUnitId,
            appOpenAdEnabled = current.appOpenAdEnabled,
            appOpenAdUnitId = current.appOpenAdUnitId,
            configUpdatedAt = current.configUpdatedAt,
            githubProjectUrl = current.githubProjectUrl,
            oauthProviders = current.oauthProviders
        )
        _uiState.value = next
        persistState(next)
    }

    fun refreshSubscriptionAndNodes(force: Boolean = false, showLoading: Boolean = false, showErrors: Boolean = false) {
        val current = _uiState.value
        if (current.authData.isEmpty() || current.nodesLoading) {
            return
        }
        if (!force && current.anyTlsNodes.isNotEmpty() && System.currentTimeMillis() - current.nodesUpdatedAt < NODE_AUTO_REFRESH_INTERVAL_MS) {
            return
        }
        if (showLoading) {
            _uiState.update { it.copy(nodesLoading = true) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val subscribeResult = XboardApi.request("user_subscribe", defaultApiUrl(), current.authData, JSONObject())
                val subscribeBody = requireSuccessfulBody("订阅同步", subscribeResult)
                val data = subscribeBody.getJSONObject("data")
                val subscribeUrl = data.optString("subscribe_url", current.subscribeUrl)
                val blockReason = subscriptionBlockReason(data)
                val nodes = if (blockReason.isEmpty()) {
                    val xbclientNodesResult = XboardApi.request("xbclient_nodes", defaultApiUrl(), current.authData, JSONObject())
                    if (xbclientNodesResult.optBoolean("ok")) {
                        val nodesBody = requireSuccessfulBody("XBClient 节点同步", xbclientNodesResult)
                        nodesBody.getJSONObject("data").getJSONArray("nodes").toAnyTlsNodeList()
                    } else if (xbclientNodesResult.optInt("status") == 404) {
                        if (subscribeUrl.isEmpty()) {
                            throw IllegalStateException("XBClient 节点接口不可用，且订阅地址为空。")
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
                        if (showErrors) {
                            emitMessage("XBClient 节点接口不可用，已使用原订阅节点。")
                        }
                        nodesResult.getJSONArray("nodes").toAnyTlsNodeList()
                    } else {
                        throw IllegalStateException(resultError(xbclientNodesResult))
                    }
                } else {
                    emptyList()
                }
                val selectedIndex = _uiState.value.selectedNodeIndex.coerceIn(0, (nodes.size - 1).coerceAtLeast(0))
                val firstConnectableIndex = nodes.indexOfFirst { it.connectSupported }
                val next = _uiState.value.copy(
                    subscribeToken = data.optString("token", current.subscribeToken),
                    subscribeUrl = subscribeUrl,
                    subscriptionSummary = subscriptionSummary(data),
                    subscriptionBlockReason = blockReason,
                    nodesUpdatedAt = System.currentTimeMillis(),
                    anyTlsNodes = nodes,
                    selectedNodeIndex = if (nodes.getOrNull(selectedIndex)?.connectSupported == true || firstConnectableIndex < 0) selectedIndex else firstConnectableIndex,
                    nodeTestResults = emptyMap(),
                    nodesLoading = false
                )
                _uiState.value = next
                persistStoredState(next)
            } catch (error: Exception) {
                if (showLoading) {
                    _uiState.update { it.copy(nodesLoading = false) }
                }
                if (showErrors) {
                    emitMessage(if (_uiState.value.anyTlsNodes.isEmpty()) "节点同步失败：${error.message}" else "节点同步失败，继续使用本地缓存：${error.message}")
                }
            }
        }
    }

    fun refreshPlans(force: Boolean = false, showLoading: Boolean = false, showErrors: Boolean = false) {
        val authData = _uiState.value.authData
        if (authData.isEmpty() || _uiState.value.plansLoading) {
            return
        }
        if (!force && _uiState.value.plans.isNotEmpty()) {
            return
        }
        if (showLoading) {
            _uiState.update { it.copy(plansLoading = true) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = XboardApi.request("plan_fetch", defaultApiUrl(), authData, JSONObject())
                val body = requireSuccessfulBody("套餐加载", result)
                val plans = extractDataArray(body).toPlanItemList()
                val next = _uiState.value.copy(plans = plans, plansLoading = false)
                _uiState.value = next
                persistStoredState(next)
            } catch (error: Exception) {
                if (showLoading) {
                    _uiState.update { it.copy(plansLoading = false) }
                }
                if (showErrors) {
                    emitMessage("套餐加载失败：${error.message}")
                }
            }
        }
    }

    fun refreshInvites(force: Boolean = false, showLoading: Boolean = false, showErrors: Boolean = false) {
        val authData = _uiState.value.authData
        if (authData.isEmpty() || _uiState.value.invitesLoading) {
            return
        }
        if (!force && _uiState.value.invites.isNotEmpty()) {
            return
        }
        if (showLoading) {
            _uiState.update { it.copy(invitesLoading = true) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = XboardApi.request("invite_fetch", defaultApiUrl(), authData, JSONObject())
                val body = requireSuccessfulBody("邀请码加载", result)
                val invites = extractDataArray(body).toInviteItemList()
                val next = _uiState.value.copy(invites = invites, invitesLoading = false)
                _uiState.value = next
                persistStoredState(next)
            } catch (error: Exception) {
                if (showLoading) {
                    _uiState.update { it.copy(invitesLoading = false) }
                }
                if (showErrors) {
                    emitMessage("邀请码加载失败：${error.message}")
                }
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
                refreshInvites(force = true, showErrors = true)
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

    fun refreshAdRewardHistory(showLoading: Boolean = false) {
        val authData = _uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        if (showLoading) {
            _uiState.update { it.copy(adRewardLogsLoading = true) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = requireSuccessfulBody(
                    "广告奖励记录",
                    XboardApi.request("xbclient_reward_history", defaultApiUrl(), authData, JSONObject())
                )
                val logs = extractDataArray(body).toAdRewardLogItemList()
                _uiState.update {
                    it.copy(adRewardLogs = logs, adRewardLogsLoading = false)
                }
                persistStoredState(_uiState.value)
            } catch (_: Exception) {
                if (showLoading) {
                    _uiState.update { it.copy(adRewardLogsLoading = false) }
                }
            }
        }
    }

    fun refreshUserInfo(showErrors: Boolean = false) {
        val current = _uiState.value
        val authData = current.authData
        if (authData.isEmpty() || current.userLoading) {
            return
        }
        _uiState.update { it.copy(userLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = requireSuccessfulBody("用户信息", XboardApi.request("user_info", defaultApiUrl(), authData, JSONObject()))
                    .getJSONObject("data")
                val config = requireSuccessfulBody("用户配置", XboardApi.request("user_config", defaultApiUrl(), authData, JSONObject()))
                    .getJSONObject("data")
                _uiState.update {
                    it.copy(
                        userEmail = info.optString("email"),
                        balance = info.optInt("balance"),
                        commissionBalance = info.optInt("commission_balance"),
                        currencySymbol = config.optString("currency_symbol", it.currencySymbol).ifBlank { it.currencySymbol },
                        userLoading = false
                    )
                }
                persistStoredState(_uiState.value)
                refreshAdRewardHistory()
            } catch (error: Exception) {
                _uiState.update { it.copy(userLoading = false) }
                if (showErrors) {
                    emitMessage("用户信息加载失败：${error.message}")
                }
            }
        }
    }

    fun requestRewardAd(scene: String) {
        val authData = _uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = loadRewardConfig(authData, scene)
                if (config.first.isEmpty()) {
                    emitMessage("广告暂未开启。")
                    return@launch
                }
                emitEvent(XbClientEvent.ShowRewardAd(config.first, config.second, config.third))
            } catch (error: Exception) {
                emitMessage("广告暂未开启。")
            }
        }
    }

    fun onRewardAdEarned(customData: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = XboardApi.request(
                    "xbclient_reward_pending",
                    defaultApiUrl(),
                    _uiState.value.authData,
                    JSONObject().put("custom_data", customData)
                )
                requireSuccessfulBody("广告验证记录", result)
                refreshUserInfo()
            } catch (error: Exception) {
                emitMessage("广告验证记录提交失败：${error.message}")
            }
        }
    }

    fun openPlanPage(context: Context, planId: Int) {
        val authData = _uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = XboardApi.request(
                    "xbclient_plan_payment",
                    defaultApiUrl(),
                    authData,
                    JSONObject().put("plan_id", planId)
                )
                val body = requireSuccessfulBody("网页登录", result)
                val loginUrl = body.getString("data")
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)))
                }
            } catch (error: Exception) {
                emitMessage("套餐打开失败：${error.message}")
            }
        }
    }

    fun buyPlanWithBalance(planId: Int, period: String, amount: Int) {
        val authData = _uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        if (amount > _uiState.value.balance) {
            emitMessage("余额不足，当前只允许余额足额抵扣。")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val saveBody = requireSuccessfulBody(
                    "创建订单",
                    XboardApi.request(
                        "order_save",
                        defaultApiUrl(),
                        authData,
                        JSONObject()
                            .put("plan_id", planId)
                            .put("period", period)
                    )
                )
                val tradeNo = saveBody.getString("data")
                val checkoutBody = requireSuccessfulBody(
                    "余额支付",
                    XboardApi.request(
                        "order_checkout",
                        defaultApiUrl(),
                        authData,
                        JSONObject().put("trade_no", tradeNo)
                    )
                )
                if (checkoutBody.optInt("type") != -1) {
                    throw IllegalStateException("订单未完成余额抵扣。")
                }
                emitMessage("余额支付成功。")
                refreshSubscriptionAndNodes(force = true)
                refreshUserInfo()
                refreshPlans(force = true)
            } catch (error: Exception) {
                emitMessage("余额支付失败：${error.message}")
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
                refreshSubscriptionAndNodes(force = true)
                refreshUserInfo()
                refreshPlans(force = true)
                refreshInvites(force = true)
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

    private suspend fun loadRewardConfig(authData: String, scene: String = REWARD_SCENE_PLAN): Triple<String, String, String> {
        val result = try {
            XboardApi.request("admob_reward_config", defaultApiUrl(), authData, JSONObject())
        } catch (_: Exception) {
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
        val githubProjectUrl = data.optString("github_project_url")
        val configUpdatedAt = System.currentTimeMillis()
        if (!data.optBoolean("ad_enabled")) {
            _uiState.update {
                it.copy(
                    adEnabled = false,
                    paymentEnabled = data.optBoolean("payment_enabled", true),
                    appOpenAdEnabled = data.optBoolean("app_open_ad_enabled"),
                    appOpenAdUnitId = data.optString("app_open_ad_unit_id"),
                    githubProjectUrl = githubProjectUrl,
                    configUpdatedAt = configUpdatedAt,
                    planRewardAdEnabled = false,
                    planRewardedAdUnitId = "",
                    pointsRewardAdEnabled = false,
                    pointsRewardedAdUnitId = ""
                )
            }
            persistStoredState(_uiState.value)
            checkGithubReleaseUpdate(githubProjectUrl)
            return Triple("", "", "")
        }
        val planEnabled = data.optBoolean("plan_reward_ad_enabled")
        val planAdUnitId = data.optString("plan_rewarded_ad_unit_id")
        val planUserId = data.optString("plan_ssv_user_id")
        val planCustomData = data.optString("plan_ssv_custom_data")
        val pointsEnabled = data.optBoolean("points_reward_ad_enabled")
        val pointsAdUnitId = data.optString("points_rewarded_ad_unit_id")
        val pointsUserId = data.optString("points_ssv_user_id")
        val pointsCustomData = data.optString("points_ssv_custom_data")
        _uiState.update {
            it.copy(
                adEnabled = planEnabled || pointsEnabled,
                paymentEnabled = data.optBoolean("payment_enabled", true),
                appOpenAdEnabled = data.optBoolean("app_open_ad_enabled"),
                appOpenAdUnitId = data.optString("app_open_ad_unit_id"),
                githubProjectUrl = githubProjectUrl,
                configUpdatedAt = configUpdatedAt,
                planRewardAdEnabled = planEnabled,
                planRewardedAdUnitId = planAdUnitId,
                pointsRewardAdEnabled = pointsEnabled,
                pointsRewardedAdUnitId = pointsAdUnitId
            )
        }
        persistStoredState(_uiState.value)
        checkGithubReleaseUpdate(githubProjectUrl)
        return if (scene == REWARD_SCENE_POINTS) {
            Triple(pointsAdUnitId, pointsUserId, pointsCustomData)
        } else {
            Triple(planAdUnitId, planUserId, planCustomData)
        }
    }

    private fun clearRewardConfig() {
        _uiState.update {
            it.copy(
                adEnabled = false,
                paymentEnabled = true,
                appOpenAdEnabled = false,
                appOpenAdUnitId = "",
                planRewardAdEnabled = false,
                planRewardedAdUnitId = "",
                pointsRewardAdEnabled = false,
                pointsRewardedAdUnitId = ""
            )
        }
        persistState(_uiState.value)
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
        if (!nodes[index].connectSupported) {
            emitMessage("当前内核暂不支持 ${nodes[index].protocolLabel} 节点。")
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
            refreshSubscriptionAndNodes(force = true, showErrors = true)
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
        val node = _uiState.value.anyTlsNodes.getOrNull(index) ?: return
        if (!node.connectSupported) {
            emitMessage("当前内核暂不支持 ${node.protocolLabel} 节点。")
            return
        }
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
        if (!nodes[index].connectSupported) {
            _uiState.update { it.copy(nodeTestResults = it.nodeTestResults + (index to "当前内核暂不支持")) }
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
                if (!nodes[index].connectSupported) {
                    _uiState.update { it.copy(nodeTestResults = it.nodeTestResults + (index to "当前内核暂不支持")) }
                    continue
                }
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
            val selectedNode = state.anyTlsNodes[selectedIndex]
            if (!selectedNode.connectSupported) {
                throw IllegalStateException("当前内核暂不支持 ${selectedNode.protocolLabel} 节点。")
            }
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
                subscriptionBlockReason = prefs[Keys.SUBSCRIPTION_BLOCK_REASON].orEmpty(),
                nodesUpdatedAt = prefs[Keys.NODES_UPDATED_AT] ?: 0L,
                userEmail = prefs[Keys.USER_EMAIL].orEmpty(),
                balance = prefs[Keys.BALANCE] ?: 0,
                commissionBalance = prefs[Keys.COMMISSION_BALANCE] ?: 0,
                currencySymbol = (prefs[Keys.CURRENCY_SYMBOL] ?: "¥").ifBlank { "¥" },
                plans = cachedPlans(prefs[Keys.PLANS].orEmpty()),
                anyTlsNodes = cachedNodes(prefs[Keys.ANYTLS_NODES].orEmpty()),
                selectedNodeIndex = prefs[Keys.SELECTED_NODE_INDEX] ?: 0,
                invites = cachedInvites(prefs[Keys.INVITES].orEmpty()),
                excludedApps = prefs[Keys.EXCLUDED_APPS].orEmpty(),
                allowedApps = prefs[Keys.ALLOWED_APPS].orEmpty(),
                appRuleMode = prefs[Keys.APP_RULE_MODE] ?: MODE_EXCLUDE,
                nodeDns = prefs[Keys.NODE_DNS] ?: DEFAULT_NODE_DNS,
                overseasDns = prefs[Keys.OVERSEAS_DNS] ?: DEFAULT_OVERSEAS_DNS,
                directDns = prefs[Keys.DIRECT_DNS] ?: DEFAULT_DIRECT_DNS,
                nodeTestTarget = (prefs[Keys.NODE_TEST_TARGET] ?: DEFAULT_NODE_TEST_TARGET).let { if (it == "cp.cloudflare.com") DEFAULT_NODE_TEST_TARGET else it },
                vpnDnsMode = prefs[Keys.VPN_DNS_MODE] ?: DNS_MODE_OVER_TCP,
                vpnIpv6Enabled = prefs[Keys.VPN_IPV6_ENABLED] ?: true,
                vpnRequested = legacy.getBoolean("vpn_running", false),
                adEnabled = prefs[Keys.AD_ENABLED] ?: false,
                paymentEnabled = prefs[Keys.PAYMENT_ENABLED] ?: true,
                planRewardAdEnabled = prefs[Keys.PLAN_REWARD_AD_ENABLED] ?: false,
                planRewardedAdUnitId = prefs[Keys.PLAN_REWARDED_AD_UNIT_ID].orEmpty(),
                pointsRewardAdEnabled = prefs[Keys.POINTS_REWARD_AD_ENABLED] ?: false,
                pointsRewardedAdUnitId = prefs[Keys.POINTS_REWARDED_AD_UNIT_ID].orEmpty(),
                appOpenAdEnabled = prefs[Keys.APP_OPEN_AD_ENABLED] ?: false,
                appOpenAdUnitId = prefs[Keys.APP_OPEN_AD_UNIT_ID].orEmpty(),
                adRewardLogs = cachedAdRewardLogs(prefs[Keys.AD_REWARD_LOGS].orEmpty()),
                configUpdatedAt = prefs[Keys.CONFIG_UPDATED_AT] ?: 0L,
                githubProjectUrl = prefs[Keys.GITHUB_PROJECT_URL].orEmpty(),
                oauthProviders = cachedOAuthProviders(prefs[Keys.OAUTH_PROVIDERS].orEmpty())
            )
        } else {
            XbClientUiState(
                loaded = true,
                authData = legacy.getString("auth_data", "").orEmpty(),
                subscribeToken = legacy.getString("subscribe_token", "").orEmpty(),
                subscribeUrl = legacy.getString("subscribe_url", "").orEmpty(),
                subscriptionSummary = legacy.getString("subscription_summary", "").orEmpty(),
                subscriptionBlockReason = legacy.getString("subscription_block_reason", "").orEmpty(),
                nodesUpdatedAt = legacy.getLong("nodes_updated_at", 0L),
                userEmail = legacy.getString("user_email", "").orEmpty(),
                balance = legacy.getInt("balance", 0),
                commissionBalance = legacy.getInt("commission_balance", 0),
                currencySymbol = legacy.getString("currency_symbol", "¥").orEmpty().ifBlank { "¥" },
                plans = cachedPlans(legacy.getString("plans", "").orEmpty()),
                anyTlsNodes = cachedNodes(legacy.getString("anytls_nodes", "").orEmpty()),
                selectedNodeIndex = legacy.getInt("selected_node_index", 0),
                invites = cachedInvites(legacy.getString("invites", "").orEmpty()),
                excludedApps = legacy.getString("excluded_apps", "").orEmpty(),
                allowedApps = legacy.getString("allowed_apps", "").orEmpty(),
                appRuleMode = legacy.getString("app_rule_mode", if (legacy.getString("allowed_apps", "").orEmpty().isNotEmpty()) MODE_ALLOW else MODE_EXCLUDE).orEmpty(),
                nodeDns = legacy.getString("node_dns", DEFAULT_NODE_DNS).orEmpty(),
                overseasDns = legacy.getString("overseas_dns", DEFAULT_OVERSEAS_DNS).orEmpty(),
                directDns = legacy.getString("direct_dns", DEFAULT_DIRECT_DNS).orEmpty(),
                nodeTestTarget = legacy.getString("node_test_target", DEFAULT_NODE_TEST_TARGET).orEmpty().let { if (it == "cp.cloudflare.com") DEFAULT_NODE_TEST_TARGET else it },
                vpnDnsMode = legacy.getString("vpn_dns_mode", DNS_MODE_OVER_TCP).orEmpty(),
                vpnIpv6Enabled = legacy.getBoolean("vpn_ipv6_enabled", true),
                vpnRequested = legacy.getBoolean("vpn_running", false),
                adEnabled = legacy.getBoolean("ad_enabled", false),
                paymentEnabled = legacy.getBoolean("payment_enabled", true),
                planRewardAdEnabled = legacy.getBoolean("plan_reward_ad_enabled", false),
                planRewardedAdUnitId = legacy.getString("plan_rewarded_ad_unit_id", "").orEmpty(),
                pointsRewardAdEnabled = legacy.getBoolean("points_reward_ad_enabled", false),
                pointsRewardedAdUnitId = legacy.getString("points_rewarded_ad_unit_id", "").orEmpty(),
                appOpenAdEnabled = legacy.getBoolean("app_open_ad_enabled", false),
                appOpenAdUnitId = legacy.getString("app_open_ad_unit_id", "").orEmpty(),
                adRewardLogs = cachedAdRewardLogs(legacy.getString("ad_reward_logs", "").orEmpty()),
                configUpdatedAt = legacy.getLong("config_updated_at", 0L),
                githubProjectUrl = legacy.getString("github_project_url", "").orEmpty(),
                oauthProviders = cachedOAuthProviders(legacy.getString("oauth_providers", "").orEmpty())
            )
        }
        _uiState.value = state.copy(
            selectedNodeIndex = state.selectedNodeIndex.coerceIn(0, (state.anyTlsNodes.size - 1).coerceAtLeast(0))
        )
        if (!hasDataStoreState) {
            persistStoredState(_uiState.value)
        }
        pendingNodeSwitchConnect?.let { connectAfterSelect ->
            pendingNodeSwitchConnect = null
            requestNodeSwitchDialog(connectAfterSelect)
        }
        pendingOAuthCallback?.let { uri ->
            pendingOAuthCallback = null
            handleOAuthCallback(uri)
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
            prefs[Keys.SUBSCRIPTION_BLOCK_REASON] = state.subscriptionBlockReason
            prefs[Keys.NODES_UPDATED_AT] = state.nodesUpdatedAt
            prefs[Keys.USER_EMAIL] = state.userEmail
            prefs[Keys.BALANCE] = state.balance
            prefs[Keys.COMMISSION_BALANCE] = state.commissionBalance
            prefs[Keys.CURRENCY_SYMBOL] = state.currencySymbol
            prefs[Keys.PLANS] = plansJson(state.plans)
            prefs[Keys.ANYTLS_NODES] = nodesJson(state.anyTlsNodes)
            prefs[Keys.SELECTED_NODE_INDEX] = state.selectedNodeIndex
            prefs[Keys.INVITES] = invitesJson(state.invites)
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
            prefs[Keys.AD_ENABLED] = state.adEnabled
            prefs[Keys.PAYMENT_ENABLED] = state.paymentEnabled
            prefs[Keys.PLAN_REWARD_AD_ENABLED] = state.planRewardAdEnabled
            prefs[Keys.PLAN_REWARDED_AD_UNIT_ID] = state.planRewardedAdUnitId
            prefs[Keys.POINTS_REWARD_AD_ENABLED] = state.pointsRewardAdEnabled
            prefs[Keys.POINTS_REWARDED_AD_UNIT_ID] = state.pointsRewardedAdUnitId
            prefs[Keys.APP_OPEN_AD_ENABLED] = state.appOpenAdEnabled
            prefs[Keys.APP_OPEN_AD_UNIT_ID] = state.appOpenAdUnitId
            prefs[Keys.AD_REWARD_LOGS] = adRewardLogsJson(state.adRewardLogs)
            prefs[Keys.CONFIG_UPDATED_AT] = state.configUpdatedAt
            prefs[Keys.GITHUB_PROJECT_URL] = state.githubProjectUrl
            prefs[Keys.OAUTH_PROVIDERS] = oauthProvidersJson(state.oauthProviders)
        }
        app.getSharedPreferences(XBCLIENT_PREFS, Context.MODE_PRIVATE).edit()
            .putString("auth_data", state.authData)
            .putString("subscribe_token", state.subscribeToken)
            .putString("subscribe_url", state.subscribeUrl)
            .putString("subscription_summary", state.subscriptionSummary)
            .putString("subscription_block_reason", state.subscriptionBlockReason)
            .putLong("nodes_updated_at", state.nodesUpdatedAt)
            .putString("user_email", state.userEmail)
            .putInt("balance", state.balance)
            .putInt("commission_balance", state.commissionBalance)
            .putString("currency_symbol", state.currencySymbol)
            .putString("plans", plansJson(state.plans))
            .putString("anytls_nodes", nodesJson(state.anyTlsNodes))
            .putInt("selected_node_index", state.selectedNodeIndex)
            .putString("invites", invitesJson(state.invites))
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
            .putBoolean("ad_enabled", state.adEnabled)
            .putBoolean("payment_enabled", state.paymentEnabled)
            .putBoolean("plan_reward_ad_enabled", state.planRewardAdEnabled)
            .putString("plan_rewarded_ad_unit_id", state.planRewardedAdUnitId)
            .putBoolean("points_reward_ad_enabled", state.pointsRewardAdEnabled)
            .putString("points_rewarded_ad_unit_id", state.pointsRewardedAdUnitId)
            .putBoolean("app_open_ad_enabled", state.appOpenAdEnabled)
            .putString("app_open_ad_unit_id", state.appOpenAdUnitId)
            .putString("ad_reward_logs", adRewardLogsJson(state.adRewardLogs))
            .putLong("config_updated_at", state.configUpdatedAt)
            .putString("github_project_url", state.githubProjectUrl)
            .putString("oauth_providers", oauthProvidersJson(state.oauthProviders))
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

    private fun cachedPlans(value: String): List<PlanItem> =
        if (value.isEmpty()) emptyList() else JSONArray(value).toPlanItemList()

    private fun plansJson(plans: List<PlanItem>): String =
        JSONArray().also { array ->
            for (plan in plans) {
                val item = JSONObject()
                    .put("id", plan.id)
                    .put("name", plan.name)
                    .put("content", plan.content)
                    .put("transfer_enable", plan.transferEnable)
                for (price in plan.prices) {
                    item.put(price.field, price.amount)
                }
                array.put(item)
            }
        }.toString()

    private fun cachedInvites(value: String): List<InviteItem> =
        if (value.isEmpty()) emptyList() else JSONArray(value).toInviteItemList()

    private fun invitesJson(invites: List<InviteItem>): String =
        JSONArray().also { array ->
            for (invite in invites) {
                array.put(JSONObject().put("code", invite.code).put("status", invite.status))
            }
        }.toString()

    private fun cachedAdRewardLogs(value: String): List<AdRewardLogItem> =
        if (value.isEmpty()) emptyList() else JSONArray(value).toAdRewardLogItemList()

    private fun adRewardLogsJson(logs: List<AdRewardLogItem>): String =
        JSONArray().also { array ->
            for (log in logs) {
                array.put(
                    JSONObject()
                        .put("id", log.id)
                        .put("scene", log.scene)
                        .put("transaction_id", log.transactionId)
                        .put("status", log.status)
                        .put("error", log.error)
                        .put("gift_card_code", log.giftCardCode)
                        .put("gift_card_code_id", log.giftCardCodeId)
                        .put("gift_card_template_id", log.giftCardTemplateId)
                        .put("used_at", log.usedAt)
                        .put("created_at", log.createdAt)
                )
            }
        }.toString()

    private fun cachedOAuthProviders(value: String): List<OAuthProvider> =
        if (value.isEmpty()) emptyList() else JSONArray(value).toOAuthProviderList()

    private fun oauthProvidersJson(providers: List<OAuthProvider>): String =
        JSONArray().also { array ->
            for (provider in providers) {
                array.put(JSONObject().put("driver", provider.driver).put("label", provider.label))
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

    private fun checkGithubReleaseUpdate(projectUrl: String) {
        val value = projectUrl.trim()
        if (value.isEmpty()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val slug = githubRepoSlug(value)
                val connection = (URL("https://api.github.com/repos/$slug/releases/latest").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 30000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", BuildConfig.USER_AGENT)
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                val status = connection.responseCode
                val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                if (status !in 200..299) {
                    throw IllegalStateException("HTTP $status")
                }
                val release = JSONObject(text)
                val latestVersion = release.optString("tag_name").ifBlank { release.optString("name") }
                if (latestVersion.isEmpty()) {
                    throw IllegalStateException("GitHub Release 缺少版本号。")
                }
                val currentVersion = BuildConfig.VERSION_NAME.removeSuffix(".debug")
                if (normalizeVersion(latestVersion) == normalizeVersion(currentVersion)) {
                    return@launch
                }
                val releaseUrl = release.optString("html_url").ifBlank { "https://github.com/$slug/releases/latest" }
                val assets = release.optJSONArray("assets") ?: JSONArray()
                var firstApkUrl = ""
                var abiApkUrl = ""
                var universalApkUrl = ""
                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                    if (!name.endsWith(".apk", ignoreCase = true)) {
                        continue
                    }
                    if (firstApkUrl.isEmpty()) {
                        firstApkUrl = url
                    }
                    if (abiApkUrl.isEmpty() && Build.SUPPORTED_ABIS.any { name.contains(it, ignoreCase = true) }) {
                        abiApkUrl = url
                    }
                    if (name.contains("universal", ignoreCase = true)) {
                        universalApkUrl = url
                    }
                }
                val downloadUrl = universalApkUrl.ifEmpty { abiApkUrl.ifEmpty { firstApkUrl } }
                _uiState.update {
                    it.copy(
                        updateAvailable = true,
                        latestReleaseVersion = latestVersion,
                        latestReleaseUrl = releaseUrl,
                        latestDownloadUrl = downloadUrl
                    )
                }
            } catch (error: Exception) {
                emitMessage("更新检查失败：${error.message}")
            }
        }
    }

    private fun githubRepoSlug(projectUrl: String): String {
        val trimmed = projectUrl.trim().removeSuffix(".git")
        if (trimmed.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))) {
            return trimmed
        }
        val uri = Uri.parse(trimmed)
        if (uri.host != "github.com" || uri.pathSegments.size < 2) {
            throw IllegalStateException("GitHub 项目地址无效。")
        }
        return uri.pathSegments[0] + "/" + uri.pathSegments[1]
    }

    private fun normalizeVersion(value: String): String =
        value.trim().removePrefix("v").removePrefix("V").removeSuffix(".debug").substringBefore("-beta.")

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
        val SUBSCRIPTION_BLOCK_REASON = stringPreferencesKey("subscription_block_reason")
        val NODES_UPDATED_AT = longPreferencesKey("nodes_updated_at")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val BALANCE = intPreferencesKey("balance")
        val COMMISSION_BALANCE = intPreferencesKey("commission_balance")
        val CURRENCY_SYMBOL = stringPreferencesKey("currency_symbol")
        val PLANS = stringPreferencesKey("plans")
        val ANYTLS_NODES = stringPreferencesKey("anytls_nodes")
        val SELECTED_NODE_INDEX = intPreferencesKey("selected_node_index")
        val INVITES = stringPreferencesKey("invites")
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
        val AD_ENABLED = booleanPreferencesKey("ad_enabled")
        val PAYMENT_ENABLED = booleanPreferencesKey("payment_enabled")
        val PLAN_REWARD_AD_ENABLED = booleanPreferencesKey("plan_reward_ad_enabled")
        val PLAN_REWARDED_AD_UNIT_ID = stringPreferencesKey("plan_rewarded_ad_unit_id")
        val POINTS_REWARD_AD_ENABLED = booleanPreferencesKey("points_reward_ad_enabled")
        val POINTS_REWARDED_AD_UNIT_ID = stringPreferencesKey("points_rewarded_ad_unit_id")
        val APP_OPEN_AD_ENABLED = booleanPreferencesKey("app_open_ad_enabled")
        val APP_OPEN_AD_UNIT_ID = stringPreferencesKey("app_open_ad_unit_id")
        val AD_REWARD_LOGS = stringPreferencesKey("ad_reward_logs")
        val CONFIG_UPDATED_AT = longPreferencesKey("config_updated_at")
        val GITHUB_PROJECT_URL = stringPreferencesKey("github_project_url")
        val OAUTH_PROVIDERS = stringPreferencesKey("oauth_providers")
    }
}
