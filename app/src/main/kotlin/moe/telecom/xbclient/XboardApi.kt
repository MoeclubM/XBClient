package moe.telecom.xbclient

import android.net.Uri
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.yaml.snakeyaml.Yaml
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

object XboardApi {
    private const val SUBSCRIPTION_USER_AGENT = "mihomo"
    private val userAgent: String
        get() = BuildConfig.USER_AGENT

    fun request(action: String, baseUrl: String, authData: String, params: JSONObject): JSONObject {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        return when (action) {
            // Xboard 原版 API
            "send_email_verify" -> post(normalizedBaseUrl, "/api/v1/passport/comm/sendEmailVerify", "", params)
            "login" -> post(normalizedBaseUrl, "/api/v1/passport/auth/login", "", params)
            "register" -> post(normalizedBaseUrl, "/api/v1/passport/auth/register", "", params)
            "forget_password" -> post(normalizedBaseUrl, "/api/v1/passport/auth/forget", "", params)
            "token_login" -> requestJson("GET", normalizedBaseUrl, "/api/v1/passport/auth/token2Login", "", requiredQuery(params, "verify"), null)
            "confirm_oauth_register" -> post(normalizedBaseUrl, "/api/v1/passport/auth/oauth/confirm-register", "", params)
            "passport_quick_login_url" -> post(normalizedBaseUrl, "/api/v1/passport/auth/getQuickLoginUrl", "", params)
            "login_with_mail_link" -> post(normalizedBaseUrl, "/api/v1/passport/auth/loginWithMailLink", "", params)
            "user_info" -> getAuth(normalizedBaseUrl, "/api/v1/user/info", authData, emptyMap())
            "user_subscribe" -> getAuth(normalizedBaseUrl, "/api/v1/user/getSubscribe", authData, emptyMap())
            "guest_config" -> requestJson("GET", normalizedBaseUrl, "/api/v1/guest/comm/config", "", emptyMap(), null)
            "check_login" -> getAuth(normalizedBaseUrl, "/api/v1/user/checkLogin", authData, emptyMap())
            "user_stat" -> getAuth(normalizedBaseUrl, "/api/v1/user/getStat", authData, emptyMap())
            "user_update" -> postAuth(normalizedBaseUrl, "/api/v1/user/update", authData, params)
            "change_password" -> postAuth(normalizedBaseUrl, "/api/v1/user/changePassword", authData, params)
            "reset_security" -> getAuth(normalizedBaseUrl, "/api/v1/user/resetSecurity", authData, emptyMap())
            "transfer" -> postAuth(normalizedBaseUrl, "/api/v1/user/transfer", authData, params)
            "quick_login_url" -> postAuth(normalizedBaseUrl, "/api/v1/user/getQuickLoginUrl", authData, params)
            "plan_fetch" -> getAuth(normalizedBaseUrl, "/api/v1/user/plan/fetch", authData, emptyMap())
            "oauth_bindings" -> getAuth(normalizedBaseUrl, "/api/v1/user/oauth/bindings", authData, emptyMap())
            "oauth_bind_prepare" -> postAuth(normalizedBaseUrl, "/api/v1/user/oauth/${params.getString("driver")}/bind", authData, JSONObject())
            "oauth_unbind" -> postAuth(normalizedBaseUrl, "/api/v1/user/oauth/${params.getString("driver")}/unbind", authData, JSONObject())
            "active_sessions" -> getAuth(normalizedBaseUrl, "/api/v1/user/getActiveSession", authData, emptyMap())
            "remove_active_session" -> postAuth(normalizedBaseUrl, "/api/v1/user/removeActiveSession", authData, params)
            "gift_card_check" -> postAuth(normalizedBaseUrl, "/api/v1/user/gift-card/check", authData, params)
            "gift_card_redeem" -> postAuth(normalizedBaseUrl, "/api/v1/user/gift-card/redeem", authData, params)
            "gift_card_history" -> getAuth(normalizedBaseUrl, "/api/v1/user/gift-card/history", authData, optionalQuery(params, "page", "per_page"))
            "gift_card_detail" -> getAuth(normalizedBaseUrl, "/api/v1/user/gift-card/detail", authData, requiredQuery(params, "id"))
            "gift_card_types" -> getAuth(normalizedBaseUrl, "/api/v1/user/gift-card/types", authData, emptyMap())
            "invite_fetch" -> getAuth(normalizedBaseUrl, "/api/v1/user/invite/fetch", authData, emptyMap())
            "invite_save" -> getAuth(normalizedBaseUrl, "/api/v1/user/invite/save", authData, emptyMap())
            "invite_details" -> getAuth(normalizedBaseUrl, "/api/v1/user/invite/details", authData, emptyMap())
            "tickets" -> getAuth(normalizedBaseUrl, "/api/v1/user/ticket/fetch", authData, optionalQuery(params, "id"))
            "ticket_save" -> postAuth(normalizedBaseUrl, "/api/v1/user/ticket/save", authData, params)
            "ticket_reply" -> postAuth(normalizedBaseUrl, "/api/v1/user/ticket/reply", authData, params)
            "ticket_close" -> postAuth(normalizedBaseUrl, "/api/v1/user/ticket/close", authData, params)
            "ticket_withdraw" -> postAuth(normalizedBaseUrl, "/api/v1/user/ticket/withdraw", authData, params)
            "notices" -> getAuth(normalizedBaseUrl, "/api/v1/user/notice/fetch", authData, emptyMap())
            "coupon_check" -> postAuth(normalizedBaseUrl, "/api/v1/user/coupon/check", authData, params)
            "traffic_logs" -> getAuth(normalizedBaseUrl, "/api/v1/user/stat/getTrafficLog", authData, emptyMap())
            "telegram_bot" -> getAuth(normalizedBaseUrl, "/api/v1/user/telegram/getBotInfo", authData, emptyMap())
            "knowledge" -> getAuth(normalizedBaseUrl, "/api/v1/user/knowledge/fetch", authData, optionalQuery(params, "id", "language", "keyword"))
            "user_config" -> getAuth(normalizedBaseUrl, "/api/v1/user/comm/config", authData, emptyMap())
            "stripe_public_key" -> postAuth(normalizedBaseUrl, "/api/v1/user/comm/getStripePublicKey", authData, params)
            "nodes" -> getAuth(normalizedBaseUrl, "/api/v1/user/server/fetch", authData, emptyMap())

            // Xboard 插件 API
            "admob_reward_config" -> getAuth(normalizedBaseUrl, "/api/v1/admob/user/config", authData, emptyMap())

            // 非站点 API：订阅内容解析
            "anytls_nodes" -> fetchProxyNodes(params.getString("subscribe_url"), params.optString("flag", "meta"))
            else -> throw IllegalArgumentException("unsupported Xboard action: $action")
        }
    }


