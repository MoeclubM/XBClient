package moe.telecom.xbclient

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object XboardApi {
    private const val SUBSCRIPTION_NODE_TYPES =
        "anytls,hysteria,trojan,vless,vmess,mieru,sudoku,flux,naive,shadowsocks,tuic,http,socks5,direct,block"
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
            "order_cancel" -> postAuth(normalizedBaseUrl, "/api/v1/user/order/cancel", authData, params)
            "quick_login_url" -> postAuth(normalizedBaseUrl, "/api/v1/user/getQuickLoginUrl", authData, params)
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
            "xbclient_reward_history" -> getAuth(normalizedBaseUrl, "/api/v1/admob/user/reward-history", authData, emptyMap())
            "xbclient_reward_pending" -> postAuth(normalizedBaseUrl, "/api/v1/admob/user/reward-pending", authData, params)
            "xbclient_nodes" -> getAuth(normalizedBaseUrl, "/api/v1/admob/user/nodes", authData, emptyMap())

            else -> throw IllegalArgumentException("unsupported Xboard action: $action")
        }
    }

    fun fetchSubscriptionYaml(subscribeUrl: String): String {
        val parsed = Uri.parse(subscribeUrl.trim())
        val builder = parsed.buildUpon().clearQuery()
        for (name in parsed.queryParameterNames) {
            if (name != "flag" && name != "types") {
                for (v in parsed.getQueryParameters(name)) {
                    builder.appendQueryParameter(name, v)
                }
            }
        }
        builder.appendQueryParameter("types", SUBSCRIPTION_NODE_TYPES)
        builder.appendQueryParameter("flag", "aerion")
        val req = Request.Builder()
            .url(builder.build().toString())
            .header("User-Agent", userAgent)
            .header("Accept", "text/yaml, application/yaml, text/plain;q=0.9, */*;q=0.8")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty().trim()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            return body
        }
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
