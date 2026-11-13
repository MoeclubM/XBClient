package moe.telecom.xbclient

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
import moe.telecom.neto.sdk.NetoControllerClient
import moe.telecom.neto.sdk.formatNetoBytes
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class PxBoardPeriod(
    val id: String,
    val label: String,
    val price: Int,
)

internal data class PxBoardPlan(
    val id: String,
    val name: String,
    val description: String,
    val quotaGiB: Double,
    val unlimited: Boolean,
    val periods: List<PxBoardPeriod>,
)

internal data class PxBoardPaymentChannel(
    val id: String,
    val name: String,
)

internal data class PxBoardQuote(
    val amount: Int,
    val discount: Int,
    val surplusAmount: Int,
    val surplusCredit: Int,
)

internal data class PxBoardOrder(
    val id: String,
    val number: String,
    val planName: String,
    val kind: String,
    val amount: Int,
    val discount: Int,
    val surplusAmount: Int,
    val surplusCredit: Int,
    val status: String,
    val createdAt: String,
)

internal data class PxBoardLedgerEntry(
    val id: String,
    val amount: Int,
    val reason: String,
    val createdAt: String,
)

internal data class PxBoardSubLink(
    val id: String,
    val name: String,
    val shareUrl: String,
    val boundClient: String,
)

internal data class PxBoardClaim(
    val id: String,
    val name: String,
    val summary: String,
    val status: String,
    val timestamp: String,
)

internal data class PxBoardTicketMsg(
    val id: String,
    val author: String,
    val content: String,
    val createdAt: String,
)

internal data class PxBoardTicket(
    val id: String,
    val subject: String,
    val status: String,
    val updatedAt: String,
    val thread: String,
    val messages: List<PxBoardTicketMsg>,
)

internal data class PxBoardArticle(
    val id: String,
    val title: String,
    val content: String,
)

internal data class PxBoardAffiliateSummary(
    val availableAmount: Int = 0,
    val pendingAmount: Int = 0,
    val totalAmount: Int = 0,
    val invitedCount: Int = 0,
    val commissionPercent: Int = 0,
    val commissionHoldDays: Int = 0,
    val commissionMode: String = "",
    val referralUrl: String = "",
)

internal data class PxBoardCommission(
    val id: String,
    val amount: Int,
    val status: String,
    val createdAt: String,
)

internal data class PxBoardWithdrawal(
    val id: String,
    val amount: Int,
    val method: String,
    val status: String,
    val createdAt: String,
)

internal data class PxBoardMfaSetup(
    val secret: String,
    val otpauthUrl: String,
    val expiresAt: String,
)

internal data class PxBoardOAuthBinding(
    val id: String,
    val providerId: String,
    val providerName: String,
    val displayName: String,
    val createdAt: String,
    val requiresPassword: Boolean,
)

internal fun JSONObject.toPxBoardAdRewardLogItem(): AdRewardLogItem {
    val rawScene = optString("scene")
    val rawStatus = optString("status")
    val content = optString("rewardContent").ifEmpty {
        when {
            rawStatus == "failed" && rawScene == REWARD_SCENE_POINTS -> "余额奖励发放失败"
            rawStatus == "failed" -> "套餐奖励发放失败"
            rawScene == REWARD_SCENE_POINTS -> "余额奖励发放中"
            else -> "套餐奖励发放中"
        }
    }
    return AdRewardLogItem(
        id = optString("id").hashCode(),
        scene = rawScene,
        transactionId = optString("transactionId"),
        status = rawStatus,
        error = optString("error"),
        rewardContent = content,
        usedAt = optLong("usedAtUnix", 0L),
        createdAt = optLong("createdAtUnix", 0L),
    )
}

internal data class PxBoardPortalUiState(
    val busy: Boolean = false,
    val plans: List<PxBoardPlan> = emptyList(),
    val channels: List<PxBoardPaymentChannel> = emptyList(),
    val quote: PxBoardQuote? = null,
    val quoting: Boolean = false,
    val activeOrder: PxBoardOrder? = null,
    val orders: List<PxBoardOrder> = emptyList(),
    val ledger: List<PxBoardLedgerEntry> = emptyList(),
    val links: List<PxBoardSubLink> = emptyList(),
    val claims: List<PxBoardClaim> = emptyList(),
    val tickets: List<PxBoardTicket> = emptyList(),
    val selectedTicketId: String = "",
    val articles: List<PxBoardArticle> = emptyList(),
    val affiliate: PxBoardAffiliateSummary = PxBoardAffiliateSummary(),
    val commissions: List<PxBoardCommission> = emptyList(),
    val withdrawals: List<PxBoardWithdrawal> = emptyList(),
    val expireReminder: Boolean = true,
    val trafficReminder: Boolean = true,
    val expireEnabled: Boolean = false,
    val trafficEnabled: Boolean = false,
    val mfaEnabled: Boolean = false,
    val mfaSetup: PxBoardMfaSetup? = null,
    val oauthBindings: List<PxBoardOAuthBinding> = emptyList(),
    val oauthBindPending: Boolean = false,
)

