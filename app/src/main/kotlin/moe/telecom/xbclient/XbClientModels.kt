package moe.telecom.xbclient

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val XBCLIENT_PREFS = "xbclient"
const val MODE_EXCLUDE = "exclude"
const val MODE_ALLOW = "allow"
const val DEFAULT_NODE_DNS = "https://dns.alidns.com/resolve"
const val DEFAULT_OVERSEAS_DNS = "https://cloudflare-dns.com/dns-query"
const val DEFAULT_DIRECT_DNS = "223.5.5.5"
const val DEFAULT_VIRTUAL_DNS_POOL = "198.18.0.0/15"
const val DEFAULT_NODE_TEST_TARGET = "https://cp.cloudflare.com"
const val DNS_MODE_OVER_TCP = "over_tcp"
const val DNS_MODE_VIRTUAL = "virtual"
const val DNS_MODE_DIRECT = "direct"
const val REWARD_SCENE_PLAN = "plan"
const val REWARD_SCENE_POINTS = "points"
const val SUBSCRIPTION_BLOCK_NO_PLAN = "no_plan"
const val SUBSCRIPTION_BLOCK_EXPIRED = "expired"
const val SUBSCRIPTION_BLOCK_TRAFFIC = "traffic_exceeded"

enum class AuthMode {
    LOGIN,
    REGISTER
}

enum class PassScreen {
    NODES,
    PLANS,
    PROFILE,
    INVITE_DETAILS,
    TRAFFIC_LOGS,
    TICKETS,
    TICKET_DETAIL,
    SETTINGS,
    NODE_SELECT,
    APP_RULES,
    OPEN_SOURCE_LICENSES
}

data class AnyTlsNode(
    val protocol: String,
    val name: String,
    val host: String,
    val port: Int,
    val tags: List<String>,
    val rawJson: String
) {
    fun displayName(index: Int, defaultName: String = "Node ${index + 1}"): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == host || trimmed == "$host:$port" || host.isNotEmpty() && trimmed.contains(host)) {
            return defaultName
        }
        return trimmed
    }

    val protocolLabel: String
        get() = when (protocol) {
            "anytls" -> "AnyTLS"
            "hysteria2" -> "Hysteria2"
            "hysteria" -> "Hysteria"
            "ss" -> "Shadowsocks"
            "vmess" -> "VMess"
            "vless" -> "VLESS"
            "trojan" -> "Trojan"
            "tuic" -> "TUIC"
            "socks5" -> "SOCKS5"
            "naive" -> "Naive"
            "http" -> "HTTP"
            "mieru" -> "Mieru"
            "direct" -> "Direct"
            "block" -> "Block"
            else -> protocol.uppercase(Locale.US)
        }

    val connectSupported: Boolean
        get() = when (protocol) {
            "anytls",
            "hysteria2",
            "trojan",
            "vless",
            "vmess",
            "mieru",
            "ss",
            "naive",
            "tuic",
            "http",
            "socks5",
            "direct",
            "block" -> true
            else -> false
        }
}

data class InviteItem(
    val code: String,
    val status: Int,
    val pv: Int,
    val createdAt: Long,
    val updatedAt: Long
)

data class CommissionLogItem(
    val id: Int,
    val orderAmount: Int,
    val tradeNo: String,
    val getAmount: Int,
    val createdAt: Long
)

data class TrafficLogItem(
    val upload: Long,
    val download: Long,
    val recordAt: Long,
    val serverRate: Double
)

