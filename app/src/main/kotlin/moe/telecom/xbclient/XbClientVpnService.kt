package moe.telecom.xbclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.TrafficStats
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

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
    private var currentDnsMode = DNS_MODE_VIRTUAL
    private var currentVirtualDnsPool = DEFAULT_VIRTUAL_DNS_POOL
    private var currentIpv6Enabled = true
    private var currentRouteConfigYaml = ""
    private var currentGeoipDir = ""
    private var currentRouteSessionId = 0L
    private var currentSocksAddr = ""
    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var underlyingNetwork: Network? = null
    private var notificationChannelReady = false
    private var sessionBaseRxBytes = 0L
    private var sessionBaseTxBytes = 0L
    private var lastSampleRxBytes = 0L
    private var lastSampleTxBytes = 0L
    private var lastSampleAtMs = 0L
    private var statsJob: Job? = null

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
                val routeConfigYaml = currentRouteConfigYaml
                val geoipDir = currentGeoipDir
                serviceScope.launch {
                    try {
                        startVpn(nodeJson, excludedApps, allowedApps, nodeDns, overseasDns, directDns, dnsMode, virtualDnsPool, ipv6Enabled, routeConfigYaml, geoipDir)
                        startForegroundNotification(connectedNotificationText())
                        startStatsTicker()
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
                        startVpn(currentNodeJson, currentExcludedApps, currentAllowedApps, currentNodeDns, currentOverseasDns, currentDirectDns, currentDnsMode, currentVirtualDnsPool, currentIpv6Enabled, currentRouteConfigYaml, currentGeoipDir)
                        startForegroundNotification(connectedNotificationText())
                        startStatsTicker()
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
                val nodeJson = intent.getStringExtra(EXTRA_NODE)
                    ?: throw IllegalStateException("VPN start missing node")
                val nodesJson = intent.getStringExtra(EXTRA_NODES)
                    ?: throw IllegalStateException("VPN start missing nodes")
                if (!intent.hasExtra(EXTRA_NODE_INDEX)) {
                    throw IllegalStateException("VPN start missing node index")
                }
                val nodeIndex = intent.getIntExtra(EXTRA_NODE_INDEX, 0)
                val excludedApps = intent.getStringExtra(EXTRA_EXCLUDED_APPS)
                    ?: throw IllegalStateException("VPN start missing excluded apps")
                val allowedApps = intent.getStringExtra(EXTRA_ALLOWED_APPS)
                    ?: throw IllegalStateException("VPN start missing allowed apps")
                val nodeDns = intent.getStringExtra(EXTRA_NODE_DNS)
                    ?: throw IllegalStateException("VPN start missing node DNS")
                val overseasDns = intent.getStringExtra(EXTRA_OVERSEAS_DNS)
                    ?: throw IllegalStateException("VPN start missing overseas DNS")
                val directDns = intent.getStringExtra(EXTRA_DIRECT_DNS)
                    ?: throw IllegalStateException("VPN start missing direct DNS")
                val dnsMode = intent.getStringExtra(EXTRA_DNS_MODE)
                    ?: throw IllegalStateException("VPN start missing DNS mode")
                val virtualDnsPool = intent.getStringExtra(EXTRA_VIRTUAL_DNS_POOL)
                    ?: throw IllegalStateException("VPN start missing virtual DNS pool")
                if (!intent.hasExtra(EXTRA_IPV6_ENABLED)) {
                    throw IllegalStateException("VPN start missing IPv6 flag")
                }
                val ipv6Enabled = intent.getBooleanExtra(EXTRA_IPV6_ENABLED, true)
                val routeConfigYaml = intent.getStringExtra(EXTRA_ROUTE_CONFIG_YAML)
                    ?: throw IllegalStateException("VPN start missing route config")
                val geoipDir = intent.getStringExtra(EXTRA_GEOIP_DIR)
                    ?: throw IllegalStateException("VPN start missing geoip dir")
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
                currentRouteConfigYaml = routeConfigYaml
                currentGeoipDir = geoipDir
                startForegroundNotification(getString(R.string.vpn_notification_connecting))
                serviceScope.launch {
                    try {
                        startVpn(nodeJson, excludedApps, allowedApps, nodeDns, overseasDns, directDns, dnsMode, virtualDnsPool, ipv6Enabled, routeConfigYaml, geoipDir)
                        startForegroundNotification(connectedNotificationText())
                        startStatsTicker()
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
        try {
            stopCurrentVpn()
        } finally {
            serviceScope.cancel()
            vpnDispatcher.close()
            if (activeService === this) {
                activeService = null
            }
            super.onDestroy()
        }
    }

    private fun startVpn(nodeJson: String?, excludedApps: String?, allowedApps: String?, nodeDns: String, overseasDns: String, directDns: String, dnsMode: String, virtualDnsPool: String, ipv6Enabled: Boolean, routeConfigYaml: String, geoipDir: String) {
        stopNativeVpn()
        val dnsAddress = XboardApi.dnsAddressForVpn(if (dnsMode == DNS_MODE_DIRECT) directDns else overseasDns)
        val builder = Builder()
            .setSession(getString(R.string.app_name))
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
        if (ipv6Enabled && dnsMode != DNS_MODE_DIRECT) {
            builder.addDnsServer(PRIVATE_IPV6_DNS)
        }
        Log.i("XBClient", "VPN DNS config: mode=$dnsMode system_dns=$systemDns upstream_dns=$dnsAddress fake_pool=$virtualDnsPool ipv6=$ipv6Enabled route_rules=${routeConfigYaml.isNotBlank()}")
        if (routeConfigYaml.isNotBlank() && dnsMode != DNS_MODE_VIRTUAL) {
            Log.w("XBClient", "Clash rule routing is enabled with DNS mode $dnsMode; DOMAIN rules only keep hostnames reliably in Fake-IP mode.")
        }
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

        registerUnderlyingNetworkTracking()

        val node = JSONObject(nodeJson ?: throw IllegalStateException("node_json is required"))
        val protocol = node.getString("type").lowercase(Locale.US)
        if (protocol != "direct" && protocol != "block") {
            val originalHost = normalizeNodeHost(node.getString("host"))
            val resolvedHost = XboardApi.resolveNodeHost(nodeDns, originalHost)
            if (resolvedHost != originalHost && (!node.has("sni") || node.getString("sni").isBlank())) {
                node.put("sni", originalHost)
            }
            node.put("host", resolvedHost)
            node.put("server", resolvedHost)
        }
        currentNodeJson = node.toString()
        val tunnelNode = if (routeConfigYaml.isBlank()) {
            node
        } else {
            val routeResult = JSONObject(AerionCore.startRoute(JSONObject()
                .put("config_yaml", routeConfigYaml)
                .put("selected_proxy", node.getString("name"))
                .put("selected_node", node)
                .put("geoip_dir", geoipDir.trim())
                .toString()))
            if (!routeResult.getBoolean("ok")) {
                throw IllegalStateException(routeResult.toString())
            }
            currentRouteSessionId = routeResult.getLong("session_id")
            val socksAddr = routeResult.getString("socks_addr")
            val colon = socksAddr.lastIndexOf(':')
            JSONObject()
                .put("type", "socks5")
                .put("name", "Clash Rules")
                .put("host", socksAddr.substring(0, colon))
                .put("port", socksAddr.substring(colon + 1).toInt())
        }
        val tun: ParcelFileDescriptor = builder.establish() ?: throw IllegalStateException(getString(R.string.vpn_permission_denied))
        tunInterface = tun
        setUnderlyingNetworks(arrayOf(underlyingNetwork ?: throw IllegalStateException("active underlying network is required")))
        val request = JSONObject()
            .put("node", tunnelNode)
            .put("tun_fd", tun.fd)
            .put("mtu", 1500)
            .put("dns", dnsMode)
            .put("dns_addr", dnsAddress)
            .put("virtual_dns_pool", virtualDnsPool)
            .put("ipv6", ipv6Enabled)
        val result = JSONObject(AerionCore.startVpn(request.toString()))
        if (!result.getBoolean("ok")) {
            throw IllegalStateException(result.toString())
        }
        vpnSessionId = result.getLong("session_id")
        currentSocksAddr = result.getString("socks_addr")
        sessionBaseRxBytes = currentUidRxBytes()
        sessionBaseTxBytes = currentUidTxBytes()
        lastSampleRxBytes = sessionBaseRxBytes
        lastSampleTxBytes = sessionBaseTxBytes
        lastSampleAtMs = System.currentTimeMillis()
        Log.i("XBClient", "VPN started: $result")
    }

    /**
     * Follows the active underlying (non-VPN) network and pins it via
     * [setUnderlyingNetworks]. Without this, after a Wi-Fi sleep/handover or IP
     * change the tunnel stays bound to the now-dead network and apps lose
     * connectivity until the VPN is restarted.
     */
    private fun registerUnderlyingNetworkTracking() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java)
            ?: throw IllegalStateException("connectivity manager is required")
        val activeNetwork = manager.activeNetwork?.takeIf { isNonVpnInternet(manager, it) }
            ?: throw IllegalStateException("active underlying network is required")
        underlyingNetwork = activeNetwork
        setUnderlyingNetworks(arrayOf(activeNetwork))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                underlyingNetwork = network
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                if (underlyingNetwork == network) {
                    underlyingNetwork = null
                    setUnderlyingNetworks(null)
                }
            }
        }
        try {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                callback
            )
            connectivityManager = manager
            networkCallback = callback
        } catch (error: Throwable) {
            Log.e("XBClient", "register underlying network callback failed", error)
            throw IllegalStateException("register underlying network callback failed", error)
        }
    }

    private fun isNonVpnInternet(manager: ConnectivityManager, network: Network): Boolean {
        val capabilities = manager.getNetworkCapabilities(network)
            ?: throw IllegalStateException("network capabilities are required")
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun unregisterUnderlyingNetworkTracking() {
        val manager = connectivityManager
        val callback = networkCallback
        if (manager != null && callback != null) {
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (error: Throwable) {
                Log.e("XBClient", "unregister underlying network callback failed", error)
                throw IllegalStateException("unregister underlying network callback failed", error)
            }
        }
        networkCallback = null
        connectivityManager = null
        underlyingNetwork = null
    }

    private fun stopCurrentVpn(error: String = "") {
        stopStatsTicker()
        stopNativeVpn()
        publishVpnState(false, error)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun stopNativeVpn() {
        unregisterUnderlyingNetworkTracking()
        if (vpnSessionId != 0L) {
            try {
                val result = AerionCore.stopVpn(vpnSessionId)
                Log.i("XBClient", "VPN stopped: $result")
            } catch (error: Throwable) {
                Log.e("XBClient", "stop VPN failed", error)
                throw IllegalStateException("stop VPN failed", error)
            }
            vpnSessionId = 0L
        }
        if (currentRouteSessionId != 0L) {
            try {
                val result = AerionCore.stopRoute(currentRouteSessionId)
                Log.i("XBClient", "route stopped: $result")
            } catch (error: Throwable) {
                Log.e("XBClient", "stop route failed", error)
                throw IllegalStateException("stop route failed", error)
            }
            currentRouteSessionId = 0L
        }
        currentSocksAddr = ""
        sessionBaseRxBytes = 0L
        sessionBaseTxBytes = 0L
        lastSampleRxBytes = 0L
        lastSampleTxBytes = 0L
        lastSampleAtMs = 0L
        tunInterface?.close()
        tunInterface = null
    }

    private fun ensureNotificationChannel() {
        if (notificationChannelReady) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_channel, getString(R.string.app_name)),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        notificationChannelReady = true
    }

    private fun buildNotification(text: String): Notification {
        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_next), getString(R.string.vpn_action_switch_node), selectNodeIntent()).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_popup_sync), getString(R.string.vpn_action_reconnect), serviceIntent(ACTION_RECONNECT, 2)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), getString(R.string.vpn_action_stop), serviceIntent(ACTION_STOP, 3)).build())
            .build()
    }

    private fun startForegroundNotification(text: String) {
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(text))
    }

    private fun updateNotification(text: String) {
        ensureNotificationChannel()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startStatsTicker() {
        stopStatsTicker()
        statsJob = serviceScope.launch {
            while (vpnSessionId != 0L) {
                updateNotification(connectedNotificationText())
                delay(1000)
            }
        }
    }

    private fun stopStatsTicker() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun selectNodeIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        intent.action = MainActivity.ACTION_SELECT_NODE
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, XbClientVpnService::class.java)
        intent.action = action
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun currentNodeName(): String {
        val node = JSONObject(currentNodeJson)
        val host = node.getString("host")
        val name = node.getString("name").trim()
        if (name.isEmpty() || name == host || name == "$host:${node.getInt("port")}" || host.isNotEmpty() && name.contains(host)) {
            return getString(R.string.node_default_name, currentNodeIndex + 1)
        }
        return name
    }

    private fun connectedNotificationText(): String {
        val now = System.currentTimeMillis()
        val rx = currentUidRxBytes()
        val tx = currentUidTxBytes()
        val sessionTraffic = (rx - sessionBaseRxBytes).coerceAtLeast(0L) + (tx - sessionBaseTxBytes).coerceAtLeast(0L)
        val elapsedMs = (now - lastSampleAtMs).coerceAtLeast(1L)
        val rxSpeed = ((rx - lastSampleRxBytes).coerceAtLeast(0L) * 1000L) / elapsedMs
        val txSpeed = ((tx - lastSampleTxBytes).coerceAtLeast(0L) * 1000L) / elapsedMs
        lastSampleRxBytes = rx
        lastSampleTxBytes = tx
        lastSampleAtMs = now
        return getString(
            R.string.vpn_notification_runtime_stats,
            currentNodeName(),
            formatTrafficBytes(sessionTraffic),
            formatTrafficBytes(txSpeed) + "/s",
            formatTrafficBytes(rxSpeed) + "/s"
        )
    }

    private fun publishVpnState(running: Boolean, error: String = "") {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("vpn_running", running)
            .putString("vpn_socks_addr", if (running) currentSocksAddr else "")
            .commit()
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

    private fun currentUidRxBytes(): Long =
        TrafficStats.getUidRxBytes(applicationInfo.uid).coerceAtLeast(0L)

    private fun currentUidTxBytes(): Long =
        TrafficStats.getUidTxBytes(applicationInfo.uid).coerceAtLeast(0L)

    private fun formatTrafficBytes(value: Long): String = formatTrafficBytes(value.toDouble())

    companion object {
        const val ACTION_START = "moe.telecom.xbclient.action.START_VPN"
        const val ACTION_STOP = "moe.telecom.xbclient.action.STOP_VPN"
        const val ACTION_RECONNECT = "moe.telecom.xbclient.action.RECONNECT_VPN"
        const val ACTION_NEXT_NODE = "moe.telecom.xbclient.action.NEXT_NODE"
        const val ACTION_STATE = "moe.telecom.xbclient.action.VPN_STATE"
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
        const val EXTRA_ROUTE_CONFIG_YAML = "route_config_yaml"
        const val EXTRA_GEOIP_DIR = "geoip_dir"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_ERROR = "error"
        private const val DEFAULT_NODE_DNS = "https://dns.alidns.com/resolve"
        private const val DEFAULT_OVERSEAS_DNS = "https://cloudflare-dns.com/dns-query"
        private const val DEFAULT_DIRECT_DNS = "223.5.5.5"
        private const val DEFAULT_VIRTUAL_DNS_POOL = "198.18.0.0/15"
        private const val DNS_MODE_OVER_TCP = "over_tcp"
        private const val DNS_MODE_VIRTUAL = "virtual"
        private const val DNS_MODE_DIRECT = "direct"
        private const val PRIVATE_IPV4_CLIENT = "172.19.0.1"
        private const val PRIVATE_IPV4_DNS = "172.19.0.2"
        private const val PRIVATE_IPV6_CLIENT = "fdfe:dcba:9876::1"
        private const val PRIVATE_IPV6_DNS = "fdfe:dcba:9876::2"
        private const val CHANNEL_ID = "xbclient_vpn"
        private const val NOTIFICATION_ID = 1
        private const val PREFS = "xbclient"
        @Volatile
        private var activeService: XbClientVpnService? = null

        @JvmStatic
        fun protectSocketFd(fd: Int): Boolean {
            val service = activeService
            if (service == null) {
                Log.d("XBClient", "skip Android VPN socket protection without active VPN service")
                return true
            }
            if (service.tunInterface == null) {
                Log.d("XBClient", "skip Android VPN socket protection without active tunnel")
                return true
            }
            if (!service.protect(fd)) {
                return false
            }
            val network = service.underlyingNetwork
                ?: throw IllegalStateException("Android VPN underlying network is not available for socket fd $fd")
            ParcelFileDescriptor.fromFd(fd).use { descriptor ->
                network.bindSocket(descriptor.fileDescriptor)
            }
            return true
        }

        @JvmStatic
        fun onLog(level: String, message: String) {
            Log.d("AerionLog", "[$level] $message")
            // Future: Broadcast logs to UI
        }

        @JvmStatic
        fun onEvent(eventJson: String) {
            Log.d("AerionEvent", eventJson)
            // Future: Update traffic stats in UI
        }
    }
}
