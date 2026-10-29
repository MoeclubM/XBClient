package moe.telecom.xbclient

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object PxBoardApi {
    val enabled get() = BuildConfig.PANEL_TYPE == "pxboard"
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    fun request(path: String, authData: String = "", body: JSONObject? = null): Any {
        val request = Request.Builder()
            .url(BuildConfig.DEFAULT_API_URL.trimEnd('/') + "/api/v1" + path)
            .header("User-Agent", BuildConfig.USER_AGENT)
            .header("Accept", "application/json")
        if (authData.isNotEmpty()) request.header("Authorization", authorization(authData))
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(request.build()).execute().use { response ->
            val envelope = JSONObject(response.body!!.string())
            if (!response.isSuccessful) throw IllegalStateException(envelope.getString("message"))
            return envelope.get("data")
        }
    }
    fun authorization(authData: String) = "Bearer " + authData.removePrefix("PxBoard ")
    fun configurationUrl() = BuildConfig.DEFAULT_API_URL.trimEnd('/') + "/api/v1/extensions/subscriptions/configuration?format=clash"
}
