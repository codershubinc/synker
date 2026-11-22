package com.quazaar.synker.klient.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.quazaar.synker.klient.QuazaarApplication
import com.quazaar.synker.klient.data.MusicDatabaseHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

class QuazaarSyncManager(private val context: Context) {

    companion object {
        private const val TAG = "QuazaarSyncManager"
        private const val SERVICE_TYPE = "_quazaar._tcp."
        const val BACKUP_TUNNEL_URL = "https://quazaar-sync.codershubinc.com"

        @Volatile
        private var instance: QuazaarSyncManager? = null

        fun getInstance(context: Context): QuazaarSyncManager {
            return instance ?: synchronized(this) {
                instance ?: QuazaarSyncManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Retrieves all active non-loopback IPv4 addresses assigned to this device (Wi-Fi, Ethernet).
         */
        fun getLocalIpv4Addresses(): List<String> {
            val result = mutableListOf<String>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
                for (iface in interfaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            addr.hostAddress?.let { result.add(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying local IPv4 addresses: ${e.message}")
            }
            return result
        }

        /**
         * Checks if candidate IP is plausible for local LAN sync.
         * Rejects loopback, link-local, and Docker/virtual bridge subnets (172.17.*, 172.18.*, 172.19.*)
         * unless this device itself is on that exact subnet.
         */
        fun isCandidateIpValid(candidate: String, localIps: List<String>): Boolean {
            if (candidate.isBlank() || candidate.equals("localhost", ignoreCase = true)) return false
            if (candidate.startsWith("127.") || candidate.startsWith("169.254.")) return false

            // Reject standard Docker bridge default ranges unless this device shares the subnet
            if (candidate.startsWith("172.17.") || candidate.startsWith("172.18.") || candidate.startsWith("172.19.")) {
                val deviceOnSameSubnet = localIps.any { local ->
                    val localPrefix = local.substringBeforeLast(".")
                    val candidatePrefix = candidate.substringBeforeLast(".")
                    localPrefix == candidatePrefix
                }
                if (!deviceOnSameSubnet) {
                    Log.d(TAG, "Ignoring Docker/bridge IP $candidate (device LAN IPs: $localIps)")
                    return false
                }
            }
            return true
        }

        /**
         * Selects best LAN IP from candidate list by matching subnet proximity with local device IP.
         */
        fun selectBestIp(candidates: List<String>): String? {
            val localIps = getLocalIpv4Addresses()
            val valid = candidates.filter { isCandidateIpValid(it, localIps) }.distinct()
            if (valid.isEmpty()) return null

            // Score candidates based on subnet match with device
            return valid.maxByOrNull { ip ->
                var score = 0
                for (local in localIps) {
                    val localOctets = local.split(".")
                    val candidateOctets = ip.split(".")
                    if (localOctets.size == 4 && candidateOctets.size == 4) {
                        if (localOctets[0] == candidateOctets[0] &&
                            localOctets[1] == candidateOctets[1] &&
                            localOctets[2] == candidateOctets[2]) {
                            // Exact /24 subnet match (e.g. 192.168.1.x)
                            score = maxOf(score, 100)
                        } else if (localOctets[0] == candidateOctets[0] &&
                                   localOctets[1] == candidateOctets[1]) {
                            // Same /16 subnet
                            score = maxOf(score, 50)
                        }
                    }
                }
                if (score == 0) {
                    if (ip.startsWith("192.168.")) score = 30
                    else if (ip.startsWith("10.")) score = 20
                    else if (ip.startsWith("172.")) score = 10
                }
                score
            }
        }
    }

    fun getActiveHost(): String? {
        val manual = context.getSharedPreferences("synker_prefs", Context.MODE_PRIVATE)
            .getString("manual_host", "")?.trim()
        val localIps = getLocalIpv4Addresses()
        if (!manual.isNullOrEmpty() && isCandidateIpValid(manual, localIps)) {
            return manual
        }
        val discovered = _discoveredHost.value
        if (!discovered.isNullOrEmpty() && isCandidateIpValid(discovered, localIps)) {
            return discovered
        }
        return if (!discovered.isNullOrEmpty()) discovered else manual
    }

    private fun getAuthToken(): String {
        return context.getSharedPreferences("synker_prefs", Context.MODE_PRIVATE)
            .getString("auth_token", "") ?: ""
    }

    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var activeWebSocket: WebSocket? = null
    @Volatile private var webSocketConnecting = false
    @Volatile private var lastWsConnectAttemptMs = 0L
    private val wsLock = Any()

    private val _isWebSocketConnected = MutableStateFlow(false)
    val isWebSocketConnected: StateFlow<Boolean> = _isWebSocketConnected.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow(0L)
    val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp.asStateFlow()

    private val _syncStatus = MutableStateFlow("Ready")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private val _globalTotalListenSeconds = MutableStateFlow<Long?>(null)
    val globalTotalListenSeconds: StateFlow<Long?> = _globalTotalListenSeconds.asStateFlow()

    private val _globalTotalPlays = MutableStateFlow<Int?>(null)
    val globalTotalPlays: StateFlow<Int?> = _globalTotalPlays.asStateFlow()

    private val _globalDeviceCount = MutableStateFlow<Int?>(null)
    val globalDeviceCount: StateFlow<Int?> = _globalDeviceCount.asStateFlow()

    private val _discoveredHost = MutableStateFlow<String?>(null)
    val discoveredHost: StateFlow<String?> = _discoveredHost.asStateFlow()

    private val _discoveredPort = MutableStateFlow(4242)
    val discoveredPort: StateFlow<Int> = _discoveredPort.asStateFlow()

    private var syncIntervalSeconds: Long = 30L
    private var periodicSyncJob: Job? = null

    @Volatile private var isDiscoveryRunning = false
    private val discoveryLock = Any()

    @Volatile private var isResolving = false
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private val resolveLock = Any()

    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android_${Build.MODEL}"
    }

    private val deviceName: String by lazy {
        "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "mDNS Service discovery started for $regType")
            synchronized(discoveryLock) {
                isDiscoveryRunning = true
            }
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d(TAG, "Service found: ${service.serviceName}, type: ${service.serviceType}")
            if (service.serviceType.contains("_quazaar._tcp")) {
                synchronized(resolveLock) {
                    resolveQueue.addLast(service)
                    resolveNextService()
                }
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.d(TAG, "Service lost: ${service.serviceName}")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "Discovery stopped: $serviceType")
            synchronized(discoveryLock) {
                isDiscoveryRunning = false
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Start discovery failed: $errorCode")
            synchronized(discoveryLock) {
                isDiscoveryRunning = false
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Stop discovery failed: $errorCode")
            synchronized(discoveryLock) {
                isDiscoveryRunning = false
            }
        }
    }

    private fun resolveNextService() {
        synchronized(resolveLock) {
            if (isResolving || resolveQueue.isEmpty()) return
            val nextService = resolveQueue.removeFirst()
            isResolving = true
            try {
                @Suppress("DEPRECATION")
                nsdManager?.resolveService(nextService, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        synchronized(resolveLock) {
                            isResolving = false
                            resolveNextService()
                        }
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val candidates = mutableListOf<String>()

                        // 1. Check TXT record attributes for explicit LAN IP announced by daemon
                        try {
                            serviceInfo.attributes?.forEach { (key, value) ->
                                if (key.equals("lan_ip", ignoreCase = true) || key.equals("ip", ignoreCase = true)) {
                                    val ipStr = String(value, Charsets.UTF_8).trim()
                                    if (ipStr.isNotEmpty()) candidates.add(ipStr)
                                } else if (key.equals("ips", ignoreCase = true)) {
                                    val ipsStr = String(value, Charsets.UTF_8).trim()
                                    ipsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { candidates.add(it) }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error reading TXT attributes: ${e.message}")
                        }

                        // 2. Check hostAddresses on Android 14+ (API 34+)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            try {
                                serviceInfo.hostAddresses?.forEach { addr ->
                                    val ipStr = addr.hostAddress
                                    if (!ipStr.isNullOrBlank() && !addr.isLoopbackAddress && addr is Inet4Address) {
                                        candidates.add(ipStr)
                                    }
                                }
                            } catch (_: Throwable) {}
                        }

                        // 3. Fallback to deprecated host property
                        @Suppress("DEPRECATION")
                        val defaultHost = serviceInfo.host?.hostAddress
                        if (!defaultHost.isNullOrBlank()) {
                            candidates.add(defaultHost)
                        }

                        val selectedHost = selectBestIp(candidates)
                        val port = serviceInfo.port
                        Log.i(TAG, "Resolved Quazaar Daemon. Candidates: $candidates, selected: $selectedHost, port: $port")

                        if (!selectedHost.isNullOrBlank()) {
                            _discoveredHost.value = selectedHost
                            _discoveredPort.value = port
                        } else {
                            Log.w(TAG, "No valid/reachable LAN IP found in resolved service candidates: $candidates")
                        }
                        synchronized(resolveLock) {
                            isResolving = false
                            resolveNextService()
                        }
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Error initiating resolveService: ${e.message}")
                isResolving = false
                resolveNextService()
            }
        }
    }

    init {
        startLanDiscovery()
        startPeriodicSync(30L)
        syncScope.launch {
            fetchGlobalSummary()
        }
    }

    fun startLanDiscovery() {
        synchronized(discoveryLock) {
            if (isDiscoveryRunning) return
            try {
                nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                isDiscoveryRunning = true
            } catch (e: Exception) {
                Log.w(TAG, "Could not start NSD discovery: ${e.message}")
                isDiscoveryRunning = false
            }
        }
    }

    fun stopLanDiscovery() {
        synchronized(discoveryLock) {
            if (!isDiscoveryRunning) return
            try {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping NSD: ${e.message}")
            } finally {
                isDiscoveryRunning = false
                synchronized(resolveLock) {
                    resolveQueue.clear()
                    isResolving = false
                }
            }
        }
    }

    fun startPeriodicSync(intervalSeconds: Long = 30L) {
        syncIntervalSeconds = intervalSeconds
        periodicSyncJob?.cancel()
        periodicSyncJob = syncScope.launch {
            while (isActive) {
                delay(syncIntervalSeconds * 1000L)
                performSync()
                fetchGlobalSummary()
            }
        }
    }

    suspend fun pullDataFromServer(dbHelper: MusicDatabaseHelper): Boolean = withContext(Dispatchers.IO) {
        _syncStatus.value = "Restoring..."
        val host = getActiveHost()
        val port = _discoveredPort.value
        val token = getAuthToken()

        val candidateUrls = mutableListOf<String>()
        if (!host.isNullOrBlank()) {
            candidateUrls.add("http://$host:$port/api/v1/sync/pull?device_id=$deviceId")
        }
        candidateUrls.add("$BACKUP_TUNNEL_URL/api/v1/sync/pull?device_id=$deviceId")

        for (urlString in candidateUrls) {
            var conn: HttpURLConnection? = null
            try {
                Log.d(TAG, "Attempting pull from $urlString")
                val url = URL(urlString)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = if (urlString.startsWith("http://")) 4000 else 15000
                    readTimeout = if (urlString.startsWith("http://")) 10000 else 30000
                    if (token.isNotEmpty()) {
                        setRequestProperty("X-Sync-Token", token)
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    setRequestProperty("X-Device-ID", deviceId)
                    setRequestProperty("X-Device-Name", deviceName)
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val json = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    val result = dbHelper.restoreDataFromJson(json)
                    if (result.success) {
                        _syncStatus.value = "Restored successfully (${result.tracksCount} tracks)"
                        Log.i(TAG, "Restore successful from $urlString: ${result.tracksCount} tracks, ${result.playsCount} plays")
                        return@withContext true
                    } else {
                        _syncStatus.value = "Restore failed: ${result.errorMessage}"
                        Log.w(TAG, "Restore data parsing failed: ${result.errorMessage}")
                        return@withContext false
                    }
                } else {
                    val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
                    Log.w(TAG, "Pull from $urlString returned HTTP $code: $err")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Pull error from $urlString: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }

        _syncStatus.value = "Restore failed: daemon unreachable"
        return@withContext false
    }

    fun triggerManualSync() {
        syncScope.launch {
            performSync()
            fetchGlobalSummary()
        }
    }

    suspend fun fetchGlobalSummary(): Boolean = withContext(Dispatchers.IO) {
        val host = getActiveHost()
        val port = _discoveredPort.value
        val token = getAuthToken()

        val candidateUrls = mutableListOf<String>()
        if (!host.isNullOrBlank()) {
            candidateUrls.add("http://$host:$port/api/v1/summary")
        }
        candidateUrls.add("$BACKUP_TUNNEL_URL/api/v1/summary")

        for (urlString in candidateUrls) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = if (urlString.startsWith("http://")) 3000 else 10000
                    readTimeout = if (urlString.startsWith("http://")) 5000 else 15000
                    if (token.isNotEmpty()) {
                        setRequestProperty("X-Sync-Token", token)
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    val json = JSONObject(body)
                    val totalSec = json.optLong("total_listening_time_sec", -1L)
                    if (totalSec >= 0) {
                        _globalTotalListenSeconds.value = totalSec
                    }
                    val totalPlays = json.optInt("total_play_count", -1)
                    if (totalPlays >= 0) {
                        _globalTotalPlays.value = totalPlays
                    }
                    val numDevices = json.optInt("device_numbers", -1)
                    if (numDevices >= 0) {
                        _globalDeviceCount.value = numDevices
                    }
                    Log.d(TAG, "Global summary fetched successfully: totalSec=$totalSec, devices=$numDevices")
                    return@withContext true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed to fetch summary from $urlString: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
        return@withContext false
    }

    suspend fun performSync(forceOverride: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        _syncStatus.value = "Syncing..."
        val dbHelper = (context.applicationContext as? QuazaarApplication)?.databaseHelper
            ?: QuazaarApplication.instance.databaseHelper

        val payload = try {
            buildSyncPayload(dbHelper, forceOverride)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compile sync payload: ${e.message}", e)
            _syncStatus.value = "Error preparing data"
            return@withContext false
        }

        // 1. Try local discovered LAN daemon first
        val localIp = getActiveHost()
        val localPort = _discoveredPort.value
        var success = false
        var targetEndpoint = ""

        if (!localIp.isNullOrBlank() && isCandidateIpValid(localIp, getLocalIpv4Addresses())) {
            val localUrl = "http://$localIp:$localPort/api/v1/sync"
            Log.d(TAG, "Attempting local sync to $localUrl")
            success = postPayload(localUrl, payload, connectTimeoutMs = 3000, readTimeoutMs = 6000)
            if (success) {
                targetEndpoint = "LAN ($localIp)"
            }
        }

        // 2. Fallback to tunnel URL if local failed or not found
        if (!success) {
            val tunnelUrl = "$BACKUP_TUNNEL_URL/api/v1/sync"
            Log.d(TAG, "Local sync unavailable; falling back to tunnel $tunnelUrl")
            success = postPayload(tunnelUrl, payload, connectTimeoutMs = 15000, readTimeoutMs = 30000)
            if (success) {
                targetEndpoint = "Tunnel (Cloud)"
            }
        }

        if (success) {
            val now = System.currentTimeMillis()
            _lastSyncTimestamp.value = now
            _syncStatus.value = "Synced via $targetEndpoint"
            Log.i(TAG, "Data synchronization successful via $targetEndpoint at $now")
            return@withContext true
        } else {
            _syncStatus.value = "Sync failed (retrying in ${syncIntervalSeconds}s)"
            Log.w(TAG, "Data synchronization failed across both LAN and tunnel.")
            return@withContext false
        }
    }

    suspend fun buildSyncPayload(dbHelper: MusicDatabaseHelper, forceOverride: Boolean = false): String {
        val json = JSONObject()
        json.put("device_id", deviceId)
        json.put("device_name", deviceName)
        json.put("override_server_stats", forceOverride)
        
        if (forceOverride) {
            json.put("total_seconds", dbHelper.totalListenSeconds.value)
            json.put("total_plays", dbHelper.getTotalPlaysCount())
            
            val tracksArr = JSONArray()
            val tracks = dbHelper.getAllTracks("")
            for (t in tracks) {
                val tObj = JSONObject()
                tObj.put("song_id", t.songId)
                tObj.put("title", t.title)
                val tArtArr = JSONArray()
                t.artists.forEach { tArtArr.put(it.trim()) }
                tObj.put("artists", tArtArr)
                tObj.put("album", t.album)
                tObj.put("play_count", t.playCount)
                tObj.put("first_played", t.firstPlayed)
                tObj.put("total_seconds", t.totalSeconds)
                tObj.put("last_played", t.lastPlayed)

                if (!t.artworkPath.isNullOrEmpty()) {
                    try {
                        val artFile = File(t.artworkPath)
                        if (artFile.exists() && artFile.length() in 1..400_000) {
                            val bytes = artFile.readBytes()
                            tObj.put("artwork_data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        }
                    } catch (_: Exception) {}
                }
                tracksArr.put(tObj)
            }
            json.put("tracks", tracksArr)
            
            val dailyStatsArr = JSONArray()
            val dailyStats = dbHelper.getDailyStats()
            for (ds in dailyStats) {
                val dsObj = JSONObject()
                dsObj.put("date", ds.date)
                dsObj.put("total_seconds", ds.totalSeconds)
                dsObj.put("play_count", ds.playCount)
                dailyStatsArr.put(dsObj)
            }
            json.put("daily_stats", dailyStatsArr)
            
            val playsArr = JSONArray()
            val plays = dbHelper.getRecentPlayLogs(1000)
            for (p in plays) {
                val pObj = JSONObject()
                pObj.put("song_id", p.songId)
                pObj.put("title", p.title)
                val pArtArr = JSONArray()
                p.artists.forEach { pArtArr.put(it) }
                pObj.put("artists", pArtArr)
                pObj.put("album", p.album)
                pObj.put("timestamp", p.timestamp)
                pObj.put("duration_ms", 180000)
                playsArr.put(pObj)
            }
            json.put("plays", playsArr)
        } else {
            // Delta sync optimization: omit totals, tracks, plays, and daily stats
            json.put("total_seconds", 0)
            json.put("total_plays", 0)
            json.put("tracks", JSONArray())
            json.put("plays", JSONArray())
            json.put("daily_stats", JSONArray())
        }

        return json.toString()
    }

    private fun postPayload(
        urlString: String,
        jsonString: String,
        connectTimeoutMs: Int = 5000,
        readTimeoutMs: Int = 10000
    ): Boolean {
        var conn: HttpURLConnection? = null
        val token = getAuthToken()
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Device-ID", deviceId)
                setRequestProperty("X-Device-Name", deviceName)
                if (token.isNotEmpty()) {
                    setRequestProperty("X-Sync-Token", token)
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonString)
                writer.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream?.bufferedReader(Charsets.UTF_8)?.readText()
                true
            } else {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
                Log.w(TAG, "HTTP POST to $urlString returned code $code: $err")
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "HTTP POST to $urlString failed: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Connects real-time WebSocket to daemon for live playback streaming.
     * Automatically attempts LAN first if discovered, and falls back to Tunnel (wss://) seamlessly.
     */
    fun ensureWebSocketConnected() {
        val now = System.currentTimeMillis()
        synchronized(wsLock) {
            if (webSocketConnecting || activeWebSocket != null || (now - lastWsConnectAttemptMs < 3000L)) return
            lastWsConnectAttemptMs = now
            webSocketConnecting = true
        }

        val host = getActiveHost()
        val port = _discoveredPort.value
        val tunnelWsUrl = BACKUP_TUNNEL_URL
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "/ws/v1/live"

        // Prefer LAN if available, otherwise connect via Tunnel
        val primaryWsUrl = if (!host.isNullOrBlank()) "ws://$host:$port/ws/v1/live" else tunnelWsUrl
        connectWebSocket(primaryWsUrl, fallbackUrl = if (primaryWsUrl != tunnelWsUrl) tunnelWsUrl else null)
    }

    /**
     * Manually triggers an immediate WebSocket reconnection attempt, resetting cooldowns and active connections.
     */
    fun reconnectWebSocket() {
        synchronized(wsLock) {
            try {
                activeWebSocket?.close(1000, "manual reconnect")
            } catch (_: Exception) {}
            activeWebSocket = null
            webSocketConnecting = false
            lastWsConnectAttemptMs = 0L
        }
        ensureWebSocketConnected()
    }

    private fun connectWebSocket(url: String, fallbackUrl: String? = null) {
        val token = getAuthToken()
        val request = Request.Builder()
            .url(url)
            .addHeader("X-Device-ID", deviceId)
            .addHeader("X-Device-Name", deviceName)
            .apply {
                if (token.isNotEmpty()) {
                    addHeader("X-Sync-Token", token)
                    addHeader("Authorization", "Bearer $token")
                }
            }
            .build()

        Log.d(TAG, "Attempting WebSocket connection to $url")

        okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Live WebSocket stream connected to $url")
                synchronized(wsLock) {
                    activeWebSocket = webSocket
                    webSocketConnecting = false
                }
                _isWebSocketConnected.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Daemon broadcast messages
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                synchronized(wsLock) {
                    if (activeWebSocket == webSocket) {
                        activeWebSocket = null
                        webSocketConnecting = false
                    }
                }
                _isWebSocketConnected.value = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket connection failed to $url: ${t.message}")
                synchronized(wsLock) {
                    if (activeWebSocket == webSocket) {
                        activeWebSocket = null
                        webSocketConnecting = false
                    }
                }
                _isWebSocketConnected.value = false

                if (!fallbackUrl.isNullOrBlank()) {
                    Log.i(TAG, "Falling back to WebSocket tunnel: $fallbackUrl")
                    connectWebSocket(fallbackUrl, fallbackUrl = null)
                }
            }
        })
    }

    /**
     * Broadcasts live Apple Music playback status (title, artist, elapsed seconds) to daemon in real-time.
     * @param artworkBase64 Optional Base64-encoded JPEG artwork (only sent when artwork changes to avoid large repeated payloads)
     */
    fun sendLivePlaybackTick(
        title: String,
        artists: List<String>,
        album: String,
        isPlaying: Boolean,
        currentSeconds: Long,
        durationSeconds: Long = 0L,
        durationMs: Long = 0L,
        artworkBase64: String? = null
    ) {
        ensureWebSocketConnected()
        val ws = activeWebSocket ?: return

        try {
            val json = JSONObject()
            json.put("type", "live_tick")
            val data = JSONObject()
            data.put("device_id", deviceId)
            data.put("device_name", deviceName)
            data.put("title", title)
            val artArr = JSONArray()
            artists.forEach { artArr.put(it) }
            data.put("artists", artArr)
            data.put("artist", artists.firstOrNull() ?: "")
            data.put("album", album)
            data.put("playback_status", if (isPlaying) "Playing" else "Idle")
            data.put("current_s", currentSeconds)
            data.put("current_seconds", currentSeconds)
            data.put("duration_s", durationSeconds)
            data.put("duration_sec", durationSeconds)
            data.put("duration_ms", if (durationMs > 0) durationMs else (durationSeconds * 1000L))
            data.put("source", "Apple Music (Mobile)")
            if (!artworkBase64.isNullOrEmpty()) {
                data.put("artwork_data", artworkBase64)
            }
            json.put("data", data)

            ws.send(json.toString())
        } catch (e: Exception) {
            Log.d(TAG, "Failed to send live WS tick: ${e.message}")
        }
    }
}
