package com.quazaar.synker.klient.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.quazaar.synker.klient.BuildConfig
import com.quazaar.synker.klient.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val hasUpdate: Boolean,
    val currentVersion: String,
    val latestVersion: String,
    val releaseName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val htmlUrl: String,
    val publishedAt: String
)

object UpdateManager {
    private const val TAG = "UpdateManager"
    const val GITHUB_OWNER = "codershubinc"
    const val GITHUB_REPO = "synker"
    const val REPO_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
    const val API_LATEST_RELEASE = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    const val CHANNEL_ID = "synker_updates_channel"
    const val NOTIFICATION_ID = 4242

    // Shared observable state across all screens
    private val _latestUpdateInfo = MutableStateFlow<UpdateInfo?>(null)
    val latestUpdateInfo: StateFlow<UpdateInfo?> = _latestUpdateInfo.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isStatusError = MutableStateFlow(false)
    val isStatusError: StateFlow<Boolean> = _isStatusError.asStateFlow()

    /**
     * Initializes notification channel for app update alerts.
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Synker Updates"
            val descriptionText = "Notifications when a new app release is available"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Posts a system notification alerting the user that a newer version is ready.
     */
    fun showUpdateNotification(context: Context, updateInfo: UpdateInfo) {
        createNotificationChannel(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Cannot post update notification: POST_NOTIFICATIONS permission not granted")
                return
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("target_tab", "ABOUT")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "Update Available: Synker v${updateInfo.latestVersion}"
        val content = if (updateInfo.releaseName.isNotBlank()) updateInfo.releaseName else "A new version of Synker is available on GitHub."
        val bigText = buildString {
            append("Version v${updateInfo.latestVersion} is ready to install.\n")
            if (updateInfo.releaseNotes.isNotBlank()) {
                append("\nRelease Notes:\n")
                append(updateInfo.releaseNotes.take(300))
                if (updateInfo.releaseNotes.length > 300) append("...")
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
            Log.i(TAG, "Update notification displayed for v${updateInfo.latestVersion}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post update notification: ${e.message}")
        }
    }

    /**
     * Background check that automatically notifies user if a new update is found.
     * Prevents repeated notifications for the same release tag.
     */
    suspend fun checkAndNotifyIfUpdateAvailable(context: Context): Result<UpdateInfo> {
        val result = checkForUpdates(context)
        result.onSuccess { info ->
            _latestUpdateInfo.value = info
            if (info.hasUpdate) {
                val prefs = context.getSharedPreferences("synker_prefs", Context.MODE_PRIVATE)
                val lastNotified = prefs.getString("last_notified_update_version", "")
                if (lastNotified != info.latestVersion) {
                    showUpdateNotification(context, info)
                    prefs.edit().putString("last_notified_update_version", info.latestVersion).apply()
                }
            }
        }.onFailure { e ->
            Log.d(TAG, "Auto update check failed: ${e.message}")
        }
        return result
    }

    /**
     * Checks GitHub Releases for the latest version.
     */
    suspend fun checkForUpdates(context: Context): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(API_LATEST_RELEASE)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "Synker-Android-Client")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext Result.failure(Exception("GitHub API returned HTTP $code"))
            }

            val jsonStr = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val json = JSONObject(jsonStr)

            val rawTag = json.optString("tag_name", "").trim()
            val releaseName = json.optString("name", rawTag)
            val releaseNotes = json.optString("body", "")
            val htmlUrl = json.optString("html_url", REPO_URL)
            val publishedAt = json.optString("published_at", "")

            // Look for APK asset in release
            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        // Prefer release apk if available
                        if (name.contains("release", ignoreCase = true)) {
                            break
                        }
                    }
                }
            }

            val currentVer = BuildConfig.VERSION_NAME.ifEmpty { "0.0.1" }
            val cleanRemote = rawTag.removePrefix("v").trim()
            val isNewer = isNewerVersion(currentVer, cleanRemote)

            Result.success(
                UpdateInfo(
                    hasUpdate = isNewer,
                    currentVersion = currentVer,
                    latestVersion = cleanRemote.ifEmpty { rawTag },
                    releaseName = releaseName,
                    releaseNotes = releaseNotes,
                    apkDownloadUrl = apkUrl,
                    htmlUrl = htmlUrl,
                    publishedAt = publishedAt
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * UI action: Checks for updates, updates shared state flows, and notifies if found.
     */
    suspend fun checkUpdatesWithState(context: Context) {
        _isChecking.value = true
        _statusMessage.value = "Checking GitHub for updates..."
        _isStatusError.value = false

        val res = checkForUpdates(context)
        _isChecking.value = false

        if (res.isSuccess) {
            val info = res.getOrThrow()
            _latestUpdateInfo.value = info
            if (info.hasUpdate) {
                _statusMessage.value = "New version v${info.latestVersion} available!"
                _isStatusError.value = false
                showUpdateNotification(context, info)
            } else {
                _statusMessage.value = "Synker is up to date (v${info.currentVersion})"
                _isStatusError.value = false
            }
        } else {
            val err = res.exceptionOrNull()?.message ?: "Unknown error"
            _statusMessage.value = "Update check failed: $err"
            _isStatusError.value = true
        }
    }

    /**
     * Compares two semantic version strings (e.g. "0.0.3" vs "0.0.2-beta").
     */
    fun isNewerVersion(currentVer: String, remoteVer: String): Boolean {
        if (remoteVer.isBlank()) return false
        val cleanCurrent = currentVer.trim().removePrefix("v").split("-")[0]
        val cleanRemote = remoteVer.trim().removePrefix("v").split("-")[0]
        val currParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(currParts.size, remoteParts.size)
        for (i in 0 until maxLen) {
            val c = currParts.getOrElse(i) { 0 }
            val r = remoteParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /**
     * Downloads APK from URL and prompts installation with shared progress flow.
     */
    suspend fun downloadAndInstallWithState(context: Context, downloadUrl: String): Result<File> {
        _downloadProgress.value = 0f
        _statusMessage.value = "Downloading update..."
        _isStatusError.value = false

        val res = downloadAndInstall(context, downloadUrl) { progress ->
            _downloadProgress.value = progress
        }

        _downloadProgress.value = null
        if (res.isSuccess) {
            _statusMessage.value = "Update downloaded. Launching installer..."
            _isStatusError.value = false
        } else {
            val err = res.exceptionOrNull()?.message ?: "Unknown error"
            _statusMessage.value = "Installation failed: $err"
            _isStatusError.value = true
        }
        return res
    }

    /**
     * Downloads APK from URL and prompts installation.
     */
    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()
            val apkFile = File(updatesDir, "synker-update.apk")
            if (apkFile.exists()) apkFile.delete()

            var currentUrl = downloadUrl
            var redirects = 0
            while (redirects < 5) {
                val url = URL(currentUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "Synker-Android-Client")
                }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == 307 || code == 308) {
                    val location = conn.getHeaderField("Location") ?: break
                    currentUrl = location
                    conn.disconnect()
                    redirects++
                } else {
                    break
                }
            }

            val length = conn?.contentLength?.toLong() ?: -1L
            conn?.inputStream?.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (length > 0) {
                            val progress = totalRead.toFloat() / length.toFloat()
                            withContext(Dispatchers.Main) {
                                onProgress(progress.coerceIn(0f, 1f))
                            }
                        }
                    }
                    output.flush()
                }
            }

            withContext(Dispatchers.Main) {
                installApk(context, apkFile)
            }
            Result.success(apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download and install failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Triggers package installer intent for the downloaded APK file.
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file does not exist: ${apkFile.absolutePath}")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                }
            }

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
        }
    }
}
