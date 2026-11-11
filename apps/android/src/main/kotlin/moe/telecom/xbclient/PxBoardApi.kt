package moe.telecom.xbclient

import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PxBoardRpcException(
    val code: String,
    message: String,
    val status: Int,
) : IllegalStateException(message)

data class PxBoardBatchCall(
    val key: String,
    val service: String,
    val method: String,
    val input: JSONObject = JSONObject(),
)

data class PxBoardBatchResult(
    val status: Int,
    val data: Any?,
    val errorCode: String = "",
    val errorMessage: String = "",
)

object PxBoardApi {
    val enabled get() = BuildConfig.PANEL_TYPE == "pxboard"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    fun baseUrl(): String = BuildConfig.DEFAULT_API_URL.trim().trimEnd('/')

    fun publicUrl(path: String): String {
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            return path
        }
        val base = baseUrl()
        return if (path.startsWith("/")) base + path else "$base/$path"
    }

    fun authorization(authData: String): String =
        "Bearer " + authData.removePrefix("PxBoard ").trim()

    fun rpc(
        service: String,
        method: String,
        authData: String = "",
        body: JSONObject = JSONObject(),
    ): Any? {
        val encodedService = Uri.encode(service)
        val reqBuilder = Request.Builder()
            .url("${baseUrl()}/rpc/v1/$encodedService/$method")
            .header("User-Agent", BuildConfig.USER_AGENT)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
        if (authData.isNotEmpty()) {
            reqBuilder.header("Authorization", authorization(authData))
        }
        client.newCall(reqBuilder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val envelope = if (raw.isNotEmpty()) JSONObject(raw) else JSONObject()
            if (!response.isSuccessful) {
                val errObj = envelope.optJSONObject("error")
                val code = errObj?.optString("code").orEmpty()
                val msg = errObj?.optString("message")?.takeIf { it.isNotEmpty() } ?: "HTTP ${response.code}"
                throw PxBoardRpcException(code, msg, response.code)
            }
            if (!envelope.has("data")) {
                throw PxBoardRpcException("invalid_response", "Invalid RPC response envelope", response.code)
            }
            val value = envelope.opt("data")
            return if (value == JSONObject.NULL) null else value
        }
    }

    fun batch(
        calls: List<PxBoardBatchCall>,
        authData: String = "",
    ): Map<String, PxBoardBatchResult> {
        val array = JSONArray()
        for (call in calls) {
            array.put(
                JSONObject()
                    .put("key", call.key)
                    .put("service", call.service)
                    .put("method", call.method)
                    .put("input", call.input),
            )
        }
        val reqBuilder = Request.Builder()
            .url("${baseUrl()}/rpc/v1/\$batch")
            .header("User-Agent", BuildConfig.USER_AGENT)
            .header("Accept", "application/json")
            .post(JSONObject().put("calls", array).toString().toRequestBody(jsonMediaType))
        if (authData.isNotEmpty()) {
            reqBuilder.header("Authorization", authorization(authData))
        }
        client.newCall(reqBuilder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val envelope = if (raw.isNotEmpty()) JSONObject(raw) else JSONObject()
            if (!response.isSuccessful) {
                val errObj = envelope.optJSONObject("error")
                val code = errObj?.optString("code").orEmpty()
                val msg = errObj?.optString("message")?.takeIf { it.isNotEmpty() } ?: "HTTP ${response.code}"
                throw PxBoardRpcException(code, msg, response.code)
            }
            val dataObj = envelope.optJSONObject("data")
                ?: throw PxBoardRpcException("invalid_response", "Invalid batch response envelope", response.code)
            val out = LinkedHashMap<String, PxBoardBatchResult>()
            val keys = dataObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = dataObj.optJSONObject(key) ?: continue
                val errObj = item.optJSONObject("error")
                val rawData = item.opt("data")
                out[key] = PxBoardBatchResult(
                    status = item.optInt("status", 500),
                    data = if (rawData == JSONObject.NULL) null else rawData,
                    errorCode = errObj?.optString("code").orEmpty(),
                    errorMessage = errObj?.optString("message").orEmpty(),
                )
            }
            return out
        }
    }
}
