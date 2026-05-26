package moe.telecom.xbclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import moe.telecom.xbclient.tauri.mobile.BuildConfig
import moe.telecom.xbclient.tauri.mobile.R

class XbClientVpnService : VpnService() {
    private val vpnDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(SupervisorJob() + vpnDispatcher)
    private var vpnSessionId = 0L
    private var currentNodeJson = ""
    private var currentNodesJson = ""
    private var currentNodeIndex = 0
    private var currentExcludedApps = ""
    private var currentAllowedApps = ""
    private var currentNodeDns = DEFAULT_NODE_DNS
    private var currentOverseasDns = DEFAULT_OVERSEAS_DNS
    private var currentDirectDns = DEFAULT_DIRECT_DNS
    private var currentDnsMode = DNS_MODE_OVER_TCP
    private var currentVirtualDnsPool = DEFAULT_VIRTUAL_DNS_POOL
    private var currentIpv6Enabled = true
    private var tunInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        activeService = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    stopCurrentVpn()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                startForegroundNotification(getString(R.string.vpn_notification_reconnecting))
                val nodeJson = currentNodeJson
                val excludedApps = currentExcludedApps
                val allowedApps = currentAllowedApps
                val nodeDns = currentNodeDns
                val overseasDns = currentOverseasDns
                val directDns = currentDirectDns
                val dnsMode = currentDnsMode
                val virtualDnsPool = currentVirtualDnsPool
                val ipv6Enabled = currentIpv6Enabled
                serviceScope.launch {
                    try {
                        startVpn(nodeJson, excludedApps, allowedApps, nodeDns, overseasDns, directDns, dnsMode, virtualDnsPool, ipv6Enabled)
                        startForegroundNotification(getString(R.string.vpn_notification_current_node, currentNodeName()))
                        publishVpnState(true)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Log.e("XBClient", "reconnect VPN failed", error)
                        stopCurrentVpn(getString(R.string.vpn_notification_reconnect_failed, errorMessage(error)))
                        stopSelf()
                    }
                }
                return START_STICKY
            }
            ACTION_NEXT_NODE -> {
                startForegroundNotification(getString(R.string.vpn_notification_switching))
                serviceScope.launch {
                    try {
                        val nodes = JSONArray(currentNodesJson)
                        if (nodes.length() == 0) {
                            throw IllegalStateException("node list is empty")
                        }
                        currentNodeIndex = (currentNodeIndex + 1) % nodes.length()
                        currentNodeJson = nodes.getJSONObject(currentNodeIndex).toString()
                        startVpn(currentNodeJson, currentExcludedApps, currentAllowedApps, currentNodeDns, currentOverseasDns, currentDirectDns, currentDnsMode, currentVirtualDnsPool, currentIpv6Enabled)
                        startForegroundNotification(getString(R.string.vpn_notification_current_node, currentNodeName()))
                        publishVpnState(true)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Log.e("XBClient", "switch VPN node failed", error)
                        stopCurrentVpn(getString(R.string.vpn_notification_switch_failed, errorMessage(error)))
                        stopSelf()
                    }
                }
                return START_STICKY
            }
            ACTION_START -> {
                val nodeJson = intent.getStringExtra(EXTRA_NODE).orEmpty()
                val nodesJson = intent.getStringExtra(EXTRA_NODES).orEmpty()
                val nodeIndex = intent.getIntExtra(EXTRA_NODE_INDEX, 0)
                val excludedApps = intent.getStringExtra(EXTRA_EXCLUDED_APPS).orEmpty()
                val allowedApps = intent.getStringExtra(EXTRA_ALLOWED_APPS).orEmpty()
                val nodeDns = intent.getStringExtra(EXTRA_NODE_DNS) ?: DEFAULT_NODE_DNS
                val overseasDns = intent.getStringExtra(EXTRA_OVERSEAS_DNS) ?: DEFAULT_OVERSEAS_DNS
                val directDns = intent.getStringExtra(EXTRA_DIRECT_DNS) ?: DEFAULT_DIRECT_DNS
                val dnsMode = intent.getStringExtra(EXTRA_DNS_MODE) ?: DNS_MODE_OVER_TCP
                val virtualDnsPool = intent.getStringExtra(EXTRA_VIRTUAL_DNS_POOL) ?: DEFAULT_VIRTUAL_DNS_POOL
                val ipv6Enabled = intent.getBooleanExtra(EXTRA_IPV6_ENABLED, true)
                currentNodeJson = nodeJson
                currentNodesJson = nodesJson
                currentNodeIndex = nodeIndex
                currentExcludedApps = excludedApps
                currentAllowedApps = allowedApps
                currentNodeDns = nodeDns
                currentOverseasDns = overseasDns
                currentDirectDns = directDns
                currentDnsMode = dnsMode
                currentVirtualDnsPool = virtualDnsPool
                currentIpv6Enabled = ipv6Enabled
                startForegroundNotification(getString(R.string.vpn_notification_connecting))
                serviceScope.launch {
                    try {
                        startVpn(nodeJson, excludedApps, allowedApps, nodeDns, overseasDns, directDns, dnsMode, virtualDnsPool, ipv6Enabled)
                        startForegroundNotification(getString(R.string.vpn_notification_current_node, currentNodeName()))
                        publishVpnState(true)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Log.e("XBClient", "start VPN failed", error)
                        stopCurrentVpn(getString(R.string.vpn_notification_start_failed, errorMessage(error)))
                        stopSelf()
                    }
                }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        vpnDispatcher.close()
        stopCurrentVpn()
        if (activeService === this) {
            activeService = null
        }
        super.onDestroy()
    }

    private fun resolveNodeHost(dns: String, host: String): String {
        if (host.matches(Regex("^[0-9.]+$")) || host.contains(":")) {
            return host
        }
        val resolver = dns.trim()
        if (!resolver.startsWith("http://") && !resolver.startsWith("https://")) {
            throw IllegalStateException("节点 DNS 必须是 DoH 地址。")
        }
        val userAgent = BuildConfig.USER_AGENT
        for (type in arrayOf("A", "AAAA")) {
            val url = android.net.Uri.parse(resolver)
                .buildUpon()
                .appendQueryParameter("name", host)
                .appendQueryParameter("type", type)
                .build()
                .toString()
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/dns-json, application/json")
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?.trim()
                .orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("节点 DNS 请求失败：HTTP $status")
            }
            val body = JSONObject(text)
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

    private fun dnsAddressForVpn(value: String): String {
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

    private fun startVpn(nodeJson: String?, excludedApps: String?, allowedApps: String?, nodeDns: String, overseasDns: String, directDns: String, dnsMode: String, virtualDnsPool: String, ipv6Enabled: Boolean) {
        stopNativeVpn()
        val dnsAddress = dnsAddressForVpn(if (dnsMode == DNS_MODE_DIRECT) directDns else overseasDns)
        val appName = packageManager.getApplicationLabel(applicationInfo).toString()
        val builder = Builder()
            .setSession(appName)
            .setMtu(1500)
            .setBlocking(false)
            .addAddress(PRIVATE_IPV4_CLIENT, 30)
            .addRoute("0.0.0.0", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        if (ipv6Enabled) {
            builder.addAddress(PRIVATE_IPV6_CLIENT, 126)
                .addRoute("::", 0)
        }
        val systemDns = if (dnsMode == DNS_MODE_DIRECT) dnsAddress else PRIVATE_IPV4_DNS
        builder.addDnsServer(systemDns)

        if (!allowedApps.isNullOrBlank()) {
            if (!excludedApps.isNullOrBlank()) {
                throw IllegalStateException("Allowed applications and disallowed applications are mutually exclusive")
            }
            for (packageName in allowedApps.split(Regex("[,;\\s]+"))) {
                if (packageName.isNotEmpty()) {
                    builder.addAllowedApplication(packageName)
                }
            }
        } else {
            builder.addDisallowedApplication(packageName)
            if (!excludedApps.isNullOrBlank()) {
                for (packageName in excludedApps.split(Regex("[,;\\s]+"))) {
                    if (packageName.isNotEmpty()) {
                        builder.addDisallowedApplication(packageName)
                    }
                }
            }
        }

        val node = JSONObject(nodeJson ?: throw IllegalStateException("node_json is required"))
        val originalHost = node.getString("host")
        val resolvedHost = resolveNodeHost(nodeDns, originalHost)
        if (resolvedHost != originalHost && node.optString("sni").isEmpty()) {
            node.put("sni", originalHost)
        }
        node.put("host", resolvedHost)
        if (node.has("server")) {
            node.put("server", resolvedHost)
        }
        if (node.has("address")) {
            node.put("address", resolvedHost)
        }
        currentNodeJson = node.toString()
        val tun: ParcelFileDescriptor = builder.establish() ?: throw IllegalStateException(getString(R.string.vpn_permission_denied))
        tunInterface = tun
        val request = JSONObject()
            .put("node", node)
            .put("tun_fd", tun.fd)
            .put("mtu", 1500)
            .put("dns", dnsMode)
            .put("dns_addr", dnsAddress)
            .put("virtual_dns_pool", virtualDnsPool)
            .put("ipv6", ipv6Enabled)
        val result = JSONObject(AerionCore.startVpn(request.toString()))
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(result.toString())
        }
        vpnSessionId = result.getLong("session_id")
        Log.i("XBClient", "VPN started: $result")
    }

    private fun stopCurrentVpn(error: String = "") {
        stopNativeVpn()
        publishVpnState(false, error)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun stopNativeVpn() {
        if (vpnSessionId != 0L) {
            try {
                val result = AerionCore.stopVpn(vpnSessionId)
                Log.i("XBClient", "VPN stopped: $result")
            } catch (error: Throwable) {
                Log.e("XBClient", "stop VPN failed", error)
            }
            vpnSessionId = 0L
        }
        tunInterface?.close()
        tunInterface = null
    }

    private fun startForegroundNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_channel, packageManager.getApplicationLabel(applicationInfo).toString()),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
        val activityIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val pendingIntent = PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title, packageManager.getApplicationLabel(applicationInfo).toString()))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_next), getString(R.string.vpn_action_switch_node), selectNodeIntent()).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_popup_sync), getString(R.string.vpn_action_reconnect), serviceIntent(ACTION_RECONNECT, 2)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), getString(R.string.vpn_action_stop), serviceIntent(ACTION_STOP, 3)).build())
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun selectNodeIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            action = ACTION_SELECT_NODE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: Intent()
        return PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, XbClientVpnService::class.java)
        intent.action = action
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun currentNodeName(): String {
        val node = JSONObject(currentNodeJson)
        val host = node.optString("host")
        val name = node.optString("name").trim()
        if (name.isEmpty() || name == host || name == "$host:${node.optInt("port")}" || host.isNotEmpty() && name.contains(host)) {
            return getString(R.string.node_default_name, currentNodeIndex + 1)
        }
        return name
    }

    private fun publishVpnState(running: Boolean, error: String = "") {
        val editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
        editor.putBoolean("vpn_running", running)
        if (running) {
            editor.putInt("vpn_node_index", currentNodeIndex)
        } else {
            editor.putInt("vpn_node_index", -1)
        }
        editor.commit()
        val intent = Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_RUNNING, running)
        if (error.isNotEmpty()) {
            intent.putExtra(EXTRA_ERROR, error)
        }
        if (running) {
            intent.putExtra(EXTRA_NODE_INDEX, currentNodeIndex)
            intent.putExtra(EXTRA_NODE_NAME, currentNodeName())
        }
        sendBroadcast(intent)
    }

    private fun errorMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    companion object {
        const val ACTION_START = "moe.telecom.xbclient.action.START_VPN"
        const val ACTION_STOP = "moe.telecom.xbclient.action.STOP_VPN"
        const val ACTION_RECONNECT = "moe.telecom.xbclient.action.RECONNECT_VPN"
        const val ACTION_NEXT_NODE = "moe.telecom.xbclient.action.NEXT_NODE"
        const val ACTION_STATE = "moe.telecom.xbclient.action.VPN_STATE"
        const val ACTION_SELECT_NODE = "moe.telecom.xbclient.action.SELECT_NODE"
        const val EXTRA_NODE = "node_json"
        const val EXTRA_NODES = "nodes_json"
        const val EXTRA_NODE_INDEX = "node_index"
        const val EXTRA_NODE_NAME = "node_name"
        const val EXTRA_EXCLUDED_APPS = "excluded_apps"
        const val EXTRA_ALLOWED_APPS = "allowed_apps"
        const val EXTRA_NODE_DNS = "node_dns"
        const val EXTRA_OVERSEAS_DNS = "overseas_dns"
        const val EXTRA_DIRECT_DNS = "direct_dns"
        const val EXTRA_DNS_MODE = "dns_mode"
        const val EXTRA_VIRTUAL_DNS_POOL = "virtual_dns_pool"
        const val EXTRA_IPV6_ENABLED = "ipv6_enabled"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_ERROR = "error"
        private const val DEFAULT_NODE_DNS = "https://dns.alidns.com/resolve"
        private const val DEFAULT_OVERSEAS_DNS = "https://cloudflare-dns.com/dns-query"
        private const val DEFAULT_DIRECT_DNS = "223.5.5.5"
        private const val DEFAULT_VIRTUAL_DNS_POOL = "198.18.0.0/15"
        private const val DNS_MODE_OVER_TCP = "over_tcp"
        private const val DNS_MODE_DIRECT = "direct"
        private const val PRIVATE_IPV4_CLIENT = "172.19.0.1"
        private const val PRIVATE_IPV4_DNS = "172.19.0.2"
        private const val PRIVATE_IPV6_CLIENT = "fdfe:dcba:9876::1"
        private const val CHANNEL_ID = "xbclient_vpn"
        private const val NOTIFICATION_ID = 1
        private const val PREFS = "xbclient"
        @Volatile
        private var activeService: XbClientVpnService? = null

        @JvmStatic
        fun protectSocketFd(fd: Int): Boolean {
            val service = activeService ?: return false
            return service.protect(fd)
        }

        @JvmStatic
        fun onLog(level: String, message: String) {
            Log.d("AerionLog", "[$level] $message")
        }

        @JvmStatic
        fun onEvent(eventJson: String) {
            Log.d("AerionEvent", eventJson)
        }
    }
}