internal class PxBoardSession(
    private val appTitle: String,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<XbClientUiState>,
    private val netoClient: NetoControllerClient,
    private val persist: suspend (XbClientUiState) -> Unit,
    private val onRewardLogsUpdated: (List<AdRewardLogItem>) -> Unit,
    private val message: (String) -> Unit,
    private val open: (String) -> Unit,
) {
    private val _portalState = MutableStateFlow(PxBoardPortalUiState())
    val portalState = _portalState.asStateFlow()
    private var cachedRevisionAuth = ""
    private var cachedRevision = ""
    private var bindJob: Job? = null
    private var bindAttempt = 0

    private val hasRewardUnits: Boolean
        get() = BuildConfig.PLAN_REWARDED_AD_UNIT_ID.isNotEmpty() ||
            BuildConfig.POINTS_REWARDED_AD_UNIT_ID.isNotEmpty()

    fun reset() {
        cancelOAuthBind()
        cachedRevisionAuth = ""
        cachedRevision = ""
        _portalState.value = PxBoardPortalUiState()
    }

    private fun normalizePortalPath(path: String): String =
        when (val trimmed = path.trim()) {
            "", "/" -> "/user"
            "/plans" -> "/user/plans"
            "/orders" -> "/user/orders"
            "/wallet" -> "/user/wallet"
            "/account" -> "/user/account"
            else -> if (trimmed == "/user" || trimmed.startsWith("/user/") || trimmed.startsWith("/user?")) trimmed else "/user"
        }

    fun portal(path: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val link = PxBoardApi.rpc(
                    "core",
                    "auth.browserLink",
                    state.value.authData,
                    JSONObject().put("redirectPath", normalizePortalPath(path)),
                ) as JSONObject
                open(PxBoardApi.publicUrl(link.getString("url")))
            } catch (e: Exception) { message(e.message ?: e.toString()) }
        }
    }

    private fun periodLabel(item: JSONObject): String {
        val explicit = item.optString("label")
        if (explicit.isNotEmpty()) return explicit
        return when (item.optString("kind")) {
            "reset" -> "重置流量"
            "traffic" -> "流量包"
            else -> if (item.optString("durationUnit") == "permanent") {
                "长期"
            } else {
                "${item.optInt("durationValue", 0)} " + if (item.optString("durationUnit") == "month") "个月" else "天"
            }
        }
    }

    private fun parseOrder(obj: JSONObject): PxBoardOrder =
        PxBoardOrder(
            id = obj.optString("id"),
            number = obj.optString("number"),
            planName = obj.optString("planName"),
            kind = obj.optString("kind"),
            amount = obj.optDouble("amount", 0.0).roundToInt(),
            discount = obj.optDouble("discount", 0.0).roundToInt(),
            surplusAmount = obj.optDouble("surplusAmount", 0.0).roundToInt(),
            surplusCredit = obj.optDouble("surplusCredit", 0.0).roundToInt(),
            status = obj.optString("status"),
            createdAt = obj.optString("createdAt"),
        )

    private fun parseLinks(arr: JSONArray?): List<PxBoardSubLink> {
        if (arr == null) return emptyList()
        return List(arr.length()) { idx ->
            val item = arr.getJSONObject(idx)
            val share = item.optString("shareUrl")
            PxBoardSubLink(
                id = item.optString("id"),
                name = item.optString("name").ifEmpty { "默认订阅" },
                shareUrl = if (share.isNotEmpty()) PxBoardApi.publicUrl(share) else "",
                boundClient = item.optString("boundClient"),
            )
        }
    }

    fun refresh(force: Boolean = false) {
        val current = state.value
        if (current.nodesLoading || current.authData.isEmpty()) return
        val auth = current.authData
        val useKnownRevision = !force &&
            cachedRevisionAuth == auth &&
            cachedRevision.isNotEmpty() &&
            (netoClient.snapshot.value.nodes.isNotEmpty() || current.subscriptionBlockReason.isNotEmpty())
        state.update { it.copy(nodesLoading = true) }
        scope.launch(Dispatchers.IO) {
            try {
                val profileInput = JSONObject()
                if (useKnownRevision) profileInput.put("knownRevision", cachedRevision)
                val calls = mutableListOf(
                    PxBoardBatchCall("me", "core", "account.me"),
                    PxBoardBatchCall("plugins", "core", "plugins.list"),
                    PxBoardBatchCall("profile", "subscriptions", "client.profile", profileInput),
                    PxBoardBatchCall("wallet", "commerce", "wallet.get"),
                    PxBoardBatchCall("announcements", "support", "announcements.list"),
                    PxBoardBatchCall("affiliate", "affiliate", "summary.get"),
                )
                if (hasRewardUnits) {
                    calls.add(PxBoardBatchCall("admob_prepare", "admob", "reward.prepare"))
                    calls.add(PxBoardBatchCall("admob_history", "admob", "reward.history"))
                }
                val batch = PxBoardApi.batch(calls, auth)
                val meRes = batch["me"]
                if (meRes?.status != 200 || meRes.data !is JSONObject) {
                    throw IllegalStateException(meRes?.errorMessage?.ifEmpty { "读取账户信息失败" } ?: "读取账户信息失败")
                }
                if (state.value.authData != auth) return@launch

                val user = meRes.data
                val mfaEnabled = user.optBoolean("mfaEnabled", false)
                val plugins = batch["plugins"]?.data as? JSONArray ?: JSONArray()
                val extensions = (0 until plugins.length())
                    .map { plugins.getJSONObject(it) }
                    .filter { it.optBoolean("enabled") }
                    .map { it.getString("id") }
                    .toSet()

                var planRewardEnabled = false
                var pointsRewardEnabled = false
                var adLogs: List<AdRewardLogItem>? = null
                if (hasRewardUnits && "admob" in extensions) {
                    val prep = batch["admob_prepare"]?.takeIf { it.status == 200 }?.data as? JSONObject
                    if (prep != null) {
                        planRewardEnabled = BuildConfig.PLAN_REWARDED_AD_UNIT_ID.isNotEmpty() && prep.optBoolean("planRewardAdEnabled")
                        pointsRewardEnabled = BuildConfig.POINTS_REWARDED_AD_UNIT_ID.isNotEmpty() && prep.optBoolean("pointsRewardAdEnabled")
                    }
                    val histArr = batch["admob_history"]?.takeIf { it.status == 200 }?.data as? JSONArray
                    if (histArr != null) {
                        adLogs = List(histArr.length()) { i -> histArr.getJSONObject(i).toPxBoardAdRewardLogItem() }
                    }
                }

                val wallet = batch["wallet"]?.takeIf { it.status == 200 }?.data as? JSONObject
                val affiliate = batch["affiliate"]?.takeIf { it.status == 200 }?.data as? JSONObject
                val notices = (batch["announcements"]?.takeIf { it.status == 200 }?.data as? JSONArray)?.let { arr ->
                    val items = ArrayList<NoticeItem>()
                    for (i in 0 until arr.length()) {
                        val row = arr.getJSONObject(i)
                        if (!row.optBoolean("published", true)) continue
                        val ts = row.optString("createdAt").takeIf { it.isNotEmpty() }?.let {
                            runCatching { Instant.parse(it).epochSecond }.getOrDefault(0L)
                        } ?: 0L
                        items.add(NoticeItem(id = items.size + 1, title = row.optString("title"), content = row.optString("content"), createdAt = ts))
                    }
                    items
                }

                var subscribeUrl = state.value.subscribeUrl
                var summary = state.value.subscriptionSummary
                var blocked = state.value.subscriptionBlockReason
                var usedBytes = state.value.subscriptionTrafficUsedBytes
                var totalBytes = state.value.subscriptionTrafficTotalBytes

                val profileRes = batch["profile"]
                if (profileRes?.status == 200 && profileRes.data is JSONObject) {
                    val profile = profileRes.data
                    val acc = profile.optJSONObject("account") ?: JSONObject()
                    usedBytes = if (acc.has("usedBytes")) acc.optLong("usedBytes") else (acc.optDouble("usedGiB", 0.0) * 1073741824.0).roundToLong()
                    totalBytes = if (acc.has("quotaBytes")) acc.optLong("quotaBytes") else (acc.optDouble("quotaGiB", 0.0) * 1073741824.0).roundToLong()
                    val expiredAtUnix = acc.optLong("expiredAtUnix", 0L).let { v ->
                        if (v > 0L) v else acc.optString("expiresAt").takeIf { it.isNotEmpty() }?.let {
                            runCatching { Instant.parse(it).epochSecond }.getOrDefault(0L)
                        } ?: 0L
                    }
                    val rawBlock = profile.optString("blockReason")
                    blocked = when (rawBlock) {
                        "", SUBSCRIPTION_BLOCK_NO_PLAN, SUBSCRIPTION_BLOCK_EXPIRED, SUBSCRIPTION_BLOCK_TRAFFIC, "suspended" -> rawBlock
                        else -> "suspended"
                    }
                    summary = if (!acc.optBoolean("exists", true)) {
                        ""
                    } else {
                        buildList {
                            add("${formatNetoBytes(usedBytes)} / " + if (acc.optBoolean("unlimited")) "∞" else formatNetoBytes(totalBytes))
                            if (expiredAtUnix > 0L) add(formatUnixDate(expiredAtUnix))
                        }.joinToString(" · ")
                    }
                    val links = parseLinks(profile.optJSONArray("links"))
                    subscribeUrl = links.firstOrNull { it.shareUrl.isNotEmpty() }?.shareUrl.orEmpty()
                    _portalState.update { it.copy(links = links) }

                    if (!profile.optBoolean("unchanged", false)) {
                        if (blocked.isNotEmpty()) {
                            netoClient.clearManagedProfile(name = appTitle, url = subscribeUrl)
                        } else {
                            val rawNodes = profile.optJSONArray("nodes") ?: JSONArray()
                            val r = profile.optJSONObject("routing") ?: JSONObject()
                            val routeConfigYaml = r.optString("routeConfigYaml").takeUnless { it == "null" }?.ifBlank { null }
                            val routeRuleCount = r.optInt("ruleCount", 0)
                            val previewArr = r.optJSONArray("rulesPreview") ?: r.optJSONArray("rules") ?: JSONArray()
                            val routeRulesPreview = List(previewArr.length().coerceAtMost(20)) { idx -> previewArr.getString(idx) }
                            netoClient.upsertManagedProfile(
                                id = "managed-default",
                                name = appTitle,
                                url = subscribeUrl,
                                nodes = rawNodes,
                                routeConfigYaml = routeConfigYaml,
                                rulesPreview = routeRulesPreview,
                                ruleCount = routeRuleCount,
                                usedDownloadBytes = usedBytes,
                                totalBytes = totalBytes,
                                expireUnix = expiredAtUnix,
                            )
                        }
                        cachedRevisionAuth = auth
                        cachedRevision = profile.optString("revision")
                    }
                } else if (profileRes != null && profileRes.status != 404) {
                    message(profileRes.errorMessage.ifEmpty { "订阅同步异常（HTTP ${profileRes.status}）" })
                }

                if (affiliate != null) {
                    _portalState.update {
                        it.copy(
                            affiliate = PxBoardAffiliateSummary(
                                availableAmount = affiliate.optDouble("availableAmount", 0.0).roundToInt(),
                                pendingAmount = affiliate.optDouble("pendingAmount", 0.0).roundToInt(),
                                totalAmount = affiliate.optDouble("totalAmount", 0.0).roundToInt(),
                                invitedCount = affiliate.optInt("invitedCount", 0),
                                commissionPercent = affiliate.optInt("commissionPercent", 0),
                                commissionHoldDays = affiliate.optInt("commissionHoldDays", 0),
                                commissionMode = affiliate.optString("commissionMode"),
                                referralUrl = affiliate.optString("referralPath").takeIf { p -> p.isNotEmpty() }?.let(PxBoardApi::publicUrl).orEmpty(),
                            ),
                        )
                    }
                }
                _portalState.update {
                    it.copy(
                        mfaEnabled = mfaEnabled,
                        mfaSetup = if (mfaEnabled) null else it.mfaSetup,
                    )
                }

                state.update { cur ->
                    cur.copy(
                        userEmail = user.getString("email"),
                        nativeExtensions = extensions,
                        paymentEnabled = BuildConfig.APP_PAYMENT_ENABLED && "commerce" in extensions,
                        planRewardAdEnabled = planRewardEnabled,
                        planRewardedAdUnitId = if (planRewardEnabled) BuildConfig.PLAN_REWARDED_AD_UNIT_ID else "",
                        pointsRewardAdEnabled = pointsRewardEnabled,
                        pointsRewardedAdUnitId = if (pointsRewardEnabled) BuildConfig.POINTS_REWARDED_AD_UNIT_ID else "",
                        adRewardLogs = adLogs ?: if ("admob" in extensions) cur.adRewardLogs else emptyList(),
                        balance = wallet?.optDouble("balance", 0.0)?.roundToInt() ?: cur.balance,
                        currencySymbol = wallet?.optString("currencySymbol") ?: cur.currencySymbol,
                        currencyUnit = wallet?.optString("currencyName") ?: cur.currencyUnit,
                        commissionBalance = affiliate?.optDouble("availableAmount", 0.0)?.roundToInt() ?: cur.commissionBalance,
                        inviteCommissionBalance = affiliate?.optDouble("availableAmount", 0.0)?.roundToInt() ?: cur.inviteCommissionBalance,
                        inviteCommissionRate = affiliate?.optInt("commissionPercent", cur.inviteCommissionRate) ?: cur.inviteCommissionRate,
                        notices = notices ?: cur.notices,
                        subscribeUrl = subscribeUrl,
                        subscriptionSummary = summary,
                        subscriptionBlockReason = blocked,
                        subscriptionTrafficUsedBytes = usedBytes,
                        subscriptionTrafficTotalBytes = totalBytes,
                        nodesLoading = false,
                        nodesUpdatedAt = System.currentTimeMillis(),
                    )
                }
                if (adLogs != null) onRewardLogsUpdated(adLogs)
                persist(state.value)
            } catch (e: Exception) { state.update { it.copy(nodesLoading = false) }; message(e.message ?: e.toString()) }
        }
    }

    fun refreshRewardConfig() {
        if (!hasRewardUnits) return
        val auth = state.value.authData
        if (auth.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                val batch = PxBoardApi.batch(
                    listOf(
                        PxBoardBatchCall("plugins", "core", "plugins.list"),
                        PxBoardBatchCall("admob_prepare", "admob", "reward.prepare"),
                        PxBoardBatchCall("admob_history", "admob", "reward.history"),
                    ),
                    auth,
                )
                val plugins = batch["plugins"]?.data as? JSONArray ?: JSONArray()
                val admobEnabled = (0 until plugins.length())
                    .map { plugins.getJSONObject(it) }
                    .any { it.optBoolean("enabled") && it.optString("id") == "admob" }
                if (!admobEnabled) {
                    state.update {
                        it.copy(
                            planRewardAdEnabled = false,
                            planRewardedAdUnitId = "",
                            pointsRewardAdEnabled = false,
                            pointsRewardedAdUnitId = "",
                            adRewardLogs = emptyList(),
                        )
                    }
                    persist(state.value)
                    return@launch
                }
                val prep = batch["admob_prepare"]?.takeIf { it.status == 200 }?.data as? JSONObject
                val histArr = batch["admob_history"]?.takeIf { it.status == 200 }?.data as? JSONArray
                val planEnabled = prep != null && BuildConfig.PLAN_REWARDED_AD_UNIT_ID.isNotEmpty() && prep.optBoolean("planRewardAdEnabled")
                val pointsEnabled = prep != null && BuildConfig.POINTS_REWARDED_AD_UNIT_ID.isNotEmpty() && prep.optBoolean("pointsRewardAdEnabled")
                val logs = histArr?.let { arr -> List(arr.length()) { i -> arr.getJSONObject(i).toPxBoardAdRewardLogItem() } }
                state.update { cur ->
                    cur.copy(
                        planRewardAdEnabled = planEnabled,
                        planRewardedAdUnitId = if (planEnabled) BuildConfig.PLAN_REWARDED_AD_UNIT_ID else "",
                        pointsRewardAdEnabled = pointsEnabled,
                        pointsRewardedAdUnitId = if (pointsEnabled) BuildConfig.POINTS_REWARDED_AD_UNIT_ID else "",
                        adRewardLogs = logs ?: cur.adRewardLogs,
                    )
                }
                if (logs != null) onRewardLogsUpdated(logs)
                persist(state.value)
            } catch (e: Exception) {
                message("广告配置加载失败：" + (e.message ?: e.toString()))
            }
        }
    }

    fun refreshRewardHistory(showLoading: Boolean = false) {
        if (!hasRewardUnits || "admob" !in state.value.nativeExtensions) return
        val auth = state.value.authData
        if (auth.isEmpty()) return
        if (showLoading) state.update { it.copy(adRewardLogsLoading = true) }
        scope.launch(Dispatchers.IO) {
            try {
                val arr = PxBoardApi.rpc("admob", "reward.history", auth) as? JSONArray ?: JSONArray()
                val logs = List(arr.length()) { i -> arr.getJSONObject(i).toPxBoardAdRewardLogItem() }
                state.update { it.copy(adRewardLogs = logs, adRewardLogsLoading = false) }
                onRewardLogsUpdated(logs)
                persist(state.value)
            } catch (e: Exception) {
                if (showLoading) state.update { it.copy(adRewardLogsLoading = false) }
                message("广告奖励记录加载失败：" + (e.message ?: e.toString()))
            }
        }
    }

    fun loadScreenData(screen: PassScreen) {
        val auth = state.value.authData
        if (auth.isEmpty()) return
        val ext = state.value.nativeExtensions
        scope.launch(Dispatchers.IO) {
            try {
                when (screen) {
                    PassScreen.PLANS -> {
                        val calls = mutableListOf<PxBoardBatchCall>()
                        if ("commerce" in ext) {
                            calls.add(PxBoardBatchCall("plans", "commerce", "plans.list", JSONObject().put("scope", "mine")))
                            calls.add(PxBoardBatchCall("wallet", "commerce", "wallet.get"))
                        }
                        if (BuildConfig.APP_PAYMENT_ENABLED && "payments" in ext) {
                            calls.add(PxBoardBatchCall("channels", "payments", "channels.list"))
                        }
                        if (hasRewardUnits && "admob" in ext) {
                            calls.add(PxBoardBatchCall("admob_prepare", "admob", "reward.prepare"))
                            calls.add(PxBoardBatchCall("admob_history", "admob", "reward.history"))
                        }
                        if (calls.isEmpty()) return@launch
                        val batch = PxBoardApi.batch(calls, auth)
                        val planArr = batch["plans"]?.data as? JSONArray ?: JSONArray()
                        val plans = ArrayList<PxBoardPlan>()
                        for (i in 0 until planArr.length()) {
                            val row = planArr.getJSONObject(i)
                            val pArr = row.optJSONArray("periods") ?: JSONArray()
                            val periods = List(pArr.length()) { pIdx ->
                                val p = pArr.getJSONObject(pIdx)
                                PxBoardPeriod(p.optString("id"), periodLabel(p), p.optDouble("price", 0.0).roundToInt())
                            }
                            if (periods.isNotEmpty()) {
                                plans.add(
                                    PxBoardPlan(
                                        id = row.optString("id"),
                                        name = row.optString("name"),
                                        description = row.optString("description"),
                                        quotaGiB = row.optDouble("quotaGiB", 0.0),
                                        unlimited = row.optBoolean("unlimited", false),
                                        periods = periods,
                                    ),
                                )
                            }
                        }
                        val chArr = batch["channels"]?.data as? JSONArray ?: JSONArray()
                        val channels = if (BuildConfig.APP_PAYMENT_ENABLED) {
                            (0 until chArr.length())
                                .map { chArr.getJSONObject(it) }
                                .filter { it.optBoolean("enabled", true) }
                                .map { PxBoardPaymentChannel(it.optString("id"), it.optString("name")) }
                        } else {
                            emptyList()
                        }
                        _portalState.update { it.copy(plans = plans, channels = channels) }
                        if (hasRewardUnits && "admob" in ext) {
                            val prep = batch["admob_prepare"]?.takeIf { it.status == 200 }?.data as? JSONObject
                            val histArr = batch["admob_history"]?.takeIf { it.status == 200 }?.data as? JSONArray
                            val logs = histArr?.let { arr -> List(arr.length()) { i -> arr.getJSONObject(i).toPxBoardAdRewardLogItem() } }
                            if (prep != null || logs != null) {
                                state.update { cur ->
                                    val planEn = if (prep != null) BuildConfig.PLAN_REWARDED_AD_UNIT_ID.isNotEmpty() && prep.optBoolean("planRewardAdEnabled") else cur.planRewardAdEnabled
                                    val ptsEn = if (prep != null) BuildConfig.POINTS_REWARDED_AD_UNIT_ID.isNotEmpty() && prep.optBoolean("pointsRewardAdEnabled") else cur.pointsRewardAdEnabled
                                    cur.copy(
                                        planRewardAdEnabled = planEn,
                                        planRewardedAdUnitId = if (planEn) BuildConfig.PLAN_REWARDED_AD_UNIT_ID else "",
                                        pointsRewardAdEnabled = ptsEn,
                                        pointsRewardedAdUnitId = if (ptsEn) BuildConfig.POINTS_REWARDED_AD_UNIT_ID else "",
                                        adRewardLogs = logs ?: cur.adRewardLogs,
                                    )
                                }
                                if (logs != null) onRewardLogsUpdated(logs)
                            }
                        }
                    }
                    PassScreen.PROFILE -> {
                        if ("subscriptions" in ext || ext.isEmpty()) {
                            val arr = PxBoardApi.rpc("subscriptions", "links.list", auth, JSONObject().put("mine", true)) as? JSONArray
                            if (arr != null) _portalState.update { it.copy(links = parseLinks(arr)) }
                        }
                    }
                    PassScreen.GIFT_CARDS -> {
                        val calls = mutableListOf<PxBoardBatchCall>()
                        if ("redeem" in ext) calls.add(PxBoardBatchCall("claims", "redeem", "claims.list", JSONObject().put("mine", true)))
                        if ("commerce" in ext) {
                            calls.add(PxBoardBatchCall("orders", "commerce", "orders.list", JSONObject().put("mine", true)))
                            calls.add(PxBoardBatchCall("ledger", "commerce", "ledger.list", JSONObject().put("mine", true)))
                        }
                        if (BuildConfig.APP_PAYMENT_ENABLED && "payments" in ext) {
                            calls.add(PxBoardBatchCall("channels", "payments", "channels.list"))
                        }
                        if (calls.isEmpty()) return@launch
                        val batch = PxBoardApi.batch(calls, auth)
                        val claimsArr = batch["claims"]?.data as? JSONArray ?: JSONArray()
                        val claims = List(claimsArr.length()) { i ->
                            val c = claimsArr.getJSONObject(i)
                            PxBoardClaim(
                                id = c.optString("id"),
                                name = c.optString("name"),
                                summary = c.optString("summary"),
                                status = c.optString("status"),
                                timestamp = c.optString("redeemedAt").ifEmpty { c.optString("createdAt") },
                            )
                        }
                        val orderArr = batch["orders"]?.data as? JSONArray ?: JSONArray()
                        val orders = List(orderArr.length()) { i -> parseOrder(orderArr.getJSONObject(i)) }
                        val ledgerArr = batch["ledger"]?.data as? JSONArray ?: JSONArray()
                        val ledger = List(ledgerArr.length()) { i ->
                            val l = ledgerArr.getJSONObject(i)
                            PxBoardLedgerEntry(l.optString("id"), l.optDouble("amount", 0.0).roundToInt(), l.optString("reason"), l.optString("createdAt"))
                        }
                        val chArr = batch["channels"]?.data as? JSONArray ?: JSONArray()
                        val channels = if (BuildConfig.APP_PAYMENT_ENABLED) {
                            (0 until chArr.length())
                                .map { chArr.getJSONObject(it) }
                                .filter { it.optBoolean("enabled", true) }
                                .map { PxBoardPaymentChannel(it.optString("id"), it.optString("name")) }
                        } else {
                            emptyList()
                        }
                        _portalState.update { it.copy(claims = claims, orders = orders, ledger = ledger, channels = channels) }
                    }
                    PassScreen.ACCOUNT_SECURITY -> {
                        val calls = mutableListOf(PxBoardBatchCall("me", "core", "account.me"))
                        if ("access" in ext) {
                            calls.add(PxBoardBatchCall("prefs", "access", "preferences.get"))
                        }
                        if ("oauth" in ext) {
                            calls.add(PxBoardBatchCall("oauth_providers", "oauth", "providers.list"))
                            calls.add(PxBoardBatchCall("oauth_bindings", "oauth", "bindings.list"))
                        }
                        val batch = PxBoardApi.batch(calls, auth)
                        val me = batch["me"]?.takeIf { it.status == 200 }?.data as? JSONObject
                        val prefs = batch["prefs"]?.takeIf { it.status == 200 }?.data as? JSONObject
                        if ("oauth" in ext) {
                            val pArr = batch["oauth_providers"]?.takeIf { it.status == 200 }?.data as? JSONArray
                            if (pArr != null) {
                                val providers = List(pArr.length()) { i ->
                                    val r = pArr.getJSONObject(i)
                                    OAuthProvider(r.getString("id"), r.getString("name"))
                                }
                                state.update { it.copy(oauthProviders = providers) }
                            }
                            val bArr = batch["oauth_bindings"]?.takeIf { it.status == 200 }?.data as? JSONArray
                            if (bArr != null) {
                                val bindings = List(bArr.length()) { i ->
                                    val r = bArr.getJSONObject(i)
                                    PxBoardOAuthBinding(
                                        id = r.optString("id"),
                                        providerId = r.optString("providerId"),
                                        providerName = r.optString("providerName"),
                                        displayName = r.optString("displayName"),
                                        createdAt = r.optString("createdAt"),
                                        requiresPassword = r.optBoolean("requiresPassword", false),
                                    )
                                }
                                _portalState.update { it.copy(oauthBindings = bindings) }
                            }
                        } else {
                            _portalState.update { it.copy(oauthBindings = emptyList()) }
                        }
                        _portalState.update { cur ->
                            val nextMfa = me?.optBoolean("mfaEnabled", cur.mfaEnabled) ?: cur.mfaEnabled
                            cur.copy(
                                mfaEnabled = nextMfa,
                                mfaSetup = if (nextMfa) null else cur.mfaSetup,
                                expireReminder = prefs?.optBoolean("expireReminder", cur.expireReminder) ?: cur.expireReminder,
                                trafficReminder = prefs?.optBoolean("trafficReminder", cur.trafficReminder) ?: cur.trafficReminder,
                                expireEnabled = prefs?.optBoolean("expireEnabled", cur.expireEnabled) ?: cur.expireEnabled,
                                trafficEnabled = prefs?.optBoolean("trafficEnabled", cur.trafficEnabled) ?: cur.trafficEnabled,
                            )
                        }
                    }
                    PassScreen.TICKETS, PassScreen.TICKET_DETAIL -> {
                        val batch = PxBoardApi.batch(
                            listOf(
                                PxBoardBatchCall("tickets", "support", "tickets.list", JSONObject().put("mine", true)),
                                PxBoardBatchCall("articles", "support", "articles.list"),
                            ),
                            auth,
                        )
                        val tArr = batch["tickets"]?.data as? JSONArray ?: JSONArray()
                        val tickets = List(tArr.length()) { i ->
                            val row = tArr.getJSONObject(i)
                            val mArr = row.optJSONArray("messages") ?: JSONArray()
                            val msgs = List(mArr.length()) { mIdx ->
                                val m = mArr.getJSONObject(mIdx)
                                PxBoardTicketMsg(m.optString("id", "$mIdx"), m.optString("author", "用户"), m.optString("content"), m.optString("createdAt"))
                            }
                            PxBoardTicket(row.optString("id"), row.optString("subject"), row.optString("status"), row.optString("updatedAt"), row.optString("thread"), msgs)
                        }
                        val aArr = batch["articles"]?.data as? JSONArray ?: JSONArray()
                        val articles = (0 until aArr.length())
                            .map { aArr.getJSONObject(it) }
                            .filter { it.optBoolean("published", true) }
                            .map { PxBoardArticle(it.optString("id"), it.optString("title"), it.optString("content")) }
                        _portalState.update { cur ->
                            cur.copy(
                                tickets = tickets,
                                selectedTicketId = cur.selectedTicketId.takeIf { id -> tickets.any { it.id == id } } ?: tickets.firstOrNull()?.id.orEmpty(),
                                articles = articles,
                            )
                        }
                    }
                    PassScreen.INVITE_DETAILS -> {
                        val batch = PxBoardApi.batch(
                            listOf(
                                PxBoardBatchCall("summary", "affiliate", "summary.get"),
                                PxBoardBatchCall("commissions", "affiliate", "commissions.list", JSONObject().put("mine", true)),
                                PxBoardBatchCall("withdrawals", "affiliate", "withdrawals.list", JSONObject().put("mine", true)),
                            ),
                            auth,
                        )
                        val s = batch["summary"]?.data as? JSONObject
                        val cArr = batch["commissions"]?.data as? JSONArray ?: JSONArray()
                        val wArr = batch["withdrawals"]?.data as? JSONArray ?: JSONArray()
                        _portalState.update { cur ->
                            cur.copy(
                                affiliate = if (s != null) {
                                    PxBoardAffiliateSummary(
                                        availableAmount = s.optDouble("availableAmount", 0.0).roundToInt(),
                                        pendingAmount = s.optDouble("pendingAmount", 0.0).roundToInt(),
                                        totalAmount = s.optDouble("totalAmount", 0.0).roundToInt(),
                                        invitedCount = s.optInt("invitedCount", 0),
                                        commissionPercent = s.optInt("commissionPercent", 0),
                                        commissionHoldDays = s.optInt("commissionHoldDays", 0),
                                        commissionMode = s.optString("commissionMode"),
                                        referralUrl = s.optString("referralPath").takeIf { p -> p.isNotEmpty() }?.let(PxBoardApi::publicUrl).orEmpty(),
                                    )
                                } else cur.affiliate,
                                commissions = List(cArr.length()) { i ->
                                    val row = cArr.getJSONObject(i)
                                    PxBoardCommission(row.optString("id"), row.optDouble("amount", 0.0).roundToInt(), row.optString("status"), row.optString("createdAt"))
                                },
                                withdrawals = List(wArr.length()) { i ->
                                    val row = wArr.getJSONObject(i)
                                    PxBoardWithdrawal(row.optString("id"), row.optDouble("amount", 0.0).roundToInt(), row.optString("method"), row.optString("status"), row.optString("createdAt"))
                                },
                            )
                        }
                    }
                    else -> Unit
                }
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            }
        }
    }

    fun clearActiveCheckout() {
        _portalState.update { it.copy(quote = null, quoting = false, activeOrder = null) }
    }

    fun quoteOrder(planId: String, periodId: String, coupon: String) {
        val auth = state.value.authData
        if (auth.isEmpty() || planId.isEmpty() || periodId.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(quoting = true, quote = null) }
            try {
                val res = PxBoardApi.rpc(
                    "commerce",
                    "orders.quote",
                    auth,
                    JSONObject().put("planId", planId).put("periodId", periodId).put("coupon", coupon.trim()),
                ) as JSONObject
                _portalState.update {
                    it.copy(
                        quoting = false,
                        quote = PxBoardQuote(
                            amount = res.optDouble("amount", 0.0).roundToInt(),
                            discount = res.optDouble("discount", 0.0).roundToInt(),
                            surplusAmount = res.optDouble("surplusAmount", 0.0).roundToInt(),
                            surplusCredit = res.optDouble("surplusCredit", 0.0).roundToInt(),
                        ),
                    )
                }
            } catch (e: Exception) {
                _portalState.update { it.copy(quoting = false, quote = null) }
                message(e.message ?: e.toString())
            }
        }
    }

    fun createPlanOrder(planId: String, periodId: String, coupon: String) {
        if (_portalState.value.busy) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                val res = PxBoardApi.rpc(
                    "commerce",
                    "orders.create",
                    auth,
                    JSONObject()
                        .put("planId", planId)
                        .put("periodId", periodId)
                        .put("coupon", coupon.trim())
                        .put("requestId", UUID.randomUUID().toString()),
                ) as JSONObject
                _portalState.update { it.copy(activeOrder = parseOrder(res)) }
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun payOrderWithWallet(orderId: String, onCompleted: () -> Unit = {}) {
        if (_portalState.value.busy) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                val res = PxBoardApi.rpc("commerce", "orders.pay", auth, JSONObject().put("id", orderId)) as JSONObject
                _portalState.update { it.copy(activeOrder = null, quote = null) }
                message(if (res.optString("status") == "fulfilled") "支付完成，权益已更新" else "订单正在处理")
                refresh(force = true)
                loadScreenData(state.value.screen)
                onCompleted()
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun checkoutOrderOnline(orderId: String, channelId: String, onOpened: () -> Unit = {}) {
        if (_portalState.value.busy) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                check(BuildConfig.APP_PAYMENT_ENABLED) { "当前客户端仅支持账户余额支付" }
                val res = PxBoardApi.rpc(
                    "payments",
                    "checkout",
                    auth,
                    JSONObject().put("orderId", orderId).put("channelId", channelId),
                ) as JSONObject
                val redirect = res.optString("redirectUrl")
                check(redirect.isNotEmpty()) { "支付渠道未返回跳转链接" }
                open(redirect)
                _portalState.update { it.copy(activeOrder = null, quote = null) }
                onOpened()
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun cancelOrder(orderId: String) {
        if (_portalState.value.busy) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                PxBoardApi.rpc("commerce", "orders.cancel", auth, JSONObject().put("id", orderId))
                message("订单已取消")
                loadScreenData(PassScreen.GIFT_CARDS)
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun rechargeWallet(amountCents: Int, channelId: String) {
        if (_portalState.value.busy || amountCents <= 0 || channelId.isEmpty()) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                check(BuildConfig.APP_PAYMENT_ENABLED) { "当前客户端仅支持兑换码与余额抵扣" }
                val order = PxBoardApi.rpc(
                    "commerce",
                    "orders.create",
                    auth,
                    JSONObject().put("kind", "recharge").put("amount", amountCents).put("requestId", UUID.randomUUID().toString()),
                ) as JSONObject
                val checkout = PxBoardApi.rpc(
                    "payments",
                    "checkout",
                    auth,
                    JSONObject().put("orderId", order.getString("id")).put("channelId", channelId),
                ) as JSONObject
                open(checkout.getString("redirectUrl"))
                loadScreenData(PassScreen.GIFT_CARDS)
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun createSubLink(name: String, bindUa: Boolean) {
        if (_portalState.value.busy) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                PxBoardApi.rpc("subscriptions", "links.create", auth, JSONObject().put("name", name.trim()).put("bindUserAgent", bindUa))
                message("订阅链接已创建")
                loadScreenData(PassScreen.PROFILE)
                refresh(force = true)
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun mutateSubLink(id: String, action: String) {
        if (_portalState.value.busy) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                PxBoardApi.rpc("subscriptions", "links.$action", auth, JSONObject().put("id", id))
                message(if (action == "rotate") "订阅链接已重置" else "订阅链接已删除")
                loadScreenData(PassScreen.PROFILE)
                refresh(force = true)
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun redeemCode(code: String, onRedeemed: () -> Unit = {}) {
        val trimmed = code.trim()
        if (_portalState.value.busy || trimmed.isEmpty()) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                val res = PxBoardApi.rpc(
                    "redeem",
                    "redeem.create",
                    auth,
                    JSONObject().put("code", trimmed).put("requestId", UUID.randomUUID().toString()),
                ) as JSONObject
                message(res.optString("message").ifEmpty { if (res.optString("status") == "redeemed") "兑换成功" else "兑换已登记" })
                onRedeemed()
                refresh(force = true)
                loadScreenData(PassScreen.GIFT_CARDS)
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun selectTicket(ticketId: String) {
        _portalState.update { it.copy(selectedTicketId = ticketId) }
        state.update { it.copy(screen = PassScreen.TICKET_DETAIL) }
    }

    fun createTicket(subject: String, content: String, onCreated: () -> Unit = {}) {
        if (_portalState.value.busy || subject.isBlank() || content.isBlank()) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                val res = PxBoardApi.rpc(
                    "support",
                    "tickets.create",
                    auth,
                    JSONObject().put("subject", subject.trim()).put("content", content.trim()),
                ) as JSONObject
                _portalState.update { it.copy(selectedTicketId = res.optString("id")) }
                message("工单已提交")
                onCreated()
                loadScreenData(PassScreen.TICKETS)
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun replyTicket(ticketId: String, reply: String, onSent: () -> Unit = {}) {
        if (_portalState.value.busy || reply.isBlank()) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                PxBoardApi.rpc("support", "tickets.reply", auth, JSONObject().put("id", ticketId).put("message", reply.trim()))
                onSent()
                loadScreenData(PassScreen.TICKET_DETAIL)
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun closeTicket(ticketId: String) {
        if (_portalState.value.busy) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                PxBoardApi.rpc("support", "tickets.close", auth, JSONObject().put("id", ticketId))
                message("工单已关闭")
                loadScreenData(PassScreen.TICKET_DETAIL)
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun uploadTicketAttachment(base64Data: String, onUploaded: (String) -> Unit) {
        if (_portalState.value.busy || base64Data.isEmpty()) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                val res = PxBoardApi.rpc("support", "attachments.create", auth, JSONObject().put("data", base64Data)) as JSONObject
                val url = PxBoardApi.publicUrl(res.getString("url"))
                onUploaded("![图片]($url)")
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun createWithdrawal(amountCents: Int, method: String, payoutAccount: String, onDone: () -> Unit = {}) {
        if (_portalState.value.busy || amountCents <= 0) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                val body = JSONObject()
                    .put("amount", amountCents)
                    .put("method", method)
                    .put("requestId", UUID.randomUUID().toString())
                if (method == "cash") body.put("payoutAccount", payoutAccount.trim())
                PxBoardApi.rpc("affiliate", "withdrawals.create", auth, body)
                message("结算申请已提交")
                onDone()
                refresh(force = true)
                loadScreenData(PassScreen.INVITE_DETAILS)
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun mutateWithdrawal(id: String, action: String) {
        if (_portalState.value.busy) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                PxBoardApi.rpc("affiliate", "withdrawals.$action", auth, JSONObject().put("id", id))
                refresh(force = true)
                loadScreenData(PassScreen.INVITE_DETAILS)
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun saveReminders(expire: Boolean, traffic: Boolean) {
        if (_portalState.value.busy) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true, expireReminder = expire, trafficReminder = traffic) }
            try {
                PxBoardApi.rpc(
                    "access",
                    "preferences.update",
                    auth,
                    JSONObject().put("expireReminder", expire).put("trafficReminder", traffic),
                )
                message("提醒设置已保存")
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun changePassword(currentPassword: String, code: String, newPassword: String, onSuccess: () -> Unit) {
        if (_portalState.value.busy || currentPassword.isEmpty() || newPassword.length < 10) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                val body = JSONObject().put("currentPassword", currentPassword).put("password", newPassword)
                if (code.trim().isNotEmpty()) body.put("code", code.trim())
                PxBoardApi.rpc("core", "account.changePassword", auth, body)
                message("密码已更新，请重新登录")
                onSuccess()
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun setupMfa() {
        if (_portalState.value.busy) return
        val auth = state.value.authData
        if (auth.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                val res = PxBoardApi.rpc("core", "account.mfaSetup", auth) as JSONObject
                _portalState.update {
                    it.copy(
                        mfaSetup = PxBoardMfaSetup(
                            secret = res.getString("secret"),
                            otpauthUrl = res.optString("otpauthUrl"),
                            expiresAt = res.optString("expiresAt"),
                        ),
                    )
                }
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun cancelMfaSetup() {
        _portalState.update { it.copy(mfaSetup = null) }
    }

    fun enableMfa(password: String, code: String, onEnabled: () -> Unit = {}) {
        val trimmedCode = code.trim()
        if (_portalState.value.busy || password.isEmpty() || trimmedCode.length != 6) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                PxBoardApi.rpc(
                    "core",
                    "account.mfaEnable",
                    auth,
                    JSONObject().put("password", password).put("code", trimmedCode),
                )
                _portalState.update { it.copy(mfaEnabled = true, mfaSetup = null) }
                message("已启用两步验证")
                onEnabled()
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun disableMfa(password: String, code: String, onDisabled: () -> Unit = {}) {
        val trimmedCode = code.trim()
        if (_portalState.value.busy || password.isEmpty() || trimmedCode.length != 6) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                PxBoardApi.rpc(
                    "core",
                    "account.mfaDisable",
                    auth,
                    JSONObject().put("password", password).put("code", trimmedCode),
                )
                _portalState.update { it.copy(mfaEnabled = false, mfaSetup = null) }
                message("已关闭两步验证")
                onDisabled()
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }

    fun cancelOAuthBind() {
        bindAttempt++
        bindJob?.cancel()
        _portalState.update { it.copy(oauthBindPending = false) }
    }

    fun bindOAuthProvider(providerId: String) {
        val trimmedProvider = providerId.trim()
        if (trimmedProvider.isEmpty()) return
        val auth = state.value.authData
        if (auth.isEmpty()) return
        cancelOAuthBind()
        val attempt = bindAttempt
        bindJob = scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(oauthBindPending = true) }
            try {
                val proof = UUID.randomUUID().toString().replace("-", "") +
                    UUID.randomUUID().toString().replace("-", "")
                val flow = PxBoardApi.rpc(
                    "oauth",
                    "authorize",
                    auth,
                    JSONObject()
                        .put("providerId", trimmedProvider)
                        .put("purpose", "bind")
                        .put("proof", proof)
                        .put("client", "native"),
                ) as JSONObject
                coroutineContext.ensureActive()
                open(PxBoardApi.publicUrl(flow.getString("authorizationUrl")))
                val expiresAt = runCatching { Instant.parse(flow.optString("expiresAt")).toEpochMilli() }
                    .getOrDefault(System.currentTimeMillis() + 5L * 60L * 1000L)
                val intervalMs = flow.optLong("intervalSeconds", 3L).coerceAtLeast(1L) * 1000L
                while (System.currentTimeMillis() < expiresAt) {
                    delay(intervalMs)
                    val res = PxBoardApi.rpc(
                        "oauth",
                        "bindExchange",
                        auth,
                        JSONObject().put("id", flow.getString("id")).put("proof", proof),
                    ) as JSONObject
                    coroutineContext.ensureActive()
                    when (val st = res.optString("status")) {
                        "pending" -> continue
                        "bound" -> {
                            message("第三方账号已绑定")
                            loadScreenData(PassScreen.ACCOUNT_SECURITY)
                            return@launch
                        }
                        else -> throw IllegalStateException("第三方账号绑定未通过：" + st)
                    }
                }
                throw IllegalStateException("第三方账号绑定超时，请重新发起")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                if (attempt == bindAttempt) {
                    _portalState.update { it.copy(oauthBindPending = false) }
                }
            }
        }
    }

    fun unbindOAuthBinding(bindingId: String, password: String, code: String, onUnbound: () -> Unit = {}) {
        val trimmedId = bindingId.trim()
        if (_portalState.value.busy || trimmedId.isEmpty()) return
        val auth = state.value.authData
        scope.launch(Dispatchers.IO) {
            _portalState.update { it.copy(busy = true) }
            try {
                val body = JSONObject().put("id", trimmedId)
                if (password.isNotEmpty()) body.put("password", password)
                if (code.trim().isNotEmpty()) body.put("code", code.trim())
                PxBoardApi.rpc("oauth", "bindings.delete", auth, body)
                message("已解除第三方账号绑定")
                onUnbound()
                loadScreenData(PassScreen.ACCOUNT_SECURITY)
            } catch (e: Exception) {
                message(e.message ?: e.toString())
            } finally {
                _portalState.update { it.copy(busy = false) }
            }
        }
    }
}
