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
const val DEFAULT_NODE_TEST_TARGET = "https://cp.cloudflare.com"
const val DNS_MODE_OVER_TCP = "over_tcp"
const val REWARD_SCENE_PLAN = "plan"
const val REWARD_SCENE_POINTS = "points"
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
    SETTINGS,
    NODE_SELECT,
    APP_RULES
}

data class AnyTlsNode(
    val protocol: String,
    val name: String,
    val host: String,
    val port: Int,
    val rawJson: String
) {
    fun displayName(index: Int, fallback: String = "Node ${index + 1}"): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == host || trimmed == "$host:$port" || host.isNotEmpty() && trimmed.contains(host)) {
            return fallback
        }
        return trimmed
    }

    val protocolLabel: String
        get() = when (protocol) {
            "anytls" -> "AnyTLS"
            "hysteria2", "hy2" -> "Hysteria2"
            "hysteria" -> "Hysteria"
            "ss", "shadowsocks" -> "Shadowsocks"
            "vmess" -> "VMess"
            "vless" -> "VLESS"
            "trojan" -> "Trojan"
            "tuic" -> "TUIC"
            "socks", "socks5" -> "SOCKS5"
            "naive", "naive+https", "naive+quic" -> "Naive"
            "http" -> "HTTP"
            "mieru", "mierus" -> "Mieru"
            else -> protocol.uppercase(Locale.US)
        }

    val connectSupported: Boolean
        get() = when (protocol) {
            "anytls",
            "hysteria2",
            "hy2",
            "trojan",
            "vless",
            "vmess",
            "mieru",
            "mierus",
            "naive",
            "naive+https",
            "naive+quic" -> true
            else -> false
        }
}

data class InviteItem(
    val code: String,
    val status: Int
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
    optString("type", optString("protocol", "anytls")).lowercase(Locale.US).let { rawProtocol ->
        val protocol = when (rawProtocol) {
            "hy2" -> "hysteria2"
            "mierus" -> "mieru"
            "naive+https", "naive+quic" -> "naive"
            else -> rawProtocol
        }
        AnyTlsNode(
            protocol = protocol,
            name = optString("name"),
            host = optString("host", optString("server")),
            port = optInt("port", optInt("server_port")),
            rawJson = normalizedNodeJson(protocol, rawProtocol)
        )
    }

private fun JSONObject.normalizedNodeJson(protocol: String, rawProtocol: String): String {
    val node = JSONObject(toString())
    if (protocol in setOf("anytls", "hysteria2", "trojan", "vless", "vmess", "mieru", "naive")) {
        if (node.optString("host").isEmpty() && node.optString("server").isNotEmpty()) {
            node.put("host", node.optString("server"))
        }
        node.put("type", protocol)
        if (rawProtocol == "naive+quic") {
            node.put("quic", true)
        }
        if (!node.has("insecure")) {
            node.put("insecure", node.optBoolean("skip-cert-verify", false))
        }
        node.remove("skip-cert-verify")
    }
    return node.toString()
}

fun JSONObject.toInviteItem(): InviteItem =
    InviteItem(
        code = optString("code"),
        status = optInt("status")
    )

fun JSONObject.toOAuthProvider(): OAuthProvider =
    OAuthProvider(
        driver = getString("driver"),
        label = optString("label", getString("driver"))
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
        val amount = numericValue(opt(field)).toInt()
        if (isNull(field) || amount <= 0) null else PlanPrice(field, label, amount)
    }
    return PlanItem(
        id = getInt("id"),
        name = optString("name", "套餐 ${getInt("id")}"),
        content = optString("content"),
        transferEnable = numericValue(opt("transfer_enable")),
        prices = prices
    )
}

fun JSONArray.toAnyTlsNodeList(): List<AnyTlsNode> =
    List(length()) { index -> getJSONObject(index).toAnyTlsNode() }

fun JSONArray.toInviteItemList(): List<InviteItem> =
    List(length()) { index -> getJSONObject(index).toInviteItem() }

fun JSONArray.toOAuthProviderList(): List<OAuthProvider> =
    List(length()) { index -> getJSONObject(index).toOAuthProvider() }

fun JSONArray.toPlanItemList(): List<PlanItem> =
    List(length()) { index -> getJSONObject(index).toPlanItem() }

fun JSONArray.toAdRewardLogItemList(): List<AdRewardLogItem> =
    List(length()) { index ->
        val item = getJSONObject(index)
        AdRewardLogItem(
            id = item.optInt("id"),
            scene = item.optString("scene"),
            transactionId = item.optString("transaction_id"),
            status = item.optString("status"),
            error = item.optString("error"),
            rewardContent = rewardContentText(item),
            usedAt = numericValue(item.opt("used_at")).toLong(),
            createdAt = numericValue(item.opt("created_at")).toLong()
        )
    }

