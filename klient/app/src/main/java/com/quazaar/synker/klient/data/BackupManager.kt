package com.quazaar.synker.klient.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupManager {

    /**
     * Saves JSON backup string to the device's public Downloads directory.
     * Uses MediaStore on Android 10+ (API 29+), and direct file write on older versions,
     * with fallback to app external files dir.
     *
     * @return Result with saved path or descriptive location.
     */
    suspend fun saveBackupToDownloads(context: Context, jsonContent: String): Result<String> = withContext(Dispatchers.IO) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "quazaar_data_backup_$timeStamp.json"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return@withContext Result.failure(Exception("Failed to create MediaStore entry in Downloads"))

                resolver.openOutputStream(uri)?.use { outStream ->
                    outStream.write(jsonContent.toByteArray(Charsets.UTF_8))
                    outStream.flush()
                } ?: return@withContext Result.failure(Exception("Failed to open output stream to Downloads"))

                return@withContext Result.success("Downloads/$fileName")
            } else {
                // API 26-28 legacy path
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { outStream ->
                    outStream.write(jsonContent.toByteArray(Charsets.UTF_8))
                    outStream.flush()
                }
                return@withContext Result.success(file.absolutePath)
            }
        } catch (e: Exception) {
            // Fallback to app external files dir if public downloads write failed
            try {
                val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                val fallbackFile = File(fallbackDir, fileName)
                fallbackFile.writeText(jsonContent)
                return@withContext Result.success(fallbackFile.absolutePath)
            } catch (fallbackEx: Exception) {
                return@withContext Result.failure(e)
            }
        }
    }

    /**
     * Reads JSON content from a Uri (picked by user via SAF OpenDocument).
     */
    suspend fun readBackupFromUri(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: return@withContext Result.failure(Exception("Unable to read selected file"))
            Result.success(content)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Finds latest backup file in public or app Downloads directory if accessible.
     */
    suspend fun findLatestBackupFile(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val candidates = mutableListOf<File>()

            val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (publicDownloads.exists() && publicDownloads.canRead()) {
                val files = publicDownloads.listFiles { f ->
                    f.isFile && (f.name.startsWith("quazaar_data_") || f.name.startsWith("quazaar_backup_")) && f.name.endsWith(".json")
                }
                if (files != null) candidates.addAll(files)
            }

            val appDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (appDownloads != null && appDownloads.exists()) {
                val files = appDownloads.listFiles { f ->
                    f.isFile && (f.name.startsWith("quazaar_data_") || f.name.startsWith("quazaar_backup_")) && f.name.endsWith(".json")
                }
                if (files != null) candidates.addAll(files)
            }

            candidates.maxByOrNull { it.lastModified() }
        } catch (_: Exception) {
            null
        }
    }
}
