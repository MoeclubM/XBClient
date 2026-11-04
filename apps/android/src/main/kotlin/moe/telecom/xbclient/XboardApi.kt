package moe.telecom.xbclient

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object XboardApi {
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
            "user_info" -> getAuth(normalizedBaseUrl, "/api/v1/user/info", authData, emptyMap())
            "user_subscribe" -> getAuth(normalizedBaseUrl, "/api/v1/user/getSubscribe", authData, emptyMap())
            "guest_config" -> requestJson("GET", normalizedBaseUrl, "/api/v1/guest/comm/config", "", emptyMap(), null)
            "change_password" -> postAuth(normalizedBaseUrl, "/api/v1/user/changePassword", authData, params)
            "transfer" -> postAuth(normalizedBaseUrl, "/api/v1/user/transfer", authData, params)
            "plan_fetch" -> getAuth(normalizedBaseUrl, "/api/v1/user/plan/fetch", authData, emptyMap())
            "order_save" -> postAuth(normalizedBaseUrl, "/api/v1/user/order/save", authData, params)
            "order_checkout" -> postAuth(normalizedBaseUrl, "/api/v1/user/order/checkout", authData, params)
            "oauth_bindings" -> getAuth(normalizedBaseUrl, "/api/v1/user/oauth/bindings", authData, emptyMap())
            "oauth_bind_prepare" -> requestJson("POST", normalizedBaseUrl, "/api/v1/user/oauth/${params.getString("driver")}/bind", authData, optionalQuery(params, "redirect", "client", "app_scheme"), JSONObject())
            "oauth_unbind" -> postAuth(normalizedBaseUrl, "/api/v1/user/oauth/${params.getString("driver")}/unbind", authData, JSONObject())
            "gift_card_check" -> postAuth(normalizedBaseUrl, "/api/v1/user/gift-card/check", authData, params)
            "gift_card_redeem" -> postAuth(normalizedBaseUrl, "/api/v1/user/gift-card/redeem", authData, params)
            "gift_card_history" -> getAuth(normalizedBaseUrl, "/api/v1/user/gift-card/history", authData, optionalQuery(params, "page", "per_page"))
            "invite_fetch" -> getAuth(normalizedBaseUrl, "/api/v1/user/invite/fetch", authData, emptyMap())
            "invite_save" -> getAuth(normalizedBaseUrl, "/api/v1/user/invite/save", authData, emptyMap())
            "invite_details" -> getAuth(normalizedBaseUrl, "/api/v1/user/invite/details", authData, emptyMap())
            "tickets" -> getAuth(normalizedBaseUrl, "/api/v1/user/ticket/fetch", authData, optionalQuery(params, "id"))
            "ticket_save" -> postAuth(normalizedBaseUrl, "/api/v1/user/ticket/save", authData, params)
            "ticket_reply" -> postAuth(normalizedBaseUrl, "/api/v1/user/ticket/reply", authData, params)
            "ticket_close" -> postAuth(normalizedBaseUrl, "/api/v1/user/ticket/close", authData, params)
            "notices" -> getAuth(normalizedBaseUrl, "/api/v1/user/notice/fetch", authData, emptyMap())
            "traffic_logs" -> getAuth(normalizedBaseUrl, "/api/v1/user/stat/getTrafficLog", authData, emptyMap())
            "user_config" -> getAuth(normalizedBaseUrl, "/api/v1/user/comm/config", authData, emptyMap())
            // Xboard 插件 API
            "admob_reward_config" -> getAuth(normalizedBaseUrl, "/api/v1/admob/user/config", authData, emptyMap())
            "xbclient_plan_payment" -> postAuth(normalizedBaseUrl, "/api/v1/admob/user/plan-payment", authData, params)
            "xbclient_reward_history" -> getAuth(normalizedBaseUrl, "/api/v1/admob/user/reward-history", authData, emptyMap())
            "xbclient_reward_pending" -> postAuth(normalizedBaseUrl, "/api/v1/admob/user/reward-pending", authData, params)
            "xbclient_nodes" -> getAuth(normalizedBaseUrl, "/api/v1/admob/user/nodes", authData, emptyMap())

            else -> throw IllegalArgumentException("unsupported Xboard action: $action")
        }
    }


    fun resolveNodeHost(dns: String, host: String): String {
        val nodeHost = normalizeNodeHost(host)
        if (isIpLiteral(nodeHost)) {
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
                setRequestProperty("Accept", "application/dns-json")
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

    private fun queryDohFamily(resolver: String, host: String, recordType: String): List<String> {
        val url = Uri.parse(resolver)
            .buildUpon()
            .appendQueryParameter("name", host)
            .appendQueryParameter("type", recordType)
            .build()
            .toString()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/dns-json")
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
            null, JSONObject.NULL -> return emptyList()
            is JSONArray -> value
            else -> throw IllegalStateException("节点 DNS 响应 Answer 必须是数组。")
        }
        val out = ArrayList<String>()
        for (index in 0 until answers.length()) {
            val data = answers.getJSONObject(index).optString("data").trim()
            val valid = if (recordType == "A") {
                data.matches(Regex("^[0-9.]+$"))
            } else {
                data.matches(Regex("^[0-9A-Fa-f:.]+$")) && data.contains(":")
            }
            if (valid && !out.contains(data)) {
                out.add(data)
            }
        }
        return out
    }

    private fun channelEffectiveHost(ch: JSONObject, mainHost: String): String =
        listOf("server", "connect_host", "connect-host", "connectHost", "host")
            .firstNotNullOfOrNull { key -> ch.optString(key).trim().takeIf { it.isNotEmpty() } }
            ?.let(::normalizeNodeHost)
            ?: mainHost

    fun resolveFluxNode(dns: String, node: JSONObject) {
        val mainHost = normalizeNodeHost(node.optString("host").ifBlank { node.optString("server") })
        val channelArrays = listOfNotNull(
            node.optJSONArray("channels"),
            node.optJSONObject("flux")?.optJSONArray("channels")
        )
        val domains = LinkedHashSet<String>()
        if (mainHost.isNotEmpty() && !isIpLiteral(mainHost)) {
            domains.add(mainHost)
        }
        for (arr in channelArrays) {
            for (index in 0 until arr.length()) {
                val ch = arr.optJSONObject(index) ?: continue
                if (!ch.optBoolean("enabled", true)) continue
                val chHost = channelEffectiveHost(ch, mainHost)
                if (chHost.isNotEmpty() && !isIpLiteral(chHost)) {
                    domains.add(chHost)
                }
            }
        }
        if (domains.isEmpty()) {
            return
        }
        val resolver = dns.trim()
        if (!resolver.startsWith("http://") && !resolver.startsWith("https://")) {
            throw IllegalStateException("节点 DNS 必须是 DoH 地址。")
        }
        val pool = Executors.newFixedThreadPool((domains.size * 2).coerceIn(2, 8))
        val resolvedMap = LinkedHashMap<String, Pair<List<String>, List<String>>>()
        try {
            val futures = domains.associateWith { domain ->
                val v4Future = pool.submit(Callable { queryDohFamily(resolver, domain, "A") })
                val v6Future = pool.submit(Callable { queryDohFamily(resolver, domain, "AAAA") })
                v4Future to v6Future
            }
            for ((domain, pair) in futures) {
                val v4 = try {
                    pair.first.get()
                } catch (e: ExecutionException) {
                    throw (e.cause ?: e)
                }
                val v6 = try {
                    pair.second.get()
                } catch (e: ExecutionException) {
                    throw (e.cause ?: e)
                }
                if (v4.isEmpty() && v6.isEmpty()) {
                    throw IllegalStateException("节点 DNS 无可用 A/AAAA 记录：$domain")
                }
                resolvedMap[domain] = v4 to v6
            }
        } finally {
            pool.shutdownNow()
        }
        resolvedMap[mainHost]?.let { (v4, v6) ->
            node.put("resolved_v4", JSONArray(v4))
            node.put("resolved_v6", JSONArray(v6))
        }
        for (arr in channelArrays) {
            for (index in 0 until arr.length()) {
                val ch = arr.optJSONObject(index) ?: continue
                if (!ch.optBoolean("enabled", true)) continue
                val chHost = channelEffectiveHost(ch, mainHost)
                resolvedMap[chHost]?.let { (v4, v6) ->
                    ch.put("resolved_v4", JSONArray(v4))
                    ch.put("resolved_v6", JSONArray(v6))
                }
            }
        }
    }

    fun dnsAddressForVpn(value: String): String {
        val dns = normalizeNodeHost(value)
        if (isIpLiteral(dns)) {
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
        if (path == "/api/v1/admob/user/nodes") {
            builder.header("X-XBClient-Protocols", "sudoku,flux")
        }
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
