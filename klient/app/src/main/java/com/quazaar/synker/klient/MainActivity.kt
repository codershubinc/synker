package com.quazaar.synker.klient

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.quazaar.synker.klient.data.CurrentlyPlaying
import com.quazaar.synker.klient.data.DayStat
import com.quazaar.synker.klient.data.TrackStat
import com.quazaar.synker.klient.ui.MainMediaDashboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val hasNotificationAccessState = mutableStateOf(false)
    private val localTotalPlaysState = mutableStateOf(0)
    private val uniqueTracksCountState = mutableStateOf(0)
    private val allTracksState = mutableStateOf<List<TrackStat>>(emptyList())
    private val dailyStatsState = mutableStateOf<List<DayStat>>(emptyList())
    private val searchQueryState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as QuazaarApplication
        val dbHelper = app.databaseHelper

        updateNotificationAccessState()
        if (hasNotificationAccessState.value) {
            com.quazaar.synker.klient.service.QuazaarBackgroundDaemon.start(this)
        }

        // Periodically refresh stats and database records
        lifecycleScope.launch {
            while (isActive) {
                try {
                    refreshData(dbHelper)
                } catch (_: Exception) {}
                delay(2000)
            }
        }

        val syncManager = app.syncManager

        setContent {
            val hasNotificationAccess by hasNotificationAccessState
            val localTotalPlays by localTotalPlaysState
            val uniqueTracksCount by uniqueTracksCountState
            val allTracks by allTracksState
            val dailyStats by dailyStatsState
            val searchQuery by searchQueryState
            val currentPlaying by dbHelper.currentPlaying.collectAsState()
            val totalListenSeconds by dbHelper.totalListenSeconds.collectAsState()
            val lastSyncTimestamp by syncManager.lastSyncTimestamp.collectAsState()
            val syncStatus by syncManager.syncStatus.collectAsState()
            val isWebSocketConnected by syncManager.isWebSocketConnected.collectAsState()

            MainMediaDashboard(
                currentPlaying = currentPlaying,
                totalPlays = localTotalPlays,
                uniqueTracksCount = uniqueTracksCount,
                totalListenSeconds = totalListenSeconds,
                allTracks = allTracks,
                dailyStats = dailyStats,
                hasNotificationAccess = hasNotificationAccess,
                onOpenNotificationSettings = {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                searchQuery = searchQuery,
                onSearchChange = { query ->
                    searchQueryState.value = query
                    lifecycleScope.launch {
                        allTracksState.value = dbHelper.getAllTracks(query)
                    }
                },
                onClearAllLogs = {
                    lifecycleScope.launch {
                        dbHelper.clearAllData()
                        refreshData(dbHelper)
                    }
                },
                lastSyncTimestamp = lastSyncTimestamp,
                syncStatus = syncStatus,
                isWebSocketConnected = isWebSocketConnected,
                onManualSync = {
                    syncManager.triggerManualSync()
                },
                onRetryWebSocket = {
                    syncManager.reconnectWebSocket()
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateNotificationAccessState()
        if (hasNotificationAccessState.value) {
            com.quazaar.synker.klient.service.QuazaarBackgroundDaemon.start(this)
        }
        lifecycleScope.launch {
            val app = application as QuazaarApplication
            refreshData(app.databaseHelper)
        }
    }

    private suspend fun refreshData(dbHelper: com.quazaar.synker.klient.data.MusicDatabaseHelper) {
        localTotalPlaysState.value = dbHelper.getTotalPlaysCount()
        uniqueTracksCountState.value = dbHelper.getUniqueTracksCount()
        allTracksState.value = dbHelper.getAllTracks(searchQueryState.value)
        dailyStatsState.value = dbHelper.getDailyStats()
    }

    private fun updateNotificationAccessState() {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val pkg = packageName
        hasNotificationAccessState.value = enabledListeners != null && enabledListeners.contains(pkg)
    }
}
