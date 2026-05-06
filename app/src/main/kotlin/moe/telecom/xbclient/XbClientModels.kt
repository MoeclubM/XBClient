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
const val DEFAULT_NODE_TEST_TARGET = "cp.cloudflare.com"
const val DNS_MODE_OVER_TCP = "over_tcp"

enum class AuthMode {
    LOGIN,
    REGISTER
}

enum class PassScreen {
    NODES,
    PROFILE,
    SETTINGS,
    NODE_SELECT,
    APP_RULES
}

data class AnyTlsNode(
    val name: String,
    val host: String,
    val port: Int,
    val rawJson: String
) {
    fun displayName(index: Int): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == host || trimmed == "$host:$port" || host.isNotEmpty() && trimmed.contains(host)) {
            return "节点 ${index + 1}"
        }
        return trimmed
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

fun JSONObject.toAnyTlsNode(): AnyTlsNode =
    AnyTlsNode(
        name = optString("name"),
        host = optString("host"),
        port = optInt("port"),
        rawJson = toString()
    )

fun JSONObject.toInviteItem(): InviteItem =
    InviteItem(
        code = optString("code"),
        status = optInt("status")
    )

fun JSONArray.toAnyTlsNodeList(): List<AnyTlsNode> =
    List(length()) { index -> getJSONObject(index).toAnyTlsNode() }

fun JSONArray.toInviteItemList(): List<InviteItem> =
    List(length()) { index -> getJSONObject(index).toInviteItem() }

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
        for (key in arrayOf("data", "invite_codes", "codes", "list", "items")) {
            val nested = data.optJSONObject(key)
            if (nested != null) {
                directArray(nested)?.let { return it }
            }
        }
        val values = JSONArray()
        val keys = data.keys()
        while (keys.hasNext()) {
            val value = data.opt(keys.next())
            if (value is JSONObject && value.has("code")) {
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
    for (key in arrayOf("data", "invite_codes", "codes", "list", "items")) {
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

fun readableNodeTestError(error: String): String {
    if (error.contains("read AnyTLS frame header")) {
        return "失败：AnyTLS 服务器关闭连接（$error）"
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
