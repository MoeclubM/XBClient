package moe.telecom.xbclient

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml
import java.util.Locale
import java.util.concurrent.TimeUnit

object Subscriptions {
    private const val SUBSCRIPTION_USER_AGENT = "Aerion"
    private const val SUBSCRIPTION_NODE_TYPES = "anytls,hysteria,trojan,vless,vmess,mieru,sudoku,flux,naive,shadowsocks,tuic,http,socks5,direct,block"
    private val httpClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    fun fetch(subscribeUrl: String, flag: String, authorization: String = ""): JSONObject {
        val singBox = flag.equals("sing-box", ignoreCase = true)
        val original = android.net.Uri.parse(subscribeUrl)
        val builder = original.buildUpon().clearQuery()
        for (name in original.queryParameterNames) {
            if (name != "flag" && name != "types") {
                for (value in original.getQueryParameters(name)) builder.appendQueryParameter(name, value)
            }
        }
        val url = builder
            .appendQueryParameter("types", SUBSCRIPTION_NODE_TYPES)
            .appendQueryParameter("flag", flag)
            .build()
            .toString()
        val response = httpClient.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", if (singBox) "sing-box" else SUBSCRIPTION_USER_AGENT)
                .apply { if (authorization.isNotEmpty()) header("Authorization", authorization) }
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
        val subscriptionHttpUrl = response.request.url
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
        val yaml = Yaml()
        val parsedRoot = yaml.load<Any>(text) as? Map<*, *>
            ?: throw IllegalStateException("clash-meta routing YAML root must be a mapping")
        val root = linkedMapOf<Any?, Any?>()
        for ((key, value) in parsedRoot) {
            root[key] = value
        }
        var rules = (root["rules"] as? List<*>
            ?: throw IllegalStateException("clash-meta routing YAML missing rules array"))
            .map { it.toString() }
        val proxyGroupCount = when (val proxyGroups = root["proxy-groups"]) {
            null -> 0
            is List<*> -> proxyGroups.size
            else -> throw IllegalStateException("clash-meta routing YAML field proxy-groups must be an array")
        }
        val ruleProvidersKey = when {
            root.containsKey("rule-providers") -> "rule-providers"
            root.containsKey("rule_providers") -> "rule_providers"
            else -> null
        }
        val strippedProviders = linkedSetOf<String>()
        val ruleProviderCount = if (ruleProvidersKey == null) {
            0
        } else {
            val providers = root[ruleProvidersKey] as? Map<*, *>
                ?: throw IllegalStateException("clash-meta routing YAML field $ruleProvidersKey must be a mapping")
            val expanded = linkedMapOf<String, Any>()
            for ((rawName, rawProvider) in providers) {
                val name = rawName.toString()
                val provider = rawProvider as? Map<*, *>
                    ?: throw IllegalStateException("clash-meta rule-provider $name must be a mapping")
                val behavior = provider["behavior"]?.toString()?.trim()
                    ?: throw IllegalStateException("clash-meta rule-provider $name missing behavior")
                val type = provider["type"]?.toString()?.trim()?.lowercase(Locale.US)
                    ?: throw IllegalStateException("clash-meta rule-provider $name missing type")
                val format = provider["format"]?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
                val rawUrl = provider["url"]?.toString()?.trim().orEmpty()
                val isMrs = format == "mrs" || rawUrl.substringBefore('?').lowercase(Locale.US).endsWith(".mrs")
                when (type) {
                    "inline" -> {
                        mihomoRuleProviderPayload(name, provider["payload"])
                        expanded[name] = provider
                    }
                    "http" -> {
                        if (isMrs) {
                            strippedProviders.add(name)
                        } else {
                            val payload = fetchMihomoRuleProviderPayload(name, provider, subscriptionHttpUrl)
                            expanded[name] = linkedMapOf(
                                "type" to "inline",
                                "behavior" to behavior,
                                "payload" to payload
                            )
                        }
                    }
                    else -> throw IllegalStateException("clash-meta rule-provider $name type $type is not supported")
                }
            }
            if (expanded.isEmpty()) {
                root.remove(ruleProvidersKey)
            } else {
                root[ruleProvidersKey] = expanded
            }
            expanded.size
        }
        if (strippedProviders.isNotEmpty()) {
            rules = rewriteStrippedRuleSets(rules, strippedProviders)
            root["rules"] = rules
        }
        val nodes = root["proxies"]?.let { value ->
            val proxies = value as List<*>
            JSONArray().also { nodes ->
                for (raw in proxies) {
                    val proxy = JSONObject(raw as Map<*, *>)
                    proxy.put("host", proxy.opt("server"))
                    if (proxy.has("skip-cert-verify")) proxy.put("insecure", proxy.getBoolean("skip-cert-verify"))
                    nodes.put(proxy)
                }
            }
        }
        val rewritten = ruleProviderCount > 0 || strippedProviders.isNotEmpty()
        val routeConfigYaml = if (rules.isEmpty()) JSONObject.NULL else if (rewritten) yaml.dump(root) else text
        return JSONObject()
            .put("ok", true)
            .put("status", status)
            .put("nodes", nodes)
            .put("format", if (flag.equals("aerion", ignoreCase = true)) "aerion" else "clashmeta")
            .put("flag", flag)
            .put("subscription_userinfo", subscriptionUserInfo)
            .put("routing", JSONObject()
                .put("has_rules", rules.isNotEmpty())
                .put("rule_count", rules.size)
                .put("proxy_group_count", proxyGroupCount)
                .put("rule_provider_count", ruleProviderCount)
                .put("rules_preview", JSONArray().also { array -> rules.take(20).forEach { array.put(it) } })
                .put("route_config_yaml", routeConfigYaml)
            )
    }

