package com.quazaar.synker.klient.service

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.quazaar.synker.klient.QuazaarApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class QuazaarNotificationListener : NotificationListenerService() {
    private val tag = "QuazaarNotifListener"
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val appName = sbn.packageName
        val isAppleMusic = appName.contains("apple", ignoreCase = true) || appName == "com.apple.android.music"
        val isNotYouTube = !appName.contains("youtube", ignoreCase = true) && !appName.contains("yt", ignoreCase = true)

        if (!isAppleMusic || !isNotYouTube) return

        val notif = sbn.notification
        val extras = notif.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        if (title.isBlank()) return

        var artworkBitmap: Bitmap? = null
        try {
            val largeIconObj = extras.get(Notification.EXTRA_LARGE_ICON)
            if (largeIconObj is Bitmap) {
                artworkBitmap = largeIconObj
            } else if (largeIconObj is Icon && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val drawable = largeIconObj.loadDrawable(this)
                if (drawable is BitmapDrawable) {
                    artworkBitmap = drawable.bitmap
                }
            }

            if (artworkBitmap == null) {
                val pictureObj = extras.get(Notification.EXTRA_PICTURE)
                if (pictureObj is Bitmap) {
                    artworkBitmap = pictureObj
                }
            }
        } catch (_: Exception) {}

        scope.launch {
            // Feeds into playback verification loop (requires 10s playback to count)
            QuazaarApplication.instance.databaseHelper.processPlaybackTick(
                title = title,
                rawArtist = text,
                isPlaying = true,
                artworkBitmap = artworkBitmap
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        val appName = sbn.packageName
        if (appName.contains("apple", ignoreCase = true) || appName == "com.apple.android.music") {
            scope.launch {
                QuazaarApplication.instance.databaseHelper.setIdle()
            }
        }
    }
}
