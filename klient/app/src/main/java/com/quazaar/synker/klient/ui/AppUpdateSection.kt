package com.quazaar.synker.klient.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.quazaar.synker.klient.update.UpdateInfo
import com.quazaar.synker.klient.update.UpdateManager
import kotlinx.coroutines.launch

@Composable
fun AppUpdateSection(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val updateInfo by UpdateManager.latestUpdateInfo.collectAsState()
    val isChecking by UpdateManager.isChecking.collectAsState()
    val downloadProgress by UpdateManager.downloadProgress.collectAsState()
    val statusMessage by UpdateManager.statusMessage.collectAsState()
    val isStatusError by UpdateManager.isStatusError.collectAsState()

    val currentVersion = BuildConfig.VERSION_NAME.ifEmpty { "0.0.1" }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AppleSurface)
            .border(1.dp, AppleBorderHighlight, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = AppleIndigo,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "APP UPDATES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppleTextSecondary,
                        letterSpacing = 0.6.sp
                    )
                }

                Text(
                    "v$currentVersion",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppleAccent
                )
            }

            Text(
                "Check GitHub Releases (${UpdateManager.GITHUB_REPO}) for app updates and install the latest release APK automatically.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = AppleTextSecondary
            )

            // Update Info Card
            val info = updateInfo
            if (info != null) {
                if (info.hasUpdate) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppleGreen.copy(alpha = 0.12f))
                            .border(1.dp, AppleGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "New Version Available: v${info.latestVersion}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppleGreen
                                )
                                Text(
                                    "GitHub",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppleGreen.copy(alpha = 0.8f)
                                )
                            }

                            if (info.releaseNotes.isNotBlank()) {
                                Text(
                                    info.releaseNotes.take(220) + if (info.releaseNotes.length > 220) "..." else "",
                                    fontSize = 11.sp,
                                    color = AppleTextPrimary.copy(alpha = 0.85f),
                                    lineHeight = 15.sp
                                )
                            }

                            // Beta Breaking Changes Warning
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AppleAmber.copy(alpha = 0.15f))
                                    .border(1.dp, AppleAmber.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = AppleAmber,
                                        modifier = Modifier.size(15.dp).padding(top = 1.dp)
                                    )
                                    Text(
                                        "Beta Notice: Breaking changes may occur between releases. Please export a JSON backup from Settings before installing.",
                                        fontSize = 11.sp,
                                        color = AppleAmber,
                                        lineHeight = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // Download progress / Install button
                    if (downloadProgress != null) {
                        val progress = downloadProgress ?: 0f
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = AppleGreen
                            )
                            Text(
                                "Downloading: ${(progress * 100).toInt()}%",
                                fontSize = 11.sp,
                                color = AppleTextSecondary
                            )
                        }
                    } else {
                        val apkUrl = info.apkDownloadUrl
                        if (apkUrl != null) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        UpdateManager.downloadAndInstallWithState(context, apkUrl)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppleGreen)
                            ) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = AppleBlack
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Download & Install v${info.latestVersion}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppleBlack
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = AppleGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "You are on the latest version (v${info.currentVersion})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppleGreen
                        )
                    }
                }
            }

            // Status message feedback
            if (!statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage ?: "",
                    fontSize = 12.sp,
                    color = if (isStatusError) MaterialTheme.colorScheme.error else AppleTextSecondary
                )
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            UpdateManager.checkUpdatesWithState(context)
                        }
                    },
                    enabled = !isChecking && downloadProgress == null,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppleTextPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppleBorderHighlight)
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = AppleTextPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Checking...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Check for Updates", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(info?.htmlUrl ?: UpdateManager.REPO_URL)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(browserIntent)
                    },
                    modifier = Modifier.weight(0.75f).height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppleTextSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppleBorderHighlight)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Releases", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