    private fun fetchMihomoRuleProviderPayload(name: String, provider: Map<*, *>, baseUrl: okhttp3.HttpUrl): List<String> {
        val rawUrl = provider["url"]?.toString()?.trim()
            ?: throw IllegalStateException("clash-meta http rule-provider $name missing url")
        val url = baseUrl.resolve(rawUrl)
            ?: throw IllegalStateException("clash-meta http rule-provider $name url is invalid: $rawUrl")
        val response = httpClient.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", SUBSCRIPTION_USER_AGENT)
                .header("Accept", "text/yaml, application/yaml, text/plain, */*")
                .build()
        ).execute()
        val status = response.code
        val body = response.body ?: throw IllegalStateException("clash-meta rule-provider $name response body is required")
        val text = body.string().trim()
        response.close()
        if (status !in 200..299) {
            throw IllegalStateException("clash-meta rule-provider $name download failed: HTTP $status")
        }
        return when (provider["format"]?.toString()?.trim()?.lowercase(Locale.US).takeUnless { it.isNullOrEmpty() } ?: "yaml") {
            "yaml", "yml" -> {
                val file = Yaml().load<Any>(text) as? Map<*, *>
                    ?: throw IllegalStateException("clash-meta rule-provider $name YAML root must be a mapping")
                mihomoRuleProviderPayload(name, file["payload"])
            }
            "text" -> text.lines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
            "mrs" -> throw IllegalStateException("clash-meta rule-provider $name MRS format is not supported")
            else -> throw IllegalStateException("clash-meta rule-provider $name format ${provider["format"]} is not supported")
        }
    }

    private fun mihomoRuleProviderPayload(name: String, payload: Any?): List<String> {
        val lines = payload as? List<*>
            ?: throw IllegalStateException("clash-meta rule-provider $name payload must be an array")
        val cleaned = lines.map { it.toString().trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) {
            throw IllegalStateException("clash-meta rule-provider $name payload is empty")
        }
        return cleaned
    }

    private fun rewriteStrippedRuleSets(rules: List<String>, stripped: Set<String>): List<String> {
        var hasCnDomain = rules.any { raw ->
            val upper = raw.trim().uppercase(Locale.US)
            upper.startsWith("DOMAIN-SUFFIX,CN,") || upper.startsWith("GEOSITE,CN,")
        }
        var hasGeoipCn = rules.any { raw ->
            raw.trim().uppercase(Locale.US).startsWith("GEOIP,CN,")
        }
        val out = ArrayList<String>(rules.size)
        for (raw in rules) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(',').map { it.trim() }
            if (parts.size >= 3 && parts[0].equals("RULE-SET", ignoreCase = true)) {
                val setName = parts[1]
                val target = parts[2]
                if (setName in stripped) {
                    val lower = setName.lowercase(Locale.US)
                    if (!hasCnDomain && (lower == "cn-domain" || (lower.contains("cn") && lower.contains("domain")))) {
                        out.add("DOMAIN-SUFFIX,cn,$target")
                        hasCnDomain = true
                    } else if (!hasGeoipCn && (lower == "cn-ip" || (lower.contains("cn") && lower.contains("ip")))) {
                        out.add("GEOIP,CN,$target,no-resolve")
                        hasGeoipCn = true
                    }
                    continue
                }
            }
            out.add(trimmed)
        }
        return out
    }
}
