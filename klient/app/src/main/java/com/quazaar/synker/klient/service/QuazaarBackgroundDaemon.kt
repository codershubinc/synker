package com.quazaar.synker.klient.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.quazaar.synker.klient.QuazaarApplication
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

class QuazaarBackgroundDaemon : Service() {

    companion object {
        private const val TAG = "QuazaarDaemon"
        private const val CHANNEL_ID = "quazaar_daemon_channel"
        private const val NOTIFICATION_ID = 4242

        fun start(context: Context) {
            val intent = Intent(context, QuazaarBackgroundDaemon::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var pollJob: Job? = null
    /** Track the last song title for which we sent artwork so we only transmit art on song change */
    private var lastArtworkSentTitle: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Active", "Tracking Apple Music playback in background"))
        startContinuousTrackingLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Quazaar Music Daemon",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Apple Music tracking active continuously in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(title: String, body: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startContinuousTrackingLoop() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            while (isActive) {
                try {
                    pollAppleMusicMediaSessions()
                } catch (e: Exception) {
                    Log.w(TAG, "MediaSession poll error: ${e.message}")
                }
                delay(1000L) // Poll every 1 second for exact live listen time tracking
            }
        }
    }

    private suspend fun pollAppleMusicMediaSessions() {
        val sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val listenerComponent = ComponentName(this, QuazaarNotificationListener::class.java)

        val controllers: List<MediaController> = try {
            sessionManager.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) {
            return
        }

        var foundPlayingAppleMusic = false

        for (controller in controllers) {
            val pkg = controller.packageName ?: ""
            val isAppleMusic = pkg.contains("apple", ignoreCase = true) || pkg == "com.apple.android.music"
            if (!isAppleMusic) continue

            val pbState = controller.playbackState
            val isPlaying = pbState?.state == PlaybackState.STATE_PLAYING

            val metadata = controller.metadata
            if (metadata != null) {
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    ?: ""
                val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                    ?: ""

                val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

                var artBitmap: Bitmap? = null
                try {
                    artBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                } catch (_: Exception) {}

                if (title.isNotBlank()) {
                    if (isPlaying) {
                        foundPlayingAppleMusic = true
                    }
                    // Route to continuous verification loop in MusicDatabaseHelper
                    QuazaarApplication.instance.databaseHelper.processPlaybackTick(
                        title = title,
                        rawArtist = artist,
                        isPlaying = isPlaying,
                        album = album,
                        artworkBitmap = artBitmap
                    )

                    // Stream live playback tick to daemon over WebSocket if playing
                    if (isPlaying) {
                        val current = QuazaarApplication.instance.databaseHelper.currentPlaying.value
                        val artists = QuazaarApplication.instance.databaseHelper.splitArtists(artist)

                        // Encode artwork as Base64 JPEG only when the song changes (not every tick)
                        var artworkBase64: String? = null
                        if (artBitmap != null && title != lastArtworkSentTitle) {
                            try {
                                val baos = ByteArrayOutputStream()
                                artBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                                artworkBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                                lastArtworkSentTitle = title
                            } catch (e: Exception) {
                                Log.d(TAG, "Artwork encode error: ${e.message}")
                            }
                        }

                        QuazaarApplication.instance.syncManager.sendLivePlaybackTick(
                            title = title,
                            artists = artists,
                            album = album,
                            isPlaying = true,
                            currentSeconds = current?.currentSongSeconds ?: 0L,
                            artworkBase64 = artworkBase64
                        )
                    }
                }
            }
        }

        if (!foundPlayingAppleMusic) {
            QuazaarApplication.instance.databaseHelper.setIdle()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        serviceJob.cancel()
    }
}