data class TicketItem(
    val id: Int,
    val level: Int,
    val replyStatus: Int,
    val status: Int,
    val subject: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class TicketMessageItem(
    val id: Int,
    val ticketId: Int,
    val isMe: Boolean,
    val message: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class InstalledAppItem(
    val label: String,
    val packageName: String
)

data class OAuthProvider(
    val driver: String,
    val label: String
)

data class PlanPrice(
    val field: String,
    val label: String,
    val amount: Int
)

data class PlanItem(
    val id: Int,
    val name: String,
    val content: String,
    val transferEnable: Double,
    val prices: List<PlanPrice>
)

data class AdRewardLogItem(
    val id: Int,
    val scene: String,
    val transactionId: String,
    val status: String,
    val error: String,
    val rewardContent: String,
    val usedAt: Long,
    val createdAt: Long
)

data class NoticeItem(
    val id: Int,
    val title: String,
    val content: String,
    val createdAt: Long
)

fun JSONObject.toAnyTlsNode(): AnyTlsNode =
    getString("type").lowercase(Locale.US).let { rawProtocol ->
        val host = normalizeNodeHost(getString("host"))
        AnyTlsNode(
            protocol = rawProtocol,
            name = getString("name").trim(),
            host = host,
            port = getInt("port"),
            tags = nodeTags(this),
            rawJson = normalizedNodeJson(rawProtocol)
        )
    }

fun nodeTags(node: JSONObject): List<String> {
    val tags = ArrayList<String>()
    when (val value = node.opt("tags")) {
        is JSONArray -> {
            for (index in 0 until value.length()) {
                val tag = value.getString(index).trim()
                if (tag.isNotEmpty()) {
                    tags.add(tag)
                }
            }
        }
        is String -> value.split(',', '|').map { it.trim() }.filterTo(tags) { it.isNotEmpty() }
    }
    for (key in arrayOf("tag", "label", "group")) {
        if (node.has(key) && !node.isNull(key)) {
            val tag = node.getString(key).trim()
            if (tag.isNotEmpty()) {
                tags.add(tag)
            }
        }
    }
    return tags.distinct()
}

private fun JSONObject.normalizedNodeJson(protocol: String): String {
    val node = JSONObject(toString())
    val host = normalizeNodeHost(node.getString("host"))
    node.put("host", host)
    node.put("server", host)
    val currentSni = normalizeNodeHost(node.optString("sni"))
    if (currentSni.isBlank() || isIpLiteral(currentSni)) {
        val sni = listOf("server_name", "servername", "server-name").firstNotNullOfOrNull { key ->
            node.optString(key).trim().takeIf { it.isNotEmpty() && !isIpLiteral(it) }
        } ?: if (node.opt("tls") is JSONObject) {
            val tls = node.getJSONObject("tls")
            listOf("server_name", "servername", "server-name").firstNotNullOfOrNull { key ->
                tls.optString(key).trim().takeIf { it.isNotEmpty() && !isIpLiteral(it) }
            }
        } else {
            null
        }
        if (sni != null) {
            node.put("sni", sni)
        } else if (currentSni.isNotBlank()) {
            node.remove("sni")
        }
    }
    if (!node.optBoolean("insecure", false)) {
        for (key in arrayOf("skip-cert-verify", "skip_cert_verify", "allow_insecure")) {
            if (node.has(key)) {
                node.put("insecure", node.optBoolean(key))
                break
            }
        }
    }
    if (protocol in setOf("anytls", "hysteria2", "trojan", "vless", "vmess", "mieru", "naive", "tuic", "ss", "http", "socks5", "direct", "block")) {
        node.put("type", protocol)
    }
    return node.toString()
}

fun normalizeNodeHost(value: String): String {
    val host = value.trim()
    val inner = if (host.length > 2 && host.first() == '[' && host.last() == ']') {
        host.substring(1, host.length - 1)
    } else {
        ""
    }
    return if (inner.contains(":")) inner else host
}

fun isIpLiteral(value: String): Boolean {
    val host = normalizeNodeHost(value)
    return host.matches(Regex("^[0-9.]+$")) || host.matches(Regex("^[0-9A-Fa-f:.]+$")) && host.contains(":")
}

fun JSONObject.toInviteItem(): InviteItem =
    InviteItem(
        code = getString("code"),
        status = when (val value = get("status")) {
            is Boolean -> if (value) 1 else 0
            else -> numericValue(value).toInt()
        },
        pv = getInt("pv"),
        createdAt = numericValue(get("created_at")).toLong(),
        updatedAt = numericValue(get("updated_at")).toLong()
    )

fun JSONObject.toCommissionLogItem(): CommissionLogItem =
    CommissionLogItem(
        id = getInt("id"),
        orderAmount = numericValue(get("order_amount")).toInt(),
        tradeNo = getString("trade_no"),
        getAmount = numericValue(get("get_amount")).toInt(),
        createdAt = numericValue(get("created_at")).toLong()
    )

fun JSONObject.toTrafficLogItem(): TrafficLogItem =
    TrafficLogItem(
        upload = numericValue(get("u")).toLong(),
        download = numericValue(get("d")).toLong(),
        recordAt = numericValue(get("record_at")).toLong(),
        serverRate = numericValue(get("server_rate"))
    )

fun JSONObject.toTicketItem(): TicketItem =
    TicketItem(
        id = getInt("id"),
        level = getInt("level"),
        replyStatus = getInt("reply_status"),
        status = getInt("status"),
        subject = getString("subject"),
        createdAt = numericValue(get("created_at")).toLong(),
        updatedAt = numericValue(get("updated_at")).toLong()
    )

fun JSONObject.toTicketMessageItem(): TicketMessageItem =
    TicketMessageItem(
        id = getInt("id"),
        ticketId = getInt("ticket_id"),
        isMe = getBoolean("is_me"),
        message = getString("message"),
        createdAt = numericValue(get("created_at")).toLong(),
        updatedAt = numericValue(get("updated_at")).toLong()
    )

fun JSONObject.toOAuthProvider(): OAuthProvider =
    OAuthProvider(
        driver = getString("driver"),
        label = getString("label")
    )

fun JSONObject.toPlanItem(): PlanItem {
    val periodFields = listOf(
        "month_price" to "月付",
        "quarter_price" to "季付",
        "half_year_price" to "半年付",
        "year_price" to "年付",
        "two_year_price" to "两年付",
        "three_year_price" to "三年付",
        "onetime_price" to "一次性",
        "reset_price" to "重置流量"
    )
    val prices = periodFields.mapNotNull { (field, label) ->
        if (isNull(field)) {
            return@mapNotNull null
        }
        val amount = numericValue(opt(field)).toInt()
        if (amount <= 0) null else PlanPrice(field, label, amount)
    }
    return PlanItem(
        id = getInt("id"),
        name = getString("name"),
        content = getString("content"),
        transferEnable = numericValue(opt("transfer_enable")),
        prices = prices
    )
}

fun JSONArray.toAnyTlsNodeList(): List<AnyTlsNode> =
    List(length()) { index -> getJSONObject(index).toAnyTlsNode() }

fun JSONArray.toInviteItemList(): List<InviteItem> =
    List(length()) { index -> getJSONObject(index).toInviteItem() }

fun JSONArray.toCommissionLogItemList(): List<CommissionLogItem> =
    List(length()) { index -> getJSONObject(index).toCommissionLogItem() }

fun JSONArray.toTrafficLogItemList(): List<TrafficLogItem> =
    List(length()) { index -> getJSONObject(index).toTrafficLogItem() }

fun JSONArray.toTicketItemList(): List<TicketItem> =
    List(length()) { index -> getJSONObject(index).toTicketItem() }

fun JSONArray.toTicketMessageItemList(): List<TicketMessageItem> =
    List(length()) { index -> getJSONObject(index).toTicketMessageItem() }

fun JSONArray.toOAuthProviderList(): List<OAuthProvider> =
    List(length()) { index -> getJSONObject(index).toOAuthProvider() }

fun JSONArray.toPlanItemList(): List<PlanItem> =
    List(length()) { index -> getJSONObject(index).toPlanItem() }

fun JSONArray.toAdRewardLogItemList(): List<AdRewardLogItem> =
    List(length()) { index ->
        val item = getJSONObject(index)
        AdRewardLogItem(
            id = item.getInt("id"),
            scene = item.getString("scene"),
            transactionId = item.getString("transaction_id"),
            status = item.getString("status"),
            error = item.getString("error"),
            rewardContent = rewardContentText(item),
            usedAt = numericValue(item.opt("used_at")).toLong(),
            createdAt = numericValue(item.opt("created_at")).toLong()
        )
    }

fun JSONArray.toNoticeItemList(): List<NoticeItem> =
    List(length()) { index ->
        val item = getJSONObject(index)
        val title = item.getString("title")
        val content = item.getString("content")
        if (title.isBlank() && content.isBlank()) {
            throw IllegalStateException("公告缺少 title 或 content。")
        }
        NoticeItem(
            id = item.getInt("id"),
            title = title,
            content = content,
            createdAt = numericValue(item.opt("created_at")).toLong()
        )
    }

fun rewardContentText(item: JSONObject): String {
    if (item.has("reward_content") && !item.isNull("reward_content")) {
        val text = item.getString("reward_content")
        if (text.isNotBlank()) {
            return text
        }
    }
    val rewardValue = item.opt("rewards")
    if (rewardValue is JSONObject) {
        val rewards = rewardValue
        val parts = mutableListOf<String>()
        if (!rewards.isNull("balance")) {
            val balance = numericValue(rewards.opt("balance"))
            if (balance > 0.0) {
                parts.add("余额 " + String.format(Locale.US, "%.2f", balance / 100.0).trimEnd('0').trimEnd('.'))
            }
        }
        if (!rewards.isNull("transfer_enable")) {
            val transfer = numericValue(rewards.opt("transfer_enable"))
            if (transfer > 0.0) {
                parts.add("流量 ${formatTrafficBytes(transfer)}")
            }
        }
        if (!rewards.isNull("device_limit")) {
            val deviceLimit = numericValue(rewards.opt("device_limit")).toInt()
            if (deviceLimit > 0) {
                parts.add("设备数 +$deviceLimit")
            }
        }
        if (!rewards.isNull("reset_package")) {
            val resetPackage = rewards.get("reset_package")
            if (when (resetPackage) {
                    is Boolean -> resetPackage
                    else -> numericValue(resetPackage) > 0.0
                }
            ) {
                parts.add("重置流量")
            }
        }
        if (!rewards.isNull("plan_id")) {
            val planId = numericValue(rewards.opt("plan_id")).toInt()
            if (planId > 0) {
                parts.add("套餐 #$planId")
            }
        }
        if (!rewards.isNull("plan_validity_days")) {
            val planValidityDays = numericValue(rewards.opt("plan_validity_days")).toInt()
            if (planValidityDays > 0) {
                parts.add("套餐有效期 $planValidityDays 天")
            }
        }
        if (!rewards.isNull("expire_days")) {
            val expireDays = numericValue(rewards.opt("expire_days")).toInt()
            if (expireDays > 0) {
                parts.add("有效期 +$expireDays 天")
            }
        }
        if (parts.isEmpty()) {
            throw IllegalStateException("广告奖励记录 rewards 为空。")
        }
        return parts.joinToString(" · ")
    }
    throw IllegalStateException("广告奖励记录缺少 reward_content 或 rewards。")
}

fun JSONObject.requireNotXboardFail() {
    if (optString("status") == "fail") {
        throw IllegalStateException(optString("message").ifEmpty { "请求失败" })
    }
}

fun resultError(result: JSONObject): String {
    val bodyValue = result.opt("body")
    if (bodyValue != null && bodyValue != JSONObject.NULL) {
        if (bodyValue !is JSONObject) {
            throw IllegalStateException("错误响应 body 必须是对象：$result")
        }
        val message = bodyValue.optString("message")
        if (message.isNotEmpty()) {
            return message
        }
    }
    if (result.has("error") && result.getString("error").isNotEmpty()) {
        return result.getString("error")
    }
    throw IllegalStateException("错误响应缺少 message 或 error：$result")
}

fun extractDataArray(body: JSONObject): JSONArray {
    val data = body.opt("data")
    if (data is JSONArray) {
        return data
    }
    throw IllegalStateException("数据不是列表。")
}

fun subscriptionSummary(data: JSONObject): String {
    val used = numericValue(data.get("u")) + numericValue(data.get("d"))
    val total = numericValue(data.get("transfer_enable"))
    val planValue = data.opt("plan")
    val plan = when (planValue) {
        null, JSONObject.NULL -> null
        is JSONObject -> planValue
        else -> throw IllegalStateException("订阅套餐 plan 必须是对象。")
    }
    val planName = if (plan == null) "" else plan.getString("name")
    val expire = numericValue(data.get("expired_at")).toLong()
    val lines = ArrayList<String>()
    if (planName.isNotEmpty()) {
        lines.add(planName)
    }
    if (total > 0.0) {
        lines.add("已用 ${formatTrafficBytes(used)} / ${formatTrafficBytes(total)}")
    }
    if (expire > 0) {
        lines.add("到期 ${formatUnixDate(expire)}")
    }
    return lines.joinToString(" · ")
}

fun subscriptionBlockReason(data: JSONObject): String {
    val planId = numericValue(data.get("plan_id")).toInt()
    val planValue = data.opt("plan")
    val hasPlan = when (planValue) {
        null, JSONObject.NULL -> false
        is JSONObject -> true
        else -> throw IllegalStateException("订阅套餐 plan 必须是对象。")
    }
    if (planId <= 0 && !hasPlan) {
        return SUBSCRIPTION_BLOCK_NO_PLAN
    }
    val expiredAt = numericValue(data.get("expired_at")).toLong()
    if (!data.isNull("expired_at") && expiredAt <= System.currentTimeMillis() / 1000L) {
        return SUBSCRIPTION_BLOCK_EXPIRED
    }
    val total = numericValue(data.get("transfer_enable"))
    if (total <= 0.0 || numericValue(data.get("u")) + numericValue(data.get("d")) >= total) {
        return SUBSCRIPTION_BLOCK_TRAFFIC
    }
    return ""
}

fun readableNodeTestError(error: String): String {
    val normalized = error.lowercase(Locale.US)
    if (error.contains("read AnyTLS frame header")) {
        return "失败：AnyTLS 服务器断开连接（$error）"
    }
    if (error.contains("Hysteria2 target test")) {
        return "失败：Hysteria2 连接失败（$error）"
    }
    if (
        normalized.contains("read socks greeting response") ||
        normalized.contains("os error 10054") ||
        error.contains("远程主机强迫关闭")
    ) {
        return "失败：节点代理握手中断（$error）"
    }
    if (
        normalized.contains("timed out") ||
        normalized.contains("timeout") ||
        normalized.contains("socks connect failed: general failure") ||
        normalized.contains("read socks connect response") ||
        normalized.contains("early eof")
    ) {
        return "失败：连接超时"
    }
    return "失败：$error"
}

fun numericValue(value: Any?): Double = when (value) {
    null, JSONObject.NULL -> throw IllegalStateException("numeric value is required")
    is Number -> value.toDouble().also { require(it.isFinite()) { "numeric value is not finite" } }
    is String -> value.toDoubleOrNull() ?: throw IllegalStateException("numeric value is invalid: $value")
    else -> throw IllegalStateException("numeric value has unsupported type: ${value.javaClass.name}")
}

fun formatTrafficGb(value: Double): String = if (value >= 1024.0) {
    String.format(Locale.US, "%.2f TB", value / 1024.0)
} else {
    String.format(Locale.US, "%.0f GB", value)
}

fun formatTrafficBytes(value: Double): String {
    if (value <= 0.0) {
        return "0 B"
    }
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = value
    var index = 0
    while (size >= 1024.0 && index < units.size - 1) {
        size /= 1024.0
        index++
    }
    return String.format(Locale.US, "%.2f %s", size, units[index])
}

fun formatUnixDate(seconds: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(seconds * 1000L))