    fun resolveNodeHost(dns: String, host: String): String {
        if (host.matches(Regex("^[0-9.]+$")) || host.contains(":")) {
            return host
        }
        val resolver = dns.trim()
        if (!resolver.startsWith("http://") && !resolver.startsWith("https://")) {
            throw IllegalStateException("节点 DNS 必须是 DoH 地址。")
        }
        for (type in arrayOf("A", "AAAA")) {
            val url = Uri.parse(resolver)
                .buildUpon()
                .appendQueryParameter("name", host)
                .appendQueryParameter("type", type)
                .build()
                .toString()
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/dns-json, application/json")
            }
            val status = connection.responseCode
            val text = readBody(connection)
            if (status !in 200..299) {
                throw IllegalStateException("节点 DNS 请求失败：HTTP $status")
            }
            val body = parseJson(text)
            if (body !is JSONObject) {
                throw IllegalStateException("节点 DNS 响应不是 JSON。")
            }
            val answers = body.optJSONArray("Answer") ?: continue
            for (index in 0 until answers.length()) {
                val data = answers.getJSONObject(index).optString("data")
                if (data.matches(Regex("^[0-9.]+$")) || data.matches(Regex("^[0-9A-Fa-f:.]+$")) && data.contains(":")) {
                    return data
                }
            }
        }
        throw IllegalStateException("节点 DNS 无可用 A/AAAA 记录。")
    }

    fun dnsAddressForVpn(value: String): String {
        val dns = value.trim()
        if (dns.matches(Regex("^[0-9.]+$")) || dns.matches(Regex("^[0-9A-Fa-f:.]+$")) && dns.contains(":")) {
            return dns
        }
        val lower = dns.lowercase()
        if (lower.contains("cloudflare-dns.com") || lower.contains("1.1.1.1")) {
            return "1.1.1.1"
        }
        if (lower.contains("dns.alidns.com") || lower.contains("223.5.5.5")) {
            return "223.5.5.5"
        }
        throw IllegalStateException("海外 DNS 需填写普通 DNS 地址，或已支持的 DoH 地址。")
    }

    private fun post(baseUrl: String, path: String, authData: String, params: JSONObject): JSONObject =
        requestJson("POST", baseUrl, path, authData, emptyMap(), params)

    private fun postAuth(baseUrl: String, path: String, authData: String, params: JSONObject): JSONObject {
        requireAuth(authData)
        return post(baseUrl, path, authData, params)
    }

    private fun getAuth(baseUrl: String, path: String, authData: String, query: Map<String, String>): JSONObject {
        requireAuth(authData)
        return requestJson("GET", baseUrl, path, authData, query, null)
    }

    private fun requestJson(
        method: String,
        baseUrl: String,
        path: String,
        authData: String,
        query: Map<String, String>,
        body: JSONObject?
    ): JSONObject {
        val connection = (URL(baseUrl + path + queryString(query)).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30000
            readTimeout = 30000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            if (authData.isNotEmpty()) {
                setRequestProperty("Authorization", authData)
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (body != null) {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val text = readBody(connection)
        val parsedBody = parseJson(text)
        return JSONObject()
            .put("ok", status in 200..299)
            .put("status", status)
            .put("body", parsedBody)
            .also {
                if (status !in 200..299) {
                    it.put("error", "HTTP $status")
                }
            }
    }

    private fun fetchProxyNodes(subscribeUrl: String, flag: String): JSONObject {
        val url = Uri.parse(subscribeUrl)
            .buildUpon()
            .appendQueryParameter("types", "anytls,hysteria")
            .appendQueryParameter("flag", flag)
            .build()
            .toString()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30000
            readTimeout = 30000
            setRequestProperty("User-Agent", SUBSCRIPTION_USER_AGENT)
            setRequestProperty("Accept", "text/yaml, application/yaml, text/plain, */*")
        }
        val status = connection.responseCode
        val text = readBody(connection)
        if (status !in 200..299) {
            return JSONObject()
                .put("ok", false)
                .put("status", status)
                .put("error", "HTTP $status")
                .put("body", text)
        }

        val root = Yaml().load<Any>(text) as Map<*, *>
        val proxies = root["proxies"] as List<*>
        val nodes = JSONArray()
        for (item in proxies) {
            val proxy = item as Map<*, *>
            val type = proxy["type"]?.toString()?.lowercase(Locale.US).orEmpty()
            if (type != "anytls" && type != "hysteria2" && type != "hy2") {
                continue
            }
            val name = proxy["name"]?.toString().orEmpty()
            if (name.startsWith("剩余流量：") || name.startsWith("距离下次重置剩余：") || name.startsWith("套餐到期：")) {
                continue
            }
            nodes.put(if (type == "anytls") anyTlsNode(proxy) else hysteria2Node(proxy))
        }
        return JSONObject()
            .put("ok", true)
            .put("status", status)
            .put("format", "clashmeta")
            .put("flag", flag)
            .put("subscription_userinfo", connection.getHeaderField("subscription-userinfo"))
            .put("nodes", nodes)
    }

    private fun anyTlsNode(proxy: Map<*, *>): JSONObject {
        val host = proxy["server"].toString()
        return JSONObject()
            .put("type", "anytls")
            .put("name", proxy["name"]?.toString().orEmpty())
            .put("raw", toJson(proxy).toString())
            .put("host", host)
            .put("port", proxy["port"].toString().toInt())
            .put("password", proxy["password"].toString())
            .put("sni", proxy["sni"]?.toString().takeUnless { it.isNullOrEmpty() } ?: host)
            .put("insecure", proxy["skip-cert-verify"] == true)
            .put("udp", proxy["udp"] == true)
    }

    private fun hysteria2Node(proxy: Map<*, *>): JSONObject {
        val host = proxy["server"].toString()
        val obfs = proxy["obfs"]
        val obfsType = when (obfs) {
            is Map<*, *> -> obfs["type"]?.toString().orEmpty()
            null -> ""
            else -> obfs.toString()
        }
        val obfsPassword = proxy["obfs-password"]?.toString()
            ?: proxy["obfs_password"]?.toString()
            ?: proxy["obfsPassword"]?.toString()
            ?: if (obfs is Map<*, *>) obfs["password"]?.toString() else null
        val port = (proxy["port"] ?: proxy["server-port"] ?: proxy["server_port"]).toString().toInt()
        val node = JSONObject()
            .put("type", "hysteria2")
            .put("name", proxy["name"]?.toString().orEmpty())
            .put("raw", toJson(proxy).toString())
            .put("host", host)
            .put("server", host)
            .put("port", port)
            .put("password", proxy["password"].toString())
            .put("sni", proxy["sni"]?.toString().takeUnless { it.isNullOrEmpty() } ?: proxy["servername"]?.toString().takeUnless { it.isNullOrEmpty() } ?: host)
            .put("insecure", proxy["skip-cert-verify"] == true || proxy["insecure"] == true)
            .put("udp", proxy["udp"] != false)
        if (obfsType.isNotEmpty()) {
            node.put("obfs", obfsType)
        }
        if (!obfsPassword.isNullOrEmpty()) {
            node.put("obfs-password", obfsPassword)
        }
        val down = proxy["down"] ?: proxy["download"] ?: proxy["down_mbps"] ?: proxy["down-mbps"]
        if (down != null) {
            node.put("down", down.toString().toLong())
        }
        return node
    }

    private fun toJson(value: Any?): Any = when (value) {
        is Map<*, *> -> JSONObject().also { output ->
            for ((key, item) in value) {
                output.put(key.toString(), toJson(item))
            }
        }
        is List<*> -> JSONArray().also { output ->
            for (item in value) {
                output.put(toJson(item))
            }
        }
        null -> JSONObject.NULL
        else -> value
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        if (stream == null) {
            return ""
        }
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            val builder = StringBuilder()
            var line = reader.readLine()
            while (line != null) {
                builder.append(line).append('\n')
                line = reader.readLine()
            }
            builder.toString().trim()
        }
    }

    private fun parseJson(text: String): Any {
        if (text.isEmpty()) {
            return ""
        }
        return try {
            JSONTokener(text).nextValue() ?: ""
        } catch (_: JSONException) {
            text
        }
    }

    private fun normalizeBaseUrl(baseUrl: String): String {
        val value = baseUrl.trim().trimEnd('/')
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value
        }
        return "https://$value"
    }

    private fun queryString(query: Map<String, String>): String {
        if (query.isEmpty()) {
            return ""
        }
        return query.entries.joinToString(prefix = "?", separator = "&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
    }

    private fun optionalQuery(params: JSONObject, vararg keys: String): Map<String, String> =
        keys.mapNotNull { key -> params.optString(key).takeIf { it.isNotEmpty() }?.let { key to it } }.toMap()

    private fun requiredQuery(params: JSONObject, vararg keys: String): Map<String, String> =
        keys.associateWith { key -> params.getString(key) }

    private fun requireAuth(authData: String) {
        if (authData.isEmpty()) {
            throw IllegalStateException("auth_data is required for this Xboard action")
        }
    }
}
