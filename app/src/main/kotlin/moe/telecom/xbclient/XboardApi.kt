package moe.telecom.xbclient

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.yaml.snakeyaml.Yaml
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object XboardApi {
    private const val SUBSCRIPTION_USER_AGENT = "mihomo"
    private const val SUBSCRIPTION_NODE_TYPES = "anytls,hysteria,trojan,vless,vmess,mieru,naive,shadowsocks,tuic,http,socks5,direct,block"
    private val userAgent: String
        get() = BuildConfig.USER_AGENT
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30000, TimeUnit.MILLISECONDS)
        .readTimeout(30000, TimeUnit.MILLISECONDS)
        .build()
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
            "order_save" -> postAuth(normalizedBaseUrl, "/api/v1/user/order/save", authData, params)
            "order_checkout" -> postAuth(normalizedBaseUrl, "/api/v1/user/order/checkout", authData, params)
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
            // Xboard 插件 API
            "admob_reward_config" -> getAuth(normalizedBaseUrl, "/api/v1/admob/user/config", authData, emptyMap())
            "xbclient_plan_payment" -> postAuth(normalizedBaseUrl, "/api/v1/admob/user/plan-payment", authData, params)
            "xbclient_reward_history" -> getAuth(normalizedBaseUrl, "/api/v1/admob/user/reward-history", authData, emptyMap())
            "xbclient_reward_pending" -> postAuth(normalizedBaseUrl, "/api/v1/admob/user/reward-pending", authData, params)
            "xbclient_nodes" -> getAuth(normalizedBaseUrl, "/api/v1/admob/user/nodes", authData, emptyMap())

            // 非站点 API：订阅内容解析
            "anytls_nodes" -> fetchProxyNodes(params.getString("subscribe_url"), params.getString("flag"))
            else -> throw IllegalArgumentException("unsupported Xboard action: $action")
        }
    }


    fun resolveNodeHost(dns: String, host: String): String {
        val nodeHost = normalizeNodeHost(host)
        if (nodeHost.matches(Regex("^[0-9.]+$")) || nodeHost.matches(Regex("^[0-9A-Fa-f:.]+$")) && nodeHost.contains(":")) {
            return nodeHost
        }
        val resolver = dns.trim()
        if (!resolver.startsWith("http://") && !resolver.startsWith("https://")) {
            throw IllegalStateException("节点 DNS 必须是 DoH 地址。")
        }
        for (type in arrayOf("A", "AAAA")) {
            val url = Uri.parse(resolver)
                .buildUpon()
                .appendQueryParameter("name", nodeHost)
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
            val answers = when (val value = body.opt("Answer")) {
                null, JSONObject.NULL -> continue
                is JSONArray -> value
                else -> throw IllegalStateException("节点 DNS 响应 Answer 必须是数组。")
            }
            for (index in 0 until answers.length()) {
                val data = answers.getJSONObject(index).getString("data")
                if (data.matches(Regex("^[0-9.]+$")) || data.matches(Regex("^[0-9A-Fa-f:.]+$")) && data.contains(":")) {
                    return data
                }
            }
        }
        throw IllegalStateException("节点 DNS 无可用 A/AAAA 记录。")
    }

    fun dnsAddressForVpn(value: String): String {
        val dns = normalizeNodeHost(value)
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
        val builder = Request.Builder()
            .url(baseUrl + path + queryString(query))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
        if (authData.isNotEmpty()) {
            builder.header("Authorization", authData)
        }
        if (body != null) {
            builder.method(method, body.toString().toRequestBody(jsonMediaType))
        } else {
            builder.method(method, null)
        }
        val response = httpClient.newCall(builder.build()).execute()
        val status = response.code
        val body = response.body ?: throw IllegalStateException("HTTP response body is required")
        val text = body.string().trim()
        val parsedBody = parseJson(text)
        response.close()
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
        val singBox = flag.equals("sing-box", ignoreCase = true)
        val url = Uri.parse(subscribeUrl)
            .buildUpon()
            .appendQueryParameter("types", SUBSCRIPTION_NODE_TYPES)
            .appendQueryParameter("flag", flag)
            .build()
            .toString()
        val response = httpClient.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", if (singBox) "sing-box" else SUBSCRIPTION_USER_AGENT)
                .header("Accept", if (singBox) "application/json, text/plain, */*" else "text/yaml, application/yaml, text/plain, */*")
                .build()
        ).execute()
        val status = response.code
        val body = response.body ?: throw IllegalStateException("subscription response body is required")
        val text = body.string().trim()
        if (status !in 200..299) {
            response.close()
            return JSONObject()
                .put("ok", false)
                .put("status", status)
                .put("error", "HTTP $status")
                .put("body", text)
        }

        val subscriptionUserInfo = response.header("subscription-userinfo")
        response.close()
        if (singBox) {
            return JSONObject()
                .put("ok", true)
                .put("status", status)
                .put("format", "sing-box")
                .put("flag", flag)
                .put("subscription_userinfo", subscriptionUserInfo)
                .put("routing", JSONObject()
                    .put("has_rules", false)
                    .put("rule_count", 0)
                    .put("proxy_group_count", 0)
                    .put("rule_provider_count", 0)
                    .put("rules_preview", JSONArray())
                    .put("route_config_yaml", JSONObject.NULL)
                )
        }
        val root = Yaml().load<Any>(text) as Map<*, *>
        val rules = (root["rules"] as? List<*>
            ?: throw IllegalStateException("clash-meta routing YAML missing rules array"))
            .map { it.toString() }
        val proxyGroupCount = when (val proxyGroups = root["proxy-groups"]) {
            null -> 0
            is List<*> -> proxyGroups.size
            else -> throw IllegalStateException("clash-meta routing YAML field proxy-groups must be an array")
        }
        val ruleProviderCount = when (val ruleProviders = root["rule-providers"]) {
            null -> 0
            is Map<*, *> -> ruleProviders.size
            else -> throw IllegalStateException("clash-meta routing YAML field rule-providers must be an object")
        }
        return JSONObject()
            .put("ok", true)
            .put("status", status)
            .put("format", "clashmeta")
            .put("flag", flag)
            .put("subscription_userinfo", subscriptionUserInfo)
            .put("routing", JSONObject()
                .put("has_rules", rules.isNotEmpty())
                .put("rule_count", rules.size)
                .put("proxy_group_count", proxyGroupCount)
                .put("rule_provider_count", ruleProviderCount)
                .put("rules_preview", JSONArray().also { array -> rules.take(20).forEach { array.put(it) } })
                .put("route_config_yaml", if (rules.isEmpty()) JSONObject.NULL else text)
            )
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        if (stream == null) throw IllegalStateException("HTTP response stream is required")
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
        if (text.isEmpty()) throw IllegalStateException("JSON response body is empty")
        return JSONTokener(text).nextValue() ?: JSONObject.NULL
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
        keys.mapNotNull { key ->
            if (!params.has(key) || params.isNull(key)) {
                null
            } else {
                params.getString(key).takeIf { it.isNotEmpty() }?.let { key to it }
            }
        }.toMap()

    private fun requiredQuery(params: JSONObject, vararg keys: String): Map<String, String> =
        keys.associateWith { key -> params.getString(key) }

    private fun requireAuth(authData: String) {
        if (authData.isEmpty()) {
            throw IllegalStateException("auth_data is required for this Xboard action")
        }
    }
}
