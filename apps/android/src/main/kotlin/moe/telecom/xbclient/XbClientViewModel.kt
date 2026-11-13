package moe.telecom.xbclient

import android.app.Application
import android.content.Context
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
import moe.telecom.neto.sdk.NetoControllerClient
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.passVpnDataStore by preferencesDataStore(name = XBCLIENT_PREFS)
private const val NODE_AUTO_REFRESH_INTERVAL_MS = 30L * 60L * 1000L

data class XbClientUiState(
    val loaded: Boolean = false,
    val nativeLoginPending: Boolean = false,
    val nativeExtensions: Set<String> = emptySet(),
    val authMode: AuthMode = AuthMode.LOGIN,
    val screen: PassScreen = PassScreen.CONNECT,
    val authData: String = "",
    val subscribeToken: String = "",
    val subscribeUrl: String = "",
    val subscriptionSummary: String = "",
    val subscriptionBlockReason: String = "",
    val subscriptionTrafficUsedBytes: Long = 0L,
    val subscriptionTrafficTotalBytes: Long = 0L,
    val nodesUpdatedAt: Long = 0L,
    val userEmail: String = "",
    val balance: Int = 0,
    val commissionBalance: Int = 0,
    val currencySymbol: String = "",
    val currencyUnit: String = "",
    val plans: List<PlanItem> = emptyList(),
    val notices: List<NoticeItem> = emptyList(),
    val invites: List<InviteItem> = emptyList(),
    val commissionLogs: List<CommissionLogItem> = emptyList(),
    val commissionTotal: Int = 0,
    val trafficLogs: List<TrafficLogItem> = emptyList(),
    val tickets: List<TicketItem> = emptyList(),
    val selectedTicket: TicketItem? = null,
    val ticketMessages: List<TicketMessageItem> = emptyList(),
    val giftCardHistory: List<GiftCardUsageItem> = emptyList(),
    val giftCardPreview: GiftCardPreviewItem? = null,
    val oauthBindings: List<OAuthBindingItem> = emptyList(),
    val inviteForce: Boolean = false,
    val inviteCommissionRate: Int = 0,
    val inviteCommissionBalance: Int = 0,
    val userLoading: Boolean = false,
    val nodesLoading: Boolean = false,
    val plansLoading: Boolean = false,
    val invitesLoading: Boolean = false,
    val commissionLogsLoading: Boolean = false,
    val trafficLogsLoading: Boolean = false,
    val ticketsLoading: Boolean = false,
    val ticketDetailLoading: Boolean = false,
    val giftCardChecking: Boolean = false,
    val giftCardRedeeming: Boolean = false,
    val giftCardHistoryLoading: Boolean = false,
    val oauthBindingsLoading: Boolean = false,
    val noticesLoading: Boolean = false,
    val paymentEnabled: Boolean = BuildConfig.APP_PAYMENT_ENABLED,
    val planRewardAdEnabled: Boolean = !PxBoardApi.enabled && BuildConfig.PLAN_REWARDED_AD_UNIT_ID.isNotEmpty(),
    val planRewardedAdUnitId: String = if (!PxBoardApi.enabled) BuildConfig.PLAN_REWARDED_AD_UNIT_ID else "",
    val pointsRewardAdEnabled: Boolean = !PxBoardApi.enabled && BuildConfig.POINTS_REWARDED_AD_UNIT_ID.isNotEmpty(),
    val pointsRewardedAdUnitId: String = if (!PxBoardApi.enabled) BuildConfig.POINTS_REWARDED_AD_UNIT_ID else "",
    val appOpenAdEnabled: Boolean = BuildConfig.APP_OPEN_AD_UNIT_ID.isNotEmpty(),
    val appOpenAdUnitId: String = BuildConfig.APP_OPEN_AD_UNIT_ID,
    val adRewardLogs: List<AdRewardLogItem> = emptyList(),
    val adRewardLogsLoading: Boolean = false,
    val updateAvailable: Boolean = false,
    val latestReleaseVersion: String = "",
    val latestReleaseUrl: String = "",
    val latestDownloadUrl: String = "",
    val appLanguage: String = "",
    val enableBlur: Boolean = true,
    val floatingBottomBar: Boolean = true,
    val languageOnboardingDone: Boolean = false,
    val vpnDisclosureDone: Boolean = false,
    val oauthProviders: List<OAuthProvider> = emptyList(),
    val registerEmailVerifyEnabled: Boolean = false,
    val registerCaptchaEnabled: Boolean = false,
    val registerCaptchaType: String = "",
    val oauthConfirmToken: String = "",
    val oauthConfirmProvider: String = "",
    val oauthConfirmEmail: String = "",
    val oauthWebViewUrl: String = "",
    val rewardCreditedDialog: Boolean = false,
    val rewardCreditedContent: String = "",
    val noticeDialog: Boolean = false
) {
    val isLoggedIn: Boolean
        get() = authData.isNotEmpty()

    val subscriptionBlocked: Boolean
        get() = subscriptionBlockReason.isNotEmpty()

    val isRefreshing: Boolean
        get() = userLoading || nodesLoading || plansLoading || invitesLoading || commissionLogsLoading || trafficLogsLoading || ticketsLoading || ticketDetailLoading || giftCardChecking || giftCardRedeeming || giftCardHistoryLoading || oauthBindingsLoading || noticesLoading
}

sealed interface XbClientEvent {
    data class Message(val text: String) : XbClientEvent
    data object RequestVpnPermission : XbClientEvent
    data class ShowRewardAd(val adUnitId: String, val userId: String, val customData: String) : XbClientEvent
    data class OpenExternalUrl(val url: String) : XbClientEvent
}

class XbClientViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val appTitle = app.getString(R.string.app_name)
    val netoClient = NetoControllerClient(application, appTitle, BuildConfig.USER_AGENT)
    private val _uiState = MutableStateFlow(XbClientUiState())
    val uiState = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<XbClientEvent>()
    val events = _events.asSharedFlow()
    private val nativePanel = PxBoardSession(appTitle, viewModelScope, _uiState, netoClient, ::persistStoredState, ::notifyPendingRewardIfCredited, ::emitMessage) { emitEvent(XbClientEvent.OpenExternalUrl(it)) }
    private val pxboardAuth = PxBoardAuthAdapter(appTitle, viewModelScope, _uiState, ::persistStoredState, ::emitMessage, { emitEvent(XbClientEvent.OpenExternalUrl(it)) }, { nativePanel.refresh(force = true) })
    internal val pxboardSession get() = nativePanel
    internal val pxboardAuthAdapter get() = pxboardAuth
    private val xboardAuth = XboardAuthAdapter(viewModelScope, _uiState, ::defaultApiUrl, ::persistStoredState, ::emitMessage, { emitEvent(XbClientEvent.OpenExternalUrl(it)) }) {
        refreshSubscriptionAndNodes(force = true)
        refreshUserInfo()
        refreshNotices(force = true)
        refreshPlans(force = true)
        refreshInvites(force = true)
        refreshRewardConfig()
    }
    fun loginPxBoard(providerId: String? = null) = pxboardAuth.login(providerId)
    fun cancelPxBoardLogin() = pxboardAuth.cancelLogin()
    fun refreshOAuthProviders(showErrors: Boolean = false) {
        if (PxBoardApi.enabled) pxboardAuth.refreshProviders() else xboardAuth.refreshOAuthProviders(showErrors)
    }
    fun openOAuthPage(scene: String, driver: String, inviteCode: String = "") {
        if (PxBoardApi.enabled) pxboardAuth.login(driver) else xboardAuth.openOAuthPage(scene, driver, inviteCode)
    }
    fun closeOAuthWebView() = xboardAuth.closeOAuthWebView()
    fun handleOAuthCallback(uri: Uri) {
        if (!PxBoardApi.enabled) xboardAuth.handleOAuthCallback(uri)
    }
    fun confirmOAuthRegister() = xboardAuth.confirmOAuthRegister()
    fun clearOAuthConfirm() = xboardAuth.clearOAuthConfirm()
    fun refreshOAuthBindings(force: Boolean = false, showLoading: Boolean = false, showErrors: Boolean = false) {
        if (PxBoardApi.enabled) nativePanel.loadScreenData(PassScreen.ACCOUNT_SECURITY) else xboardAuth.refreshOAuthBindings(force, showLoading, showErrors)
    }
    fun bindOAuth(driver: String) { if (PxBoardApi.enabled) nativePanel.bindOAuthProvider(driver) else xboardAuth.bindOAuth(driver) }
    fun unbindOAuth(driver: String) { if (PxBoardApi.enabled) nativePanel.portal("/user/account") else xboardAuth.unbindOAuth(driver) }
    fun openPxBoardPortal(path: String = "/user") = nativePanel.portal(path)

    private var nodeAutoRefreshStarted = false
    private var pendingRewardScene = ""
    private var pendingRewardStartedAt = 0L
    private val hasBuildRewardAds: Boolean
        get() = BuildConfig.PLAN_REWARDED_AD_UNIT_ID.isNotEmpty() ||
            BuildConfig.POINTS_REWARDED_AD_UNIT_ID.isNotEmpty()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            loadStoredState()
            ensureNodeAutoRefresh()
            refreshOAuthProviders()
            val updateProjectUrl = BuildConfig.GITHUB_PROJECT_URL.trim()
            if (updateProjectUrl.isNotBlank() && !isPlayInstallation()) {
                checkGithubReleaseUpdate(updateProjectUrl)
            }
            val state = _uiState.value
            if (state.authData.isNotEmpty()) {
                showDailyNoticeDialog(state.notices)
                refreshSubscriptionAndNodes(force = true)
                refreshUserInfo()
                refreshNotices()
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
        if (PxBoardApi.enabled) {
            _uiState.update { it.copy(screen = screen) }
            nativePanel.refresh()
            nativePanel.loadScreenData(screen)
            return
        }
        _uiState.update { it.copy(screen = screen) }
        when (screen) {
            PassScreen.PROFILE -> Unit
            PassScreen.GIFT_CARDS -> refreshGiftCardHistory(showLoading = true, showErrors = true)
            PassScreen.ACCOUNT_SECURITY -> {
                refreshOAuthProviders(showErrors = true)
                refreshOAuthBindings(showLoading = true, showErrors = true)
            }
            PassScreen.INVITE_DETAILS -> {
                refreshInvites(showErrors = true)
                refreshInviteDetails(showLoading = true, showErrors = true)
            }
            PassScreen.TRAFFIC_LOGS -> refreshTrafficLogs(showLoading = true, showErrors = true)
            PassScreen.TICKETS -> refreshTickets(showLoading = true, showErrors = true)
            PassScreen.TICKET_DETAIL, PassScreen.SUB_LINKS -> Unit
            PassScreen.PLANS -> Unit
            PassScreen.OPEN_SOURCE_LICENSES -> Unit
            PassScreen.SETTINGS -> Unit
            PassScreen.CONNECT, PassScreen.DASHBOARD, PassScreen.NODES -> {
                refreshSubscriptionAndNodes()
                refreshNotices()
            }
        }
    }

    fun refreshCurrentPage() {
        if (PxBoardApi.enabled) {
            nativePanel.refresh(force = true)
            nativePanel.loadScreenData(_uiState.value.screen)
            return
        }
        when (_uiState.value.screen) {
            PassScreen.PROFILE -> {
                refreshSubscriptionAndNodes(force = true, showLoading = true, showErrors = true)
                refreshUserInfo(showErrors = true)
                refreshInvites(force = true, showLoading = true, showErrors = true)
                refreshRewardConfig()
            }
            PassScreen.GIFT_CARDS -> refreshGiftCardHistory(force = true, showLoading = true, showErrors = true)
            PassScreen.ACCOUNT_SECURITY -> {
                refreshUserInfo(showErrors = true)
                refreshOAuthProviders(showErrors = true)
                refreshOAuthBindings(force = true, showLoading = true, showErrors = true)
            }
            PassScreen.INVITE_DETAILS -> {
                refreshInvites(force = true, showLoading = true, showErrors = true)
                refreshInviteDetails(force = true, showLoading = true, showErrors = true)
            }
            PassScreen.TRAFFIC_LOGS -> refreshTrafficLogs(force = true, showLoading = true, showErrors = true)
            PassScreen.TICKETS -> refreshTickets(force = true, showLoading = true, showErrors = true)
            PassScreen.TICKET_DETAIL -> _uiState.value.selectedTicket?.let {
                refreshTicketDetail(it.id, showLoading = true, showErrors = true)
            }
            PassScreen.PLANS -> {
                refreshSubscriptionAndNodes(force = true, showLoading = true, showErrors = true)
                refreshPlans(force = true, showLoading = true, showErrors = true)
                refreshRewardConfig()
                refreshUserInfo(showErrors = true)
            }
            PassScreen.SETTINGS -> refreshUserInfo(showErrors = true)
            PassScreen.OPEN_SOURCE_LICENSES -> Unit
            PassScreen.SUB_LINKS -> Unit
            PassScreen.CONNECT, PassScreen.DASHBOARD, PassScreen.NODES -> {
                refreshSubscriptionAndNodes(force = true, showLoading = true, showErrors = true)
                refreshUserInfo(showErrors = true)
                refreshNotices(force = true, showLoading = true, showErrors = true)
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
            PassScreen.TICKET_DETAIL -> openScreen(PassScreen.TICKETS)
            PassScreen.GIFT_CARDS, PassScreen.ACCOUNT_SECURITY, PassScreen.INVITE_DETAILS, PassScreen.TRAFFIC_LOGS, PassScreen.TICKETS, PassScreen.SUB_LINKS -> openScreen(PassScreen.PROFILE)
            PassScreen.OPEN_SOURCE_LICENSES -> openScreen(PassScreen.SETTINGS)
            PassScreen.SETTINGS -> openScreen(PassScreen.PROFILE)
            PassScreen.CONNECT, PassScreen.DASHBOARD, PassScreen.NODES, PassScreen.PLANS, PassScreen.PROFILE -> Unit
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
                    screen = PassScreen.CONNECT,
                    authData = data.getString("auth_data"),
                    subscribeToken = data.optString("token"),
                    subscribeUrl = data.optString("subscribe_url")
                )
                _uiState.value = next
                persistStoredState(next)
                emitMessage("登录成功。")
                refreshSubscriptionAndNodes(force = true)
                refreshUserInfo()
                refreshNotices(force = true)
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
                    screen = PassScreen.CONNECT,
                    authData = data.getString("auth_data"),
                    subscribeToken = data.optString("token"),
                    subscribeUrl = data.optString("subscribe_url")
                )
                _uiState.value = next
                persistStoredState(next)
                emitMessage("注册成功。")
                refreshSubscriptionAndNodes(force = true)
                refreshUserInfo()
                refreshNotices(force = true)
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

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(updateAvailable = false) }
    }

    fun showNotices() {
        _uiState.update { it.copy(noticeDialog = true) }
    }

    fun dismissNotices() {
        _uiState.update { it.copy(noticeDialog = false) }
    }

    fun dismissRewardCreditedDialog() {
        _uiState.update { it.copy(rewardCreditedDialog = false, rewardCreditedContent = "") }
    }

    fun openUpdatePage(context: Context) {
        if (isPlayInstallation()) {
            dismissUpdateDialog()
            BrowserOpener.open(context, "https://play.google.com/store/apps/details?id=${context.packageName}")
            return
        }
        val downloadUrl = _uiState.value.latestDownloadUrl.trim()
        val releaseUrl = _uiState.value.latestReleaseUrl.trim()
        dismissUpdateDialog()
        if (downloadUrl.endsWith(".apk", ignoreCase = true)) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    emitMessage("正在下载更新…")
                    val dir = File(context.cacheDir, "apk-updates").apply { mkdirs() }
                    val apkFile = File(dir, "update.apk")
                    ApkUpdateInstaller.downloadApk(downloadUrl, apkFile, BuildConfig.USER_AGENT)
                    emitMessage("正在打开安装程序…")
                    ApkUpdateInstaller.installApk(context, apkFile)
                } catch (error: Exception) {
                    emitMessage("应用内更新失败：${error.message}")
                }
            }
            return
        }
        throw IllegalStateException("更新下载地址不是 APK：$releaseUrl")
    }

    fun logout() {
        pxboardAuth.cancelLogin()
        val current = _uiState.value
        netoClient.stopVpn()
        if (PxBoardApi.enabled) {
            nativePanel.reset()
            if (current.authData.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) {
                try { PxBoardApi.rpc("core", "auth.logout", current.authData, JSONObject()) }
                catch (error: Exception) { emitMessage(error.message ?: error.toString()) }
            }
        }
        val next = XbClientUiState(
            loaded = true,
            appLanguage = current.appLanguage,
            enableBlur = current.enableBlur,
            floatingBottomBar = current.floatingBottomBar,
            languageOnboardingDone = current.languageOnboardingDone,
            vpnDisclosureDone = current.vpnDisclosureDone,
            oauthProviders = current.oauthProviders,
            registerEmailVerifyEnabled = current.registerEmailVerifyEnabled,
            registerCaptchaEnabled = current.registerCaptchaEnabled,
            registerCaptchaType = current.registerCaptchaType
        )
        _uiState.value = next
        persistState(next)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { netoClient.clearManagedProfile(name = appTitle) }
        }
    }

    fun refreshSubscriptionAndNodes(force: Boolean = false, showLoading: Boolean = false, showErrors: Boolean = false) {
        if (PxBoardApi.enabled) { nativePanel.refresh(force = force); return }
        val current = _uiState.value
        if (current.authData.isEmpty() || current.nodesLoading) {
            return
        }
        if (!force && netoClient.snapshot.value.nodes.isNotEmpty() && System.currentTimeMillis() - current.nodesUpdatedAt < NODE_AUTO_REFRESH_INTERVAL_MS) {
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
                val subscribeUrl = data.optString("subscribe_url")
                val blockReason = subscriptionBlockReason(data)
                val uploadBytes = numericValue(data.get("u")).toLong()
                val downloadBytes = numericValue(data.get("d")).toLong()
                val totalBytes = numericValue(data.get("transfer_enable")).toLong()
                val expiredAt = if (data.isNull("expired_at")) 0L else numericValue(data.get("expired_at")).toLong()
                if (blockReason.isNotEmpty()) {
                    netoClient.clearManagedProfile(name = appTitle, url = subscribeUrl)
                } else {
                    if (subscribeUrl.isNotEmpty()) {
                        netoClient.syncManagedSubscription(
                            id = "managed-default",
                            name = appTitle,
                            url = subscribeUrl,
                            flag = "aerion",
                            userAgent = BuildConfig.USER_AGENT,
                        )
                    }
                    val xbclientNodesResult = XboardApi.request("xbclient_nodes", defaultApiUrl(), current.authData, JSONObject())
                    if (xbclientNodesResult.getBoolean("ok")) {
                        val nodesBody = requireSuccessfulBody("XBClient 节点同步", xbclientNodesResult)
                        val pluginNodes = nodesBody.getJSONObject("data").getJSONArray("nodes")
                        if (pluginNodes.length() > 0 || subscribeUrl.isEmpty()) {
                            netoClient.upsertManagedProfile(
                                id = "managed-default",
                                name = appTitle,
                                url = subscribeUrl,
                                nodes = pluginNodes,
                                preserveRoutes = true,
                                usedUploadBytes = uploadBytes,
                                usedDownloadBytes = downloadBytes,
                                totalBytes = totalBytes,
                                expireUnix = expiredAt,
                            )
                        }
                    } else if (subscribeUrl.isEmpty()) {
                        throw IllegalStateException(resultError(xbclientNodesResult))
                    }
                }
                val next = _uiState.value.copy(
                    subscribeToken = data.optString("token"),
                    subscribeUrl = subscribeUrl,
                    subscriptionSummary = subscriptionSummary(data),
                    subscriptionBlockReason = blockReason,
                    subscriptionTrafficUsedBytes = uploadBytes + downloadBytes,
                    subscriptionTrafficTotalBytes = totalBytes,
                    nodesUpdatedAt = System.currentTimeMillis(),
                    nodesLoading = false
                )
                _uiState.value = next
                persistStoredState(next)
            } catch (error: Exception) {
                if (showLoading) {
                    _uiState.update { it.copy(nodesLoading = false) }
                }
                if (showErrors) {
                    emitMessage("节点同步失败：${error.message}")
                }
            }
        }
    }

    fun refreshPlans(force: Boolean = false, showLoading: Boolean = false, showErrors: Boolean = false) {
        if (PxBoardApi.enabled) return
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

    fun refreshNotices(force: Boolean = false, showLoading: Boolean = false, showErrors: Boolean = false) {
        if (PxBoardApi.enabled) return
        val authData = _uiState.value.authData
        if (authData.isEmpty() || _uiState.value.noticesLoading) {
            return
        }
        if (!force && _uiState.value.notices.isNotEmpty()) {
            return
        }
        if (showLoading) {
            _uiState.update { it.copy(noticesLoading = true) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = requireSuccessfulBody("公告加载", XboardApi.request("notices", defaultApiUrl(), authData, JSONObject()))
                val notices = extractDataArray(body).toNoticeItemList()
                val next = _uiState.value.copy(notices = notices, noticesLoading = false)
                _uiState.value = next
                persistStoredState(next)
                showDailyNoticeDialog(notices)
            } catch (error: Exception) {
                if (showLoading) {
                    _uiState.update { it.copy(noticesLoading = false) }
                }
                if (showErrors) {
                    emitMessage("公告加载失败：${error.message}")
                }
            }
        }
    }

    fun refreshInvites(force: Boolean = false, showLoading: Boolean = false, showErrors: Boolean = false) {
        if (PxBoardApi.enabled) return
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
                val data = body.getJSONObject("data")
                val stat = data.getJSONArray("stat")
                val invites = data.getJSONArray("codes").toInviteItemList()
                val next = _uiState.value.copy(
                    invites = invites,
                    invitesLoading = false,
                    inviteCommissionRate = stat.getInt(3),
                    inviteCommissionBalance = stat.getInt(4)
                )
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

    fun refreshInviteDetails(force: Boolean = false, showLoading: Boolean = false, showErrors: Boolean = false) {
        val authData = _uiState.value.authData
        if (authData.isEmpty() || _uiState.value.commissionLogsLoading) {
            return
        }
        if (!force && _uiState.value.commissionLogs.isNotEmpty()) {
            return
        }
        if (showLoading) {
            _uiState.update { it.copy(commissionLogsLoading = true) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = requireSuccessfulBody("邀请明细", XboardApi.request("invite_details", defaultApiUrl(), authData, JSONObject()))
                val logs = body.getJSONArray("data").toCommissionLogItemList()
                val next = _uiState.value.copy(
                    commissionLogs = logs,
                    commissionTotal = body.getInt("total"),
                    commissionLogsLoading = false
                )
                _uiState.value = next
                persistStoredState(next)
            } catch (error: Exception) {
                if (showLoading) {
                    _uiState.update { it.copy(commissionLogsLoading = false) }
                }
                if (showErrors) {
                    emitMessage("邀请明细加载失败：${error.message}")
                }
            }
        }
    }

    fun refreshTrafficLogs(force: Boolean = false, showLoading: Boolean = false, showErrors: Boolean = false) {
        val authData = _uiState.value.authData
        if (authData.isEmpty() || _uiState.value.trafficLogsLoading) {
            return
        }
        if (!force && _uiState.value.trafficLogs.isNotEmpty()) {
            return
        }
        if (showLoading) {
            _uiState.update { it.copy(trafficLogsLoading = true) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = requireSuccessfulBody("流量明细", XboardApi.request("traffic_logs", defaultApiUrl(), authData, JSONObject()))
                val next = _uiState.value.copy(
                    trafficLogs = extractDataArray(body).toTrafficLogItemList(),
                    trafficLogsLoading = false
                )
                _uiState.value = next
                persistStoredState(next)
            } catch (error: Exception) {
                if (showLoading) {
                    _uiState.update { it.copy(trafficLogsLoading = false) }
                }
                if (showErrors) {
                    emitMessage("流量明细加载失败：${error.message}")
                }
            }
        }
    }

    fun refreshGiftCardHistory(force: Boolean = false, showLoading: Boolean = false, showErrors: Boolean = false) {
        val authData = _uiState.value.authData
        if (authData.isEmpty() || _uiState.value.giftCardHistoryLoading) {
            return
        }
        if (!force && _uiState.value.giftCardHistory.isNotEmpty()) {
            return
        }
        if (showLoading) {
            _uiState.update { it.copy(giftCardHistoryLoading = true) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = requireSuccessfulBody(
                    "礼品卡记录",
                    XboardApi.request(
                        "gift_card_history",
                        defaultApiUrl(),
                        authData,
                        JSONObject()
                            .put("page", 1)
                            .put("per_page", 20)
                    )
                )
                _uiState.update {
                    it.copy(
                        giftCardHistory = body.getJSONArray("data").toGiftCardUsageItemList(),
                        giftCardHistoryLoading = false
                    )
                }
            } catch (error: Exception) {
                if (showLoading) {
                    _uiState.update { it.copy(giftCardHistoryLoading = false) }
                }
                if (showErrors) {
                    emitMessage("礼品卡记录加载失败：${error.message}")
                }
            }
        }
    }

    fun checkGiftCard(code: String) {
        val authData = _uiState.value.authData
        if (authData.isEmpty() || _uiState.value.giftCardChecking) {
            return
        }
        _uiState.update { it.copy(giftCardChecking = true, giftCardPreview = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = requireSuccessfulBody(
                    "礼品卡查询",
                    XboardApi.request(
                        "gift_card_check",
                        defaultApiUrl(),
                        authData,
                        JSONObject().put("code", code.trim())
                    )
                )
                _uiState.update {
                    it.copy(
                        giftCardPreview = body.getJSONObject("data").toGiftCardPreviewItem(),
                        giftCardChecking = false
                    )
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(giftCardChecking = false) }
                emitMessage("礼品卡查询失败：${error.message}")
            }
        }
    }

    fun redeemGiftCard(code: String) {
        val authData = _uiState.value.authData
        if (authData.isEmpty() || _uiState.value.giftCardRedeeming) {
            return
        }
        _uiState.update { it.copy(giftCardRedeeming = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = requireSuccessfulBody(
                    "礼品卡兑换",
                    XboardApi.request(
                        "gift_card_redeem",
                        defaultApiUrl(),
                        authData,
                        JSONObject().put("code", code.trim())
                    )
                )
                val data = body.getJSONObject("data")
                val rewardText = giftCardRewardText(data.getJSONObject("rewards"))
                _uiState.update { it.copy(giftCardRedeeming = false, giftCardPreview = null) }
                emitMessage("${data.getString("message")} $rewardText")
                refreshUserInfo()
                refreshSubscriptionAndNodes(force = true)
                refreshGiftCardHistory(force = true, showLoading = true, showErrors = true)
            } catch (error: Exception) {
                _uiState.update { it.copy(giftCardRedeeming = false) }
                emitMessage("礼品卡兑换失败：${error.message}")
            }
        }
    }

    fun changePassword(oldPassword: String, newPassword: String, confirmPassword: String) {
        val authData = _uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        if (newPassword != confirmPassword) {
            emitMessage("两次新密码输入不同。")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                requireSuccessfulBody(
                    "修改密码",
                    XboardApi.request(
                        "change_password",
                        defaultApiUrl(),
                        authData,
                        JSONObject()
                            .put("old_password", oldPassword)
                            .put("new_password", newPassword)
                    )
                )
                emitMessage("密码已修改。")
            } catch (error: Exception) {
                emitMessage("密码修改失败：${error.message}")
            }
        }
    }

    fun refreshTickets(force: Boolean = false, showLoading: Boolean = false, showErrors: Boolean = false) {
        val authData = _uiState.value.authData
        if (authData.isEmpty() || _uiState.value.ticketsLoading) {
            return
        }
        if (!force && _uiState.value.tickets.isNotEmpty()) {
            return
        }
        if (showLoading) {
            _uiState.update { it.copy(ticketsLoading = true) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = requireSuccessfulBody("工单列表", XboardApi.request("tickets", defaultApiUrl(), authData, JSONObject()))
                val next = _uiState.value.copy(
                    tickets = extractDataArray(body).toTicketItemList(),
                    ticketsLoading = false
                )
                _uiState.value = next
                persistStoredState(next)
            } catch (error: Exception) {
                if (showLoading) {
                    _uiState.update { it.copy(ticketsLoading = false) }
                }
                if (showErrors) {
                    emitMessage("工单列表加载失败：${error.message}")
                }
            }
        }
    }

    fun openTicket(ticketId: Int) {
        val currentTicket = _uiState.value.tickets.firstOrNull { it.id == ticketId }
        _uiState.update { it.copy(screen = PassScreen.TICKET_DETAIL, selectedTicket = currentTicket, ticketMessages = emptyList()) }
        refreshTicketDetail(ticketId, showLoading = true, showErrors = true)
    }

    fun refreshTicketDetail(ticketId: Int, showLoading: Boolean = false, showErrors: Boolean = false) {
        val authData = _uiState.value.authData
        if (authData.isEmpty() || _uiState.value.ticketDetailLoading) {
            return
        }
        if (showLoading) {
            _uiState.update { it.copy(ticketDetailLoading = true) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = requireSuccessfulBody(
                    "工单详情",
                    XboardApi.request("tickets", defaultApiUrl(), authData, JSONObject().put("id", ticketId))
                )
                val data = body.getJSONObject("data")
                val ticket = data.toTicketItem()
                val next = _uiState.value.copy(
                    selectedTicket = ticket,
                    ticketMessages = data.getJSONArray("message").toTicketMessageItemList(),
                    ticketDetailLoading = false
                )
                _uiState.value = next
                persistStoredState(next)
            } catch (error: Exception) {
                if (showLoading) {
                    _uiState.update { it.copy(ticketDetailLoading = false) }
                }
                if (showErrors) {
                    emitMessage("工单详情加载失败：${error.message}")
                }
            }
        }
    }

    fun createTicket(subject: String, level: Int, message: String) {
        val authData = _uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                requireSuccessfulBody(
                    "创建工单",
                    XboardApi.request(
                        "ticket_save",
                        defaultApiUrl(),
                        authData,
                        JSONObject()
                            .put("subject", subject.trim())
                            .put("level", level)
                            .put("message", message.trim())
                    )
                )
                emitMessage("工单已提交。")
                refreshTickets(force = true, showLoading = true, showErrors = true)
            } catch (error: Exception) {
                emitMessage("工单提交失败：${error.message}")
            }
        }
    }

    fun replyTicket(message: String) {
        val ticket = _uiState.value.selectedTicket ?: return
        val authData = _uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                requireSuccessfulBody(
                    "回复工单",
                    XboardApi.request(
                        "ticket_reply",
                        defaultApiUrl(),
                        authData,
                        JSONObject()
                            .put("id", ticket.id)
                            .put("message", message.trim())
                    )
                )
                emitMessage("工单回复已提交。")
                refreshTicketDetail(ticket.id, showLoading = true, showErrors = true)
                refreshTickets(force = true, showErrors = true)
            } catch (error: Exception) {
                emitMessage("工单回复失败：${error.message}")
            }
        }
    }

    fun closeTicket() {
        val ticket = _uiState.value.selectedTicket ?: return
        val authData = _uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                requireSuccessfulBody(
                    "关闭工单",
                    XboardApi.request("ticket_close", defaultApiUrl(), authData, JSONObject().put("id", ticket.id))
                )
                emitMessage("工单已关闭。")
                refreshTicketDetail(ticket.id, showLoading = true, showErrors = true)
                refreshTickets(force = true, showErrors = true)
            } catch (error: Exception) {
                emitMessage("关闭工单失败：${error.message}")
            }
        }
    }

    fun refreshRewardConfig() {
        if (!hasBuildRewardAds) return
        val authData = _uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        if (PxBoardApi.enabled) {
            nativePanel.refreshRewardConfig()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                loadRewardConfig(authData)
            } catch (error: Exception) {
                emitMessage("广告配置加载失败：${error.message}")
            }
        }
    }

    fun refreshAdRewardHistory(showLoading: Boolean = false) {
        if (!hasBuildRewardAds) return
        val authData = _uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        if (PxBoardApi.enabled) {
            nativePanel.refreshRewardHistory(showLoading)
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
                notifyPendingRewardIfCredited(logs)
                persistStoredState(_uiState.value)
            } catch (error: Exception) {
                if (showLoading) {
                    _uiState.update { it.copy(adRewardLogsLoading = false) }
                }
                emitMessage("广告奖励记录加载失败：${error.message}")
            }
        }
    }

    fun refreshUserInfo(showErrors: Boolean = false) {
        if (PxBoardApi.enabled) return
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
                        userEmail = info.getString("email"),
                        balance = info.getInt("balance"),
                        commissionBalance = info.getInt("commission_balance"),
                        inviteCommissionRate = if (info.has("commission_rate") && info.isNull("commission_rate")) {
                            0
                        } else {
                            info.getInt("commission_rate")
                        },
                        inviteCommissionBalance = info.getInt("commission_balance"),
                        currencySymbol = config.getString("currency_symbol"),
                        currencyUnit = config.getString("currency"),
                        userLoading = false
                    )
                }
                persistStoredState(_uiState.value)
                if (hasBuildRewardAds) refreshAdRewardHistory()
            } catch (error: Exception) {
                _uiState.update { it.copy(userLoading = false) }
                if (showErrors) {
                    emitMessage("用户信息加载失败：${error.message}")
                }
            }
        }
    }

    fun requestRewardAd(scene: String) {
        if (!hasBuildRewardAds) return
        val authData = _uiState.value.authData
        if (authData.isEmpty()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (PxBoardApi.enabled) {
                    val data = PxBoardApi.rpc(
                        "admob",
                        "reward.prepare",
                        authData,
                        JSONObject().put("scene", scene),
                    ) as JSONObject
                    val adUnitId: String
                    val userId: String
                    val customData: String
                    if (scene == REWARD_SCENE_POINTS) {
                        adUnitId = if (data.optBoolean("pointsRewardAdEnabled")) BuildConfig.POINTS_REWARDED_AD_UNIT_ID else ""
                        userId = data.optString("pointsSsvUserId")
                        customData = data.optString("pointsSsvCustomData")
                    } else {
                        adUnitId = if (data.optBoolean("planRewardAdEnabled")) BuildConfig.PLAN_REWARDED_AD_UNIT_ID else ""
                        userId = data.optString("planSsvUserId")
                        customData = data.optString("planSsvCustomData")
                    }
                    if (adUnitId.isEmpty()) throw IllegalStateException("广告场景未启用：$scene")
                    if (userId.isEmpty() || customData.isEmpty()) throw IllegalStateException("服务端激励广告 SSV 未就绪：$scene")
                    pendingRewardScene = scene
                    emitEvent(XbClientEvent.ShowRewardAd(adUnitId, userId, customData))
                    return@launch
                }
                val config = loadRewardConfig(authData, scene)
                if (config.first.isEmpty()) {
                    throw IllegalStateException("广告场景未启用：$scene")
                }
                if (config.second.isEmpty() || config.third.isEmpty()) {
                    throw IllegalStateException("服务端激励广告 SSV 未就绪：$scene")
                }
                pendingRewardScene = scene
                emitEvent(XbClientEvent.ShowRewardAd(config.first, config.second, config.third))
            } catch (error: Exception) {
                emitMessage("广告配置加载失败：${error.message}")
            }
        }
    }

    fun onRewardAdEarned(customData: String) {
        pendingRewardStartedAt = System.currentTimeMillis() / 1000L - 10L
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (PxBoardApi.enabled) {
                    val auth = _uiState.value.authData
                    val data = PxBoardApi.rpc(
                        "admob",
                        "reward.pending",
                        auth,
                        JSONObject().put("customData", customData),
                    ) as JSONObject
                    if (data.optBoolean("credited")) {
                        showRewardCreditedDialog(data.toPxBoardAdRewardLogItem().rewardContent)
                        pendingRewardScene = ""
                        pendingRewardStartedAt = 0L
                        nativePanel.refresh(force = true)
                        return@launch
                    }
                    nativePanel.refreshRewardHistory()
                    for (attempt in 0 until 5) {
                        delay(2000L)
                        if (pendingRewardScene.isEmpty() || _uiState.value.authData != auth) break
                        nativePanel.refreshRewardHistory()
                    }
                    nativePanel.refresh(force = true)
                    return@launch
                }
                val result = XboardApi.request(
                    "xbclient_reward_pending",
                    defaultApiUrl(),
                    _uiState.value.authData,
                    JSONObject().put("custom_data", customData)
                )
                val body = requireSuccessfulBody("广告验证记录", result)
                val data = body.getJSONObject("data")
                if (data.getBoolean("credited")) {
                    showRewardCreditedDialog(rewardContentText(data))
                    pendingRewardScene = ""
                    pendingRewardStartedAt = 0L
                }
                refreshUserInfo()
            } catch (error: Exception) {
                emitMessage("广告验证记录提交失败：${error.message}")
            }
        }
    }

    fun openPlanPage(context: Context, planId: Int) {
        if (!BuildConfig.APP_PAYMENT_ENABLED) {
            emitMessage("当前客户端仅支持账户余额与兑换码。")
            return
        }
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
                    JSONObject().put("redirect", "plan/$planId")
                )
                val body = requireSuccessfulBody("网页登录", result)
                val loginUrl = body.getString("data")
                val loginUri = Uri.parse(loginUrl)
                val apiUri = Uri.parse(defaultApiUrl())
                val loginPort = if (loginUri.port >= 0) loginUri.port else if (loginUri.scheme == "https") 443 else 80
                val apiPort = if (apiUri.port >= 0) apiUri.port else if (apiUri.scheme == "https") 443 else 80
                if (loginUri.scheme != apiUri.scheme || loginUri.host != apiUri.host || loginPort != apiPort) {
                    throw IllegalStateException("网页登录地址必须来自当前 Xboard 站点。")
                }
                withContext(Dispatchers.Main) {
                    BrowserOpener.open(context, loginUrl)
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
            emitMessage("账户金额不足，当前只允许账户金额足额抵扣。")
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
                val checkoutBody = try {
                    requireSuccessfulBody(
                        "账户金额支付",
                        XboardApi.request(
                            "order_checkout",
                            defaultApiUrl(),
                            authData,
                            JSONObject().put("trade_no", tradeNo)
                        )
                    ).also { body ->
                        if (body.getInt("type") != -1) {
                            throw IllegalStateException("订单未完成账户金额抵扣。")
                        }
                    }
                } catch (checkoutErr: Exception) {
                    runCatching {
                        XboardApi.request("order_cancel", defaultApiUrl(), authData, JSONObject().put("trade_no", tradeNo))
                    }
                    throw checkoutErr
                }
                emitMessage("账户金额支付成功。")
                refreshSubscriptionAndNodes(force = true)
                refreshUserInfo()
                refreshPlans(force = true)
            } catch (error: Exception) {
                emitMessage("账户金额支付失败：${error.message}")
            }
        }
    }

    private suspend fun loadRewardConfig(authData: String, scene: String = REWARD_SCENE_PLAN): Triple<String, String, String> {
        val result = XboardApi.request("admob_reward_config", defaultApiUrl(), authData, JSONObject())
        if (!result.getBoolean("ok")) {
            throw IllegalStateException(resultError(result))
        }
        val body = result.getJSONObject("body")
        body.requireNotXboardFail()
        val data = body.getJSONObject("data")
        val planAdUnitId = BuildConfig.PLAN_REWARDED_AD_UNIT_ID
        val planEnabled = planAdUnitId.isNotEmpty()
        val planUserId = if (planEnabled) data.optString("plan_ssv_user_id") else ""
        val planCustomData = if (planEnabled) data.optString("plan_ssv_custom_data") else ""
        val pointsAdUnitId = BuildConfig.POINTS_REWARDED_AD_UNIT_ID
        val pointsEnabled = pointsAdUnitId.isNotEmpty()
        val pointsUserId = if (pointsEnabled) data.optString("points_ssv_user_id") else ""
        val pointsCustomData = if (pointsEnabled) data.optString("points_ssv_custom_data") else ""
        _uiState.update {
            it.copy(
                paymentEnabled = BuildConfig.APP_PAYMENT_ENABLED,
                appOpenAdEnabled = BuildConfig.APP_OPEN_AD_UNIT_ID.isNotEmpty(),
                appOpenAdUnitId = BuildConfig.APP_OPEN_AD_UNIT_ID,
                planRewardAdEnabled = planEnabled,
                planRewardedAdUnitId = planAdUnitId,
                pointsRewardAdEnabled = pointsEnabled,
                pointsRewardedAdUnitId = pointsAdUnitId
            )
        }
        persistStoredState(_uiState.value)
        val updateProjectUrl = BuildConfig.GITHUB_PROJECT_URL.trim()
        if (updateProjectUrl.isNotBlank() && !isPlayInstallation()) {
            checkGithubReleaseUpdate(updateProjectUrl)
        }
        return if (scene == REWARD_SCENE_POINTS) {
            Triple(pointsAdUnitId, pointsUserId, pointsCustomData)
        } else {
            Triple(planAdUnitId, planUserId, planCustomData)
        }
    }

    fun setAppLanguage(language: String) {
        app.getSharedPreferences(XBCLIENT_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("app_language", language)
            .commit()
        updateAndPersist { it.copy(appLanguage = language) }
    }

    fun finishLanguageOnboarding(language: String) {
        updateAndPersist { it.copy(appLanguage = language, languageOnboardingDone = true) }
    }

    fun acceptVpnDisclosure() {
        updateAndPersist { it.copy(vpnDisclosureDone = true) }
    }

    fun resetOnboarding() {
        updateAndPersist { it.copy(languageOnboardingDone = false, vpnDisclosureDone = false) }
    }

    fun setThemeMode(themeMode: String) {
        netoClient.updateSettings(JSONObject().put("theme_mode", themeMode.ifBlank { "system" }))
    }

    fun setEnableBlur(enabled: Boolean) {
        app.getSharedPreferences(XBCLIENT_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enable_blur", enabled)
            .commit()
        updateAndPersist { it.copy(enableBlur = enabled) }
    }

    fun setFloatingBottomBar(enabled: Boolean) {
        app.getSharedPreferences(XBCLIENT_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("floating_bottom_bar", enabled)
            .commit()
        updateAndPersist { it.copy(floatingBottomBar = enabled) }
    }

    fun syncAppearanceSettings() {
        val prefs = app.getSharedPreferences(XBCLIENT_PREFS, Context.MODE_PRIVATE)
        val language = prefs.getString("app_language", _uiState.value.appLanguage)
            ?: throw IllegalStateException("app_language preference is null")
        val enableBlur = prefs.getBoolean("enable_blur", true)
        val floatingBottomBar = prefs.getBoolean("floating_bottom_bar", true)
        _uiState.update { current ->
            if (!current.loaded) {
                current
            } else {
                current.copy(
                    appLanguage = language,
                    enableBlur = enableBlur,
                    floatingBottomBar = floatingBottomBar
                )
            }
        }
    }

    fun toggleVpn() {
        if (netoClient.snapshot.value.connection.connected) {
            netoClient.stopVpn()
            return
        }
        if (netoClient.prepareVpnPermission() != null) {
            emitEvent(XbClientEvent.RequestVpnPermission)
        } else {
            startNetoVpn()
        }
    }

    fun startNetoVpn() {
        netoClient.startVpn()
    }

    private suspend fun loadStoredState() {
        val prefs = app.passVpnDataStore.data.first()
        val sessionMatches = prefs[Keys.PANEL_ORIGIN] == defaultApiUrl().trimEnd('/') + "|" + BuildConfig.PANEL_TYPE
        val state = XbClientUiState(
            loaded = true,
            authData = if (sessionMatches) prefs[Keys.AUTH_DATA].orEmpty() else "",
            subscribeToken = prefs[Keys.SUBSCRIBE_TOKEN].orEmpty(),
            subscribeUrl = if (sessionMatches) prefs[Keys.SUBSCRIBE_URL].orEmpty() else "",
            subscriptionSummary = prefs[Keys.SUBSCRIPTION_SUMMARY].orEmpty(),
            subscriptionBlockReason = prefs[Keys.SUBSCRIPTION_BLOCK_REASON].orEmpty(),
            subscriptionTrafficUsedBytes = prefs[Keys.SUBSCRIPTION_TRAFFIC_USED_BYTES] ?: 0L,
            subscriptionTrafficTotalBytes = prefs[Keys.SUBSCRIPTION_TRAFFIC_TOTAL_BYTES] ?: 0L,
            nodesUpdatedAt = prefs[Keys.NODES_UPDATED_AT] ?: 0L,
            userEmail = prefs[Keys.USER_EMAIL].orEmpty(),
            balance = prefs[Keys.BALANCE] ?: 0,
            commissionBalance = prefs[Keys.COMMISSION_BALANCE] ?: 0,
            currencySymbol = prefs[Keys.CURRENCY_SYMBOL].orEmpty(),
            currencyUnit = prefs[Keys.CURRENCY_UNIT].orEmpty(),
            plans = emptyList(),
            notices = emptyList(),
            invites = emptyList(),
            inviteForce = prefs[Keys.INVITE_FORCE] ?: false,
            inviteCommissionRate = prefs[Keys.INVITE_COMMISSION_RATE] ?: 0,
            inviteCommissionBalance = prefs[Keys.INVITE_COMMISSION_BALANCE] ?: 0,
            paymentEnabled = BuildConfig.APP_PAYMENT_ENABLED,
            planRewardAdEnabled = !PxBoardApi.enabled && BuildConfig.PLAN_REWARDED_AD_UNIT_ID.isNotEmpty(),
            planRewardedAdUnitId = if (!PxBoardApi.enabled) BuildConfig.PLAN_REWARDED_AD_UNIT_ID else "",
            pointsRewardAdEnabled = !PxBoardApi.enabled && BuildConfig.POINTS_REWARDED_AD_UNIT_ID.isNotEmpty(),
            pointsRewardedAdUnitId = if (!PxBoardApi.enabled) BuildConfig.POINTS_REWARDED_AD_UNIT_ID else "",
            appOpenAdEnabled = BuildConfig.APP_OPEN_AD_UNIT_ID.isNotEmpty(),
            appOpenAdUnitId = BuildConfig.APP_OPEN_AD_UNIT_ID,
            adRewardLogs = emptyList(),
            appLanguage = prefs[Keys.APP_LANGUAGE].orEmpty(),
            enableBlur = prefs[Keys.ENABLE_BLUR] ?: true,
            floatingBottomBar = prefs[Keys.FLOATING_BOTTOM_BAR] ?: true,
            languageOnboardingDone = prefs[Keys.LANGUAGE_ONBOARDING_DONE] ?: false,
            vpnDisclosureDone = prefs[Keys.VPN_DISCLOSURE_DONE] ?: false,
            oauthProviders = emptyList(),
            registerEmailVerifyEnabled = prefs[Keys.REGISTER_EMAIL_VERIFY_ENABLED] ?: false,
            registerCaptchaEnabled = prefs[Keys.REGISTER_CAPTCHA_ENABLED] ?: false,
            registerCaptchaType = prefs[Keys.REGISTER_CAPTCHA_TYPE].orEmpty()
        )
        _uiState.value = state
        if (!sessionMatches || !state.isLoggedIn || state.subscriptionBlocked) {
            runCatching { netoClient.clearManagedProfile(name = appTitle, url = state.subscribeUrl) }
        }
        if (!PxBoardApi.enabled) xboardAuth.resumeCallback()
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
            prefs[Keys.PANEL_ORIGIN] = defaultApiUrl().trimEnd('/') + "|" + BuildConfig.PANEL_TYPE
            prefs[Keys.AUTH_DATA] = state.authData
            prefs[Keys.SUBSCRIBE_TOKEN] = state.subscribeToken
            prefs[Keys.SUBSCRIBE_URL] = state.subscribeUrl
            prefs[Keys.SUBSCRIPTION_SUMMARY] = state.subscriptionSummary
            prefs[Keys.SUBSCRIPTION_BLOCK_REASON] = state.subscriptionBlockReason
            prefs[Keys.SUBSCRIPTION_TRAFFIC_USED_BYTES] = state.subscriptionTrafficUsedBytes
            prefs[Keys.SUBSCRIPTION_TRAFFIC_TOTAL_BYTES] = state.subscriptionTrafficTotalBytes
            prefs[Keys.NODES_UPDATED_AT] = state.nodesUpdatedAt
            prefs[Keys.USER_EMAIL] = state.userEmail
            prefs[Keys.BALANCE] = state.balance
            prefs[Keys.COMMISSION_BALANCE] = state.commissionBalance
            prefs[Keys.CURRENCY_SYMBOL] = state.currencySymbol
            prefs[Keys.CURRENCY_UNIT] = state.currencyUnit
            prefs[Keys.INVITE_FORCE] = state.inviteForce
            prefs[Keys.INVITE_COMMISSION_RATE] = state.inviteCommissionRate
            prefs[Keys.INVITE_COMMISSION_BALANCE] = state.inviteCommissionBalance
            prefs[Keys.PAYMENT_ENABLED] = state.paymentEnabled
            prefs[Keys.PLAN_REWARD_AD_ENABLED] = state.planRewardAdEnabled
            prefs[Keys.PLAN_REWARDED_AD_UNIT_ID] = state.planRewardedAdUnitId
            prefs[Keys.POINTS_REWARD_AD_ENABLED] = state.pointsRewardAdEnabled
            prefs[Keys.POINTS_REWARDED_AD_UNIT_ID] = state.pointsRewardedAdUnitId
            prefs[Keys.APP_OPEN_AD_ENABLED] = state.appOpenAdEnabled
            prefs[Keys.APP_OPEN_AD_UNIT_ID] = state.appOpenAdUnitId
            prefs[Keys.APP_LANGUAGE] = state.appLanguage
            prefs[Keys.ENABLE_BLUR] = state.enableBlur
            prefs[Keys.FLOATING_BOTTOM_BAR] = state.floatingBottomBar
            prefs[Keys.LANGUAGE_ONBOARDING_DONE] = state.languageOnboardingDone
            prefs[Keys.VPN_DISCLOSURE_DONE] = state.vpnDisclosureDone
            prefs[Keys.REGISTER_EMAIL_VERIFY_ENABLED] = state.registerEmailVerifyEnabled
            prefs[Keys.REGISTER_CAPTCHA_ENABLED] = state.registerCaptchaEnabled
            prefs[Keys.REGISTER_CAPTCHA_TYPE] = state.registerCaptchaType
        }
        app.getSharedPreferences(XBCLIENT_PREFS, Context.MODE_PRIVATE).edit()
            .putString("app_language", state.appLanguage)
            .putBoolean("enable_blur", state.enableBlur)
            .putBoolean("floating_bottom_bar", state.floatingBottomBar)
            .apply()
    }

    private fun requireSuccessfulBody(title: String, result: JSONObject): JSONObject {
        if (!result.getBoolean("ok")) {
            throw IllegalStateException(resultError(result))
        }
        val body = result.getJSONObject("body")
        body.requireNotXboardFail()
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
            when (val type = _uiState.value.registerCaptchaType.trim()) {
                "turnstile" -> params.put("turnstile_token", token)
                "recaptcha-v3" -> params.put("recaptcha_v3_token", token)
                "recaptcha" -> params.put("recaptcha_data", token)
                else -> throw IllegalStateException("不支持的验证码类型：$type")
            }
        }
    }

    private fun isPlayInstallation() =
        !BuildConfig.SELF_UPDATE_ENABLED ||
            app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName == "com.android.vending"

    private fun checkGithubReleaseUpdate(projectUrl: String) {
        if (isPlayInstallation()) {
            emitEvent(XbClientEvent.OpenExternalUrl("https://play.google.com/store/apps/details?id=${app.packageName}"))
            return
        }
        val value = projectUrl.trim()
        if (value.isEmpty()) {
            throw IllegalStateException("GitHub 项目地址为空。")
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
                val latestVersion = release.getString("tag_name")
                if (latestVersion.isEmpty()) {
                    throw IllegalStateException("GitHub Release 缺少版本号。")
                }
                val currentVersion = BuildConfig.VERSION_NAME.removeSuffix(".debug")
                if (normalizeVersion(latestVersion) == normalizeVersion(currentVersion)) {
                    return@launch
                }
                val releaseUrl = release.getString("html_url")
                val assets = release.getJSONArray("assets")
                var abiApkUrl = ""
                var universalApkUrl = ""
                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    val name = asset.getString("name")
                    val url = asset.getString("browser_download_url")
                    if (!name.endsWith(".apk", ignoreCase = true)) {
                        continue
                    }
                    if (abiApkUrl.isEmpty() && Build.SUPPORTED_ABIS.any { name.contains(it, ignoreCase = true) }) {
                        abiApkUrl = url
                    }
                    if (name.contains("universal", ignoreCase = true)) {
                        universalApkUrl = url
                    }
                }
                val downloadUrl = universalApkUrl.ifEmpty { abiApkUrl }
                if (downloadUrl.isEmpty()) {
                    throw IllegalStateException("GitHub Release 缺少 universal 或当前 ABI 的 APK 资源。")
                }
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

    private fun showRewardCreditedDialog(content: String) {
        _uiState.update { it.copy(rewardCreditedDialog = true, rewardCreditedContent = content) }
    }

    private fun notifyPendingRewardIfCredited(logs: List<AdRewardLogItem>) {
        if (pendingRewardScene.isEmpty() || pendingRewardStartedAt <= 0L) {
            return
        }
        val log = logs.firstOrNull {
            it.scene == pendingRewardScene && it.status == "credited" && it.createdAt >= pendingRewardStartedAt
        } ?: return
        pendingRewardScene = ""
        pendingRewardStartedAt = 0L
        showRewardCreditedDialog(log.rewardContent)
    }

    private fun showDailyNoticeDialog(notices: List<NoticeItem>) {
        if (notices.isEmpty()) {
            return
        }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val prefs = app.getSharedPreferences(XBCLIENT_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("last_notice_dialog_day", "") == today) {
            return
        }
        prefs.edit().putString("last_notice_dialog_day", today).apply()
        _uiState.update { it.copy(noticeDialog = true) }
    }

    private fun emitEvent(event: XbClientEvent) {
        viewModelScope.launch {
            _events.emit(event)
        }
    }

    private object Keys {
        val PANEL_ORIGIN = stringPreferencesKey("panel_origin")
        val AUTH_DATA = stringPreferencesKey("auth_data")
        val SUBSCRIBE_TOKEN = stringPreferencesKey("subscribe_token")
        val SUBSCRIBE_URL = stringPreferencesKey("subscribe_url")
        val SUBSCRIPTION_SUMMARY = stringPreferencesKey("subscription_summary")
        val SUBSCRIPTION_BLOCK_REASON = stringPreferencesKey("subscription_block_reason")
        val SUBSCRIPTION_TRAFFIC_USED_BYTES = longPreferencesKey("subscription_traffic_used_bytes")
        val SUBSCRIPTION_TRAFFIC_TOTAL_BYTES = longPreferencesKey("subscription_traffic_total_bytes")
        val NODES_UPDATED_AT = longPreferencesKey("nodes_updated_at")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val BALANCE = intPreferencesKey("balance")
        val COMMISSION_BALANCE = intPreferencesKey("commission_balance")
        val CURRENCY_SYMBOL = stringPreferencesKey("currency_symbol")
        val CURRENCY_UNIT = stringPreferencesKey("currency_unit")
        val INVITE_FORCE = booleanPreferencesKey("invite_force")
        val INVITE_COMMISSION_RATE = intPreferencesKey("invite_commission_rate")
        val INVITE_COMMISSION_BALANCE = intPreferencesKey("invite_commission_balance")
        val PAYMENT_ENABLED = booleanPreferencesKey("payment_enabled")
        val PLAN_REWARD_AD_ENABLED = booleanPreferencesKey("plan_reward_ad_enabled")
        val PLAN_REWARDED_AD_UNIT_ID = stringPreferencesKey("plan_rewarded_ad_unit_id")
        val POINTS_REWARD_AD_ENABLED = booleanPreferencesKey("points_reward_ad_enabled")
        val POINTS_REWARDED_AD_UNIT_ID = stringPreferencesKey("points_rewarded_ad_unit_id")
        val APP_OPEN_AD_ENABLED = booleanPreferencesKey("app_open_ad_enabled")
        val APP_OPEN_AD_UNIT_ID = stringPreferencesKey("app_open_ad_unit_id")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val ENABLE_BLUR = booleanPreferencesKey("enable_blur")
        val FLOATING_BOTTOM_BAR = booleanPreferencesKey("floating_bottom_bar")
        val LANGUAGE_ONBOARDING_DONE = booleanPreferencesKey("language_onboarding_done")
        val VPN_DISCLOSURE_DONE = booleanPreferencesKey("vpn_disclosure_done")
        val REGISTER_EMAIL_VERIFY_ENABLED = booleanPreferencesKey("register_email_verify_enabled")
        val REGISTER_CAPTCHA_ENABLED = booleanPreferencesKey("register_captcha_enabled")
        val REGISTER_CAPTCHA_TYPE = stringPreferencesKey("register_captcha_type")
    }
}
