package com.quazaar.synker.klient.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.quazaar.synker.klient.QuazaarApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
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
    }

            fun getActiveHost(): String? {
        val manual = context.getSharedPreferences("synker_prefs", android.content.Context.MODE_PRIVATE)
            .getString("manual_host", "")
        if (!manual.isNullOrEmpty()) return manual
        return _discoveredHost.value
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

    private var activeWebSocket: WebSocket? = null
    private var webSocketConnecting = false
    private var lastWsConnectAttemptMs = 0L

    private val _isWebSocketConnected = MutableStateFlow(false)
    val isWebSocketConnected: StateFlow<Boolean> = _isWebSocketConnected.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow(0L)
    val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp.asStateFlow()

    private val _syncStatus = MutableStateFlow("Ready")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private val _discoveredHost = MutableStateFlow<String?>(null)
    val discoveredHost: StateFlow<String?> = _discoveredHost.asStateFlow()

    private val _discoveredPort = MutableStateFlow(4242)
    val discoveredPort: StateFlow<Int> = _discoveredPort.asStateFlow()

    private var syncIntervalSeconds: Long = 30L
    private var periodicSyncJob: Job? = null
    private var isDiscoveryRunning = false

    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android_${Build.MODEL}"
    }

    private val deviceName: String by lazy {
        "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "mDNS Service discovery started for $regType")
            isDiscoveryRunning = true
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d(TAG, "Service found: ${service.serviceName}, type: ${service.serviceType}")
            if (service.serviceType.contains("_quazaar._tcp")) {
                try {
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress
                            val port = serviceInfo.port
                            Log.i(TAG, "Resolved Quazaar Daemon at http://$host:$port")
                            if (!host.isNullOrBlank()) {
                                _discoveredHost.value = host
                                _discoveredPort.value = port
                            }
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Error initiating resolveService: ${e.message}")
                }
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.d(TAG, "Service lost: ${service.serviceName}")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "Discovery stopped: $serviceType")
            isDiscoveryRunning = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Start discovery failed: $errorCode")
            isDiscoveryRunning = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Stop discovery failed: $errorCode")
            isDiscoveryRunning = false
        }
    }

    init {
        startLanDiscovery()
        startPeriodicSync(30L)
    }

    fun startLanDiscovery() {
        if (isDiscoveryRunning) return
        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start NSD discovery: ${e.message}")
        }
    }

    fun stopLanDiscovery() {
        if (!isDiscoveryRunning) return
        try {
            nsdManager?.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping NSD: ${e.message}")
        }
    }

    fun startPeriodicSync(intervalSeconds: Long = 30L) {
        syncIntervalSeconds = intervalSeconds
        periodicSyncJob?.cancel()
        periodicSyncJob = syncScope.launch {
            while (isActive) {
                delay(syncIntervalSeconds * 1000L)
                performSync()
            }
        }
    }

        suspend fun pullDataFromServer(dbHelper: com.quazaar.synker.klient.data.MusicDatabaseHelper): Boolean = withContext(Dispatchers.IO) {
        _syncStatus.value = "Restoring..."
        val urlString = "http://${getActiveHost()}:${_discoveredPort.value}/api/v1/sync/pull?device_id=$deviceId"
        var conn: java.net.HttpURLConnection? = null
        try {
            val url = java.net.URL(urlString)
            conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-Sync-Token", getAuthToken())
            
            if (conn.responseCode in 200..299) {
                val json = conn.inputStream.bufferedReader().readText()
                dbHelper.restoreDataFromJson(json)
                _syncStatus.value = "Restored successfully"
                return@withContext true
            } else {
                _syncStatus.value = "Restore failed: ${conn.responseCode}"
            }
        } catch(e: Exception) {
            _syncStatus.value = "Restore error: ${e.message}"
        } finally {
            conn?.disconnect()
        }
        return@withContext false
    }

    fun triggerManualSync() {
        syncScope.launch {
            performSync()
        }
    }

    suspend fun performSync(forceOverride: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        _syncStatus.value = "Syncing..."
        val dbHelper = QuazaarApplication.instance.databaseHelper

        val payload = try {
            buildSyncPayload(dbHelper, forceOverride)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compile sync payload: ${e.message}", e)
            _syncStatus.value = "Error preparing data"
            return@withContext false
        }

        // 1. Try local discovered LAN daemon first
        val localIp = _discoveredHost.value
        val localPort = _discoveredPort.value
        var success = false
        var targetEndpoint = ""

        if (!localIp.isNullOrBlank()) {
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

    suspend fun buildSyncPayload(dbHelper: com.quazaar.synker.klient.data.MusicDatabaseHelper, forceOverride: Boolean = false): String {
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
                // Use correct field names from TrackStat
                t.artists.forEach { tArtArr.put(it.trim()) }
                tObj.put("artists", tArtArr)
                tObj.put("album", t.album)
                tObj.put("play_count", t.playCount)
                tObj.put("total_seconds", t.totalSeconds)
                tObj.put("last_played", t.lastPlayed)
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
            val plays = dbHelper.getRecentPlayLogs(1000) // send a larger chunk of logs
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
            // Delta sync optimization: do not send totals, tracks, or daily stats
            json.put("total_seconds", 0)
            json.put("total_plays", 0)
            json.put("tracks", JSONArray())

            val playsArr = JSONArray()
            val plays = dbHelper.getRecentPlayLogs(50)
            for (p in plays) {
                val pObj = JSONObject()
                pObj.put("song_id", p.songId)
                pObj.put("title", p.title)
                val pArtArr = JSONArray()
                p.artists.forEach { pArtArr.put(it) }
                pObj.put("artists", pArtArr)
                pObj.put("album", p.album)
                pObj.put("timestamp", p.timestamp)
                // Add duration for delta sync
                pObj.put("duration_ms", 180000) // Dummy default or you would get it from db if stored
                playsArr.put(pObj)
            }
            json.put("plays", playsArr)
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
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Device-ID", deviceId)
                setRequestProperty("X-Device-Name", deviceName)
                setRequestProperty("X-Sync-Token", getAuthToken()) // Hardcoded for now
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(jsonString)
                writer.flush()
            }

            val code = conn.responseCode
            code in 200..299
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
        if (webSocketConnecting || activeWebSocket != null || (now - lastWsConnectAttemptMs < 3000L)) return

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
        try {
            activeWebSocket?.close(1000, "manual reconnect")
        } catch (_: Exception) {}
        activeWebSocket = null
        webSocketConnecting = false
        lastWsConnectAttemptMs = 0L
        ensureWebSocketConnected()
    }

    private fun connectWebSocket(url: String, fallbackUrl: String? = null) {
        lastWsConnectAttemptMs = System.currentTimeMillis()
        webSocketConnecting = true

        val request = Request.Builder()
            .url(url)
            .addHeader("X-Device-ID", deviceId)
            .addHeader("X-Device-Name", deviceName)
            .addHeader("X-Sync-Token", getAuthToken()) // Hardcoded for now
            .build()

        Log.d(TAG, "Attempting WebSocket connection to $url")

        okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Live WebSocket stream connected to $url")
                activeWebSocket = webSocket
                webSocketConnecting = false
                _isWebSocketConnected.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Daemon broadcast messages
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                activeWebSocket = null
                webSocketConnecting = false
                _isWebSocketConnected.value = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket connection failed to $url: ${t.message}")
                activeWebSocket = null
                webSocketConnecting = false
                _isWebSocketConnected.value = false

                // Attempt fallback if available (e.g. LAN failed, fall back to tunnel)
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
            data.put("current_seconds", currentSeconds)
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
