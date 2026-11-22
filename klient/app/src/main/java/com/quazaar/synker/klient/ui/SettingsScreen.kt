package com.quazaar.synker.klient.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quazaar.synker.klient.BuildConfig
import com.quazaar.synker.klient.QuazaarApplication
import com.quazaar.synker.klient.data.BackupManager
import com.quazaar.synker.klient.update.UpdateInfo
import com.quazaar.synker.klient.update.UpdateManager
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onDataRestored: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var syncStatus by remember { mutableStateOf("") }
    var isStatusError by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    val syncManager = QuazaarApplication.instance.syncManager
    val dbHelper = QuazaarApplication.instance.databaseHelper

    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var pendingRestoreFile by remember { mutableStateOf<File?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var confirmDialogMessage by remember { mutableStateOf("") }

    var latestBackupFile by remember { mutableStateOf<File?>(null) }

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateDownloadProgress by remember { mutableStateOf(0f) }

    // Check for latest backup in Downloads and auto-check updates on screen load
    LaunchedEffect(Unit) {
        latestBackupFile = BackupManager.findLatestBackupFile(context)
        try {
            val res = UpdateManager.checkForUpdates(context)
            if (res.isSuccess) {
                updateInfo = res.getOrThrow()
            }
        } catch (_: Exception) {}
    }

    // File picker for JSON backup files
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingRestoreUri = uri
            pendingRestoreFile = null
            confirmDialogMessage = "Restore data from selected file? This will replace your current local tracks and play logs."
            showConfirmDialog = true
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showConfirmDialog = false
                pendingRestoreUri = null
                pendingRestoreFile = null
            },
            title = {
                Text(
                    "Confirm Data Restore",
                    color = AppleTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    confirmDialogMessage,
                    color = AppleTextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        val uri = pendingRestoreUri
                        val file = pendingRestoreFile
                        coroutineScope.launch {
                            isProcessing = true
                            syncStatus = "Restoring data from backup..."
                            isStatusError = false
                            try {
                                val jsonString = when {
                                    uri != null -> {
                                        val readRes = BackupManager.readBackupFromUri(context, uri)
                                        if (readRes.isFailure) {
                                            throw (readRes.exceptionOrNull() ?: Exception("Failed to read file"))
                                        }
                                        readRes.getOrThrow()
                                    }
                                    file != null -> file.readText()
                                    else -> throw Exception("No backup source specified")
                                }

                                val result = dbHelper.restoreDataFromJson(jsonString)
                                if (result.success) {
                                    syncStatus = "Successfully restored ${result.tracksCount} tracks and ${result.playsCount} play logs!"
                                    isStatusError = false
                                    onDataRestored()
                                    // Refresh latest backup file reference
                                    latestBackupFile = BackupManager.findLatestBackupFile(context)
                                } else {
                                    syncStatus = "Restore failed: ${result.errorMessage}"
                                    isStatusError = true
                                }
                            } catch (e: Exception) {
                                syncStatus = "Error restoring data: ${e.message}"
                                isStatusError = true
                            } finally {
                                isProcessing = false
                                pendingRestoreUri = null
                                pendingRestoreFile = null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleAccent)
                ) {
                    Text("Proceed Restore", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        pendingRestoreUri = null
                        pendingRestoreFile = null
                    }
                ) {
                    Text("Cancel", color = AppleTextSecondary)
                }
            },
            containerColor = AppleCardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings & Data",
                        fontWeight = FontWeight.Bold,
                        color = AppleTextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = AppleTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppleBlack
                )
            )
        },
        containerColor = AppleBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                "Client Data Management",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AppleTextPrimary
            )
            Text(
                "Backup your listening history to JSON in your Downloads folder and retrieve or restore data at any time.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppleTextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            // Status Card
            if (syncStatus.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isStatusError) Color(0x33FF453A) else Color(0x2230D158))
                        .border(
                            1.dp,
                            if (isStatusError) Color(0xFFFF453A) else AppleGreen,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(14.dp)
                ) {
                    Text(
                        text = syncStatus,
                        color = if (isStatusError) Color(0xFFFF8080) else AppleGreen,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── Section 1: Backup Data ──────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppleCardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppleBorder)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = AppleCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "Export & Backup",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppleTextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Export all tracks, play counts, daily statistics, and timestamps into a formatted JSON file in your Downloads folder.",
                        fontSize = 13.sp,
                        color = AppleTextSecondary,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isProcessing = true
                                syncStatus = "Generating JSON backup..."
                                isStatusError = false
                                try {
                                    val jsonString = syncManager.buildSyncPayload(dbHelper, forceOverride = true)
                                    val formattedJson = org.json.JSONObject(jsonString).toString(4)

                                    val saveResult = BackupManager.saveBackupToDownloads(context, formattedJson)
                                    if (saveResult.isSuccess) {
                                        val path = saveResult.getOrThrow()
                                        syncStatus = "Backup saved successfully to Downloads:\n$path"
                                        isStatusError = false
                                        latestBackupFile = BackupManager.findLatestBackupFile(context)
                                    } else {
                                        syncStatus = "Backup failed: ${saveResult.exceptionOrNull()?.message}"
                                        isStatusError = true
                                    }
                                } catch (e: Exception) {
                                    syncStatus = "Error creating backup: ${e.message}"
                                    isStatusError = true
                                } finally {
                                    isProcessing = false
                                }
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download Data as JSON", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── Section 2: Retrieve & Restore Data ──────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppleCardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppleBorder)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = null,
                            tint = AppleGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "Retrieve & Restore",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppleTextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Retrieve your listening history from a JSON backup file or pull the latest state from your synced daemon server.",
                        fontSize = 13.sp,
                        color = AppleTextSecondary,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Option A: Pick JSON file from device
                    Button(
                        onClick = {
                            openDocumentLauncher.launch(
                                arrayOf("application/json", "text/*", "*/*")
                            )
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleGreen)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retrieve from JSON File (Browse)", fontWeight = FontWeight.SemiBold, color = AppleBlack)
                    }

                    // Option B: Quick restore from latest backup if found
                    val backupFile = latestBackupFile
                    if (backupFile != null && backupFile.exists()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val dateStr = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(backupFile.lastModified()))
                        OutlinedButton(
                            onClick = {
                                pendingRestoreFile = backupFile
                                pendingRestoreUri = null
                                confirmDialogMessage = "Restore data from latest backup (${backupFile.name}, $dateStr)?"
                                showConfirmDialog = true
                            },
                            enabled = !isProcessing,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AppleBorderHighlight),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppleCyan)
                        ) {
                            Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Quick Restore (${backupFile.name.take(20)}...)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Option C: Pull & Restore from Server
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isProcessing = true
                                syncStatus = "Pulling data from daemon server..."
                                isStatusError = false
                                val success = syncManager.pullDataFromServer(dbHelper)
                                if (success) {
                                    syncStatus = "Successfully restored database from daemon server!"
                                    isStatusError = false
                                    onDataRestored()
                                } else {
                                    syncStatus = "Failed to pull data from server. Ensure daemon is reachable."
                                    isStatusError = true
                                }
                                isProcessing = false
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AppleBorderHighlight),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppleTextPrimary)
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pull & Restore from Daemon Server", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── Section 3: Server Sync Actions ──────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppleCardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppleBorder)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = AppleAccent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "Server Overwrite",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppleTextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Force-pushes this device's full database state to overwrite the remote server daemon's records.",
                        fontSize = 13.sp,
                        color = AppleTextSecondary,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isProcessing = true
                                syncStatus = "Force syncing with server..."
                                isStatusError = false
                                val success = syncManager.performSync(forceOverride = true)
                                if (success) {
                                    syncStatus = "Server statistics successfully overwritten!"
                                    isStatusError = false
                                } else {
                                    syncStatus = "Server overwrite failed. Check connection."
                                    isStatusError = true
                                }
                                isProcessing = false
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleAccent)
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Force Overwrite Server Stats", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── Section 4: App Updates & GitHub Releases ─────────────────────
            AppUpdateSection()

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