fun JSONArray.toNoticeItemList(): List<NoticeItem> =
    List(length()) { index ->
        val item = getJSONObject(index)
        NoticeItem(
            id = item.optInt("id"),
            title = item.optString("title", item.optString("subject")),
            content = item.optString("content", item.optString("message")),
            createdAt = numericValue(item.opt("created_at")).toLong()
        )
    }.filter { it.title.isNotBlank() || it.content.isNotBlank() }

fun rewardContentText(item: JSONObject): String {
    for (key in arrayOf("reward_content", "reward_text", "reward_description", "description")) {
        val text = item.optString(key)
        if (text.isNotBlank()) {
            return text
        }
    }
    val rewards = item.optJSONObject("rewards") ?: item.optJSONObject("rewards_given")
    if (rewards != null) {
        val parts = mutableListOf<String>()
        val balance = numericValue(rewards.opt("balance"))
        if (balance > 0.0) {
            parts.add("余额 " + String.format(Locale.US, "%.2f", balance / 100.0).trimEnd('0').trimEnd('.'))
        }
        val transfer = numericValue(rewards.opt("transfer_enable"))
        if (transfer > 0.0) {
            parts.add("流量 ${formatTrafficBytes(transfer)}")
        }
        val deviceLimit = numericValue(rewards.opt("device_limit")).toInt()
        if (deviceLimit > 0) {
            parts.add("设备数 +$deviceLimit")
        }
        if (rewards.optBoolean("reset_package") || numericValue(rewards.opt("reset_package")) > 0.0) {
            parts.add("重置流量")
        }
        val planId = numericValue(rewards.opt("plan_id")).toInt()
        if (planId > 0) {
            parts.add("套餐 #$planId")
        }
        val planValidityDays = numericValue(rewards.opt("plan_validity_days")).toInt()
        if (planValidityDays > 0) {
            parts.add("套餐有效期 $planValidityDays 天")
        }
        val expireDays = numericValue(rewards.opt("expire_days")).toInt()
        if (expireDays > 0) {
            parts.add("有效期 +$expireDays 天")
        }
        return parts.joinToString(" · ")
    }
    return ""
}

fun resultError(result: JSONObject): String {
    val body = result.optJSONObject("body")
    if (body != null && body.optString("message").isNotEmpty()) {
        return body.optString("message")
    }
    if (result.optString("error").isNotEmpty()) {
        return result.optString("error")
    }
    return result.toString()
}

fun extractDataArray(body: JSONObject): JSONArray {
    val data = body.opt("data")
    if (data is JSONArray) {
        return data
    }
    if (data is JSONObject) {
        directArray(data)?.let { return it }
        for (key in arrayOf("data", "invite_codes", "codes", "list", "items", "notices")) {
            val nested = data.optJSONObject(key)
            if (nested != null) {
                directArray(nested)?.let { return it }
            }
        }
        val values = JSONArray()
        val keys = data.keys()
        while (keys.hasNext()) {
            val value = data.opt(keys.next())
            if (value is JSONObject && (value.has("code") || value.has("title") || value.has("content"))) {
                values.put(value)
            }
        }
        if (values.length() > 0) {
            return values
        }
    }
    throw IllegalStateException("数据不是列表。")
}

private fun directArray(data: JSONObject): JSONArray? {
    for (key in arrayOf("data", "invite_codes", "codes", "list", "items", "notices")) {
        val array = data.optJSONArray(key)
        if (array != null) {
            return array
        }
    }
    return null
}

fun subscriptionSummary(data: JSONObject): String {
    val used = numericValue(data.opt("u")) + numericValue(data.opt("d"))
    val total = numericValue(data.opt("transfer_enable"))
    val planName = data.optJSONObject("plan")?.optString("name").orEmpty()
    val expire = numericValue(data.opt("expired_at")).toLong()
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
    val expiredAt = numericValue(data.opt("expired_at")).toLong()
    if (expiredAt > 0L && expiredAt <= System.currentTimeMillis() / 1000L) {
        return SUBSCRIPTION_BLOCK_EXPIRED
    }
    val total = numericValue(data.opt("transfer_enable"))
    if (total > 0.0 && numericValue(data.opt("u")) + numericValue(data.opt("d")) >= total) {
        return SUBSCRIPTION_BLOCK_TRAFFIC
    }
    return ""
}

fun readableNodeTestError(error: String): String {
    if (error.contains("read AnyTLS frame header")) {
        return "失败：AnyTLS 服务器断开连接（$error）"
    }
    if (error.contains("Hysteria2 target test")) {
        return "失败：Hysteria2 连接失败（$error）"
    }
    if (error.contains("timed out")) {
        return "失败：连接超时（$error）"
    }
    return "失败：$error"
}

fun numericValue(value: Any?): Double = when (value) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull() ?: 0.0
    else -> 0.0
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
