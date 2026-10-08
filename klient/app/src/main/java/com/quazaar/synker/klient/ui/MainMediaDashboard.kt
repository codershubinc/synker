package com.quazaar.synker.klient.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.quazaar.synker.klient.data.CurrentlyPlaying
import com.quazaar.synker.klient.data.DayStat
import com.quazaar.synker.klient.data.TrackStat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ── Apple Music Palette ──────────────────────────────────────────────────────
val AppleBlack = Color(0xFF000000)
val AppleSurface = Color(0xFF141416)
val AppleCardBg = Color(0xFF1C1C1E)
val AppleCardHover = Color(0xFF28282C)
val AppleBorder = Color(0xFF2C2C2E)
val AppleBorderHighlight = Color(0xFF3A3A3C)

// Iconic Apple Music Vibrant Red / Crimson Pink
val AppleAccent = Color(0xFFFA243C)
val AppleAccentHover = Color(0xFFFF375F)
val AppleAccentGlow = Color(0x40FA243C)

// Extended Apple System Colors
val AppleGreen = Color(0xFF30D158)
val AppleIndigo = Color(0xFF5E5CE6)
val ApplePurple = Color(0xFFBF5AF2)
val AppleCyan = Color(0xFF64D2FF)
val AppleAmber = Color(0xFFFF9F0A)

// Typography Colors
val AppleTextPrimary = Color(0xFFFFFFFF)
val AppleTextSecondary = Color(0xFF8E8E93)
val AppleTextTertiary = Color(0xFF636366)

enum class AppTab {
    DASHBOARD,
    LIBRARY,
    ABOUT
}

@Composable
fun MainMediaDashboard(
    currentPlaying: CurrentlyPlaying?,
    totalPlays: Int,
    uniqueTracksCount: Int,
    totalListenSeconds: Long,
    allTracks: List<TrackStat>,
    dailyStats: List<DayStat>,
    hasNotificationAccess: Boolean,
    onOpenNotificationSettings: () -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onClearAllLogs: () -> Unit,
    lastSyncTimestamp: Long = 0L,
    syncStatus: String = "Ready",
    isWebSocketConnected: Boolean = false,
    onManualSync: () -> Unit = {},
    onRetryWebSocket: () -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(AppTab.DASHBOARD) }
    var showClearWarningDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = AppleBlack,
        bottomBar = {
            NavigationBar(
                containerColor = AppleSurface.copy(alpha = 0.95f),
                tonalElevation = 0.dp,
                modifier = Modifier.border(
                    width = 1.dp,
                    color = AppleBorder,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                )
            ) {
                NavigationBarItem(
                    selected = selectedTab == AppTab.DASHBOARD,
                    onClick = { selectedTab = AppTab.DASHBOARD },
                    icon = {
                        Icon(
                            if (selectedTab == AppTab.DASHBOARD) Icons.Filled.PlayCircle else Icons.Outlined.PlayCircle,
                            contentDescription = "Listen Now"
                        )
                    },
                    label = { Text("Listen Now", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AppleAccent,
                        selectedTextColor = AppleAccent,
                        unselectedIconColor = AppleTextSecondary,
                        unselectedTextColor = AppleTextSecondary,
                        indicatorColor = AppleAccent.copy(alpha = 0.15f)
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == AppTab.LIBRARY,
                    onClick = { selectedTab = AppTab.LIBRARY },
                    icon = {
                        Icon(
                            if (selectedTab == AppTab.LIBRARY) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic,
                            contentDescription = "Songs"
                        )
                    },
                    label = { Text("Songs", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AppleAccent,
                        selectedTextColor = AppleAccent,
                        unselectedIconColor = AppleTextSecondary,
                        unselectedTextColor = AppleTextSecondary,
                        indicatorColor = AppleAccent.copy(alpha = 0.15f)
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == AppTab.ABOUT,
                    onClick = { selectedTab = AppTab.ABOUT },
                    icon = {
                        Icon(
                            if (selectedTab == AppTab.ABOUT) Icons.Filled.Info else Icons.Outlined.Info,
                            contentDescription = "About"
                        )
                    },
                    label = { Text("About", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AppleAccent,
                        selectedTextColor = AppleAccent,
                        unselectedIconColor = AppleTextSecondary,
                        unselectedTextColor = AppleTextSecondary,
                        indicatorColor = AppleAccent.copy(alpha = 0.15f)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppleBlack)
                .padding(innerPadding)
        ) {
            // Apple Music Ambient Radiant Aurora in background
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                AppleAccent.copy(alpha = 0.15f),
                                AppleIndigo.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp)
            ) {
                Spacer(modifier = Modifier.height(14.dp))

                // Apple Music Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(AppleAccent, AppleAccentHover)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    " Music",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = AppleTextPrimary,
                                    letterSpacing = (-0.3).sp
                                )
                                Text(
                                    "Synker",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppleAccent,
                                    letterSpacing = (-0.3).sp
                                )
                            }
                            Text(
                                "Live Listening Intelligence",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppleTextSecondary
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Live WebSocket Status Pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (isWebSocketConnected) AppleGreen.copy(alpha = 0.15f)
                                    else AppleAmber.copy(alpha = 0.12f)
                                )
                                .border(
                                    1.dp,
                                    if (isWebSocketConnected) AppleGreen.copy(alpha = 0.4f)
                                    else AppleAmber.copy(alpha = 0.35f),
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable { onRetryWebSocket() }
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                if (isWebSocketConnected) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(AppleGreen)
                                    )
                                    Text(
                                        "LIVE",
                                        color = AppleGreen,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = "Retry WebSocket",
                                        tint = AppleAmber,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Text(
                                        "RETRY",
                                        color = AppleAmber,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Plays committed pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(AppleCardBg)
                                .border(1.dp, AppleBorderHighlight, RoundedCornerShape(999.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "$totalPlays plays",
                                color = AppleAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(AppleCardBg)
                                .border(1.dp, AppleBorder, CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = AppleTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                if (showClearWarningDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearWarningDialog = false },
                        containerColor = AppleSurface,
                        titleContentColor = AppleTextPrimary,
                        textContentColor = AppleTextSecondary,
                        icon = {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(AppleAccent.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.WarningAmber,
                                    contentDescription = null,
                                    tint = AppleAccent,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        },
                        title = {
                            Text(
                                "Clear All Play Logs?",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppleTextPrimary
                            )
                        },
                        text = {
                            Text(
                                "This will permanently delete all logged playback history, tracked song counts, listening time, and saved album artworks. This cannot be undone.",
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = AppleTextSecondary
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showClearWarningDialog = false
                                    onClearAllLogs()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppleAccent,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Clear Everything", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showClearWarningDialog = false }
                            ) {
                                Text("Cancel", color = AppleTextSecondary, fontSize = 12.sp)
                            }
                        },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.border(1.dp, AppleBorderHighlight, RoundedCornerShape(18.dp))
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Permission Warning Bar
                if (!hasNotificationAccess) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF261808))
                            .border(1.dp, AppleAmber.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                            .clickable { onOpenNotificationSettings() }
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = AppleAmber, modifier = Modifier.size(22.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Notification Access Needed",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    "Tap to enable Apple Music background tracking",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Sync Status Bar with Manual Sync Button
                SyncStatusBar(
                    lastSyncTimestamp = lastSyncTimestamp,
                    syncStatus = syncStatus,
                    isWebSocketConnected = isWebSocketConnected,
                    onManualSync = onManualSync
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Apple Music Currently Playing Hero Card
                CurrentlyPlayingWidget(currentPlaying)

                Spacer(modifier = Modifier.height(14.dp))

                when (selectedTab) {
                    AppTab.DASHBOARD -> DashboardTabContent(
                        totalPlays = totalPlays,
                        uniqueTracks = uniqueTracksCount,
                        totalListenSeconds = totalListenSeconds,
                        dailyStats = dailyStats,
                        topTracks = allTracks.take(15),
                        onViewAllClick = { selectedTab = AppTab.LIBRARY }
                    )
                    AppTab.LIBRARY -> LibraryTabContent(
                        tracks = allTracks,
                        searchQuery = searchQuery,
                        onSearchChange = onSearchChange
                    )
                    AppTab.ABOUT -> AboutTabContent(
                        totalPlays = totalPlays,
                        uniqueTracks = uniqueTracksCount,
                        totalListenSeconds = totalListenSeconds,
                        onClearClick = { showClearWarningDialog = true }
                    )
                }
            }
        }
    }
}

// ── Apple Music Animated Equalizer ───────────────────────────────────────────
@Composable
fun AppleEqualizerWave(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "eqWave")

    val h1 by transition.animateFloat(
        initialValue = 4f, targetValue = 16f,
        animationSpec = infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Reverse),
        label = "h1"
    )
    val h2 by transition.animateFloat(
        initialValue = 14f, targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
        label = "h2"
    )
    val h3 by transition.animateFloat(
        initialValue = 6f, targetValue = 18f,
        animationSpec = infiniteRepeatable(tween(920, easing = LinearEasing), RepeatMode.Reverse),
        label = "h3"
    )
    val h4 by transition.animateFloat(
        initialValue = 12f, targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(720, easing = LinearEasing), RepeatMode.Reverse),
        label = "h4"
    )

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.height(18.dp)
    ) {
        listOf(h1, h2, h3, h4).forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(if (isPlaying) h.dp else 4.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(AppleAccent)
            )
        }
    }
}

// ── Apple Music "Now Playing" Widget ─────────────────────────────────────────
@Composable
fun CurrentlyPlayingWidget(current: CurrentlyPlaying?) {
    val isPlaying = current?.isPlaying == true

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF222026).copy(alpha = 0.85f),
                        Color(0xFF16151A).copy(alpha = 0.95f)
                    )
                )
            )
            .border(
                1.dp,
                if (isPlaying) AppleAccent.copy(alpha = 0.45f) else AppleBorderHighlight,
                RoundedCornerShape(18.dp)
            )
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Album Artwork Sleeve with Apple Squircle and shadow
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isPlaying) AppleAccent.copy(alpha = 0.2f) else AppleCardBg)
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (current?.artworkPath != null && File(current.artworkPath).exists()) {
                    AsyncImage(
                        model = File(current.artworkPath),
                        contentDescription = "Cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (isPlaying) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = AppleAccent, modifier = Modifier.size(26.dp))
                } else {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = AppleTextSecondary, modifier = Modifier.size(24.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppleEqualizerWave(isPlaying = isPlaying)

                    Text(
                        if (isPlaying) "NOW PLAYING" else "IDLE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isPlaying) AppleAccent else AppleTextSecondary,
                        letterSpacing = 0.8.sp
                    )

                    // Lossless badge
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "LOSSLESS",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppleTextPrimary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = current?.title ?: "No track currently playing",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppleTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val artistAlbumText = buildString {
                    append(current?.artists?.joinToString(", ") ?: "Play Apple Music to begin")
                    if (!current?.album.isNullOrBlank()) {
                        append(" • ")
                        append(current!!.album)
                    }
                }
                Text(
                    text = artistAlbumText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isPlaying) AppleAccentHover else AppleTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isPlaying && current.currentSongSeconds > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppleCardBg)
                        .border(1.dp, AppleAccent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = formatListenTime(current.currentSongSeconds),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppleAccent
                    )
                }
            }
        }
    }
}

fun formatListenTime(seconds: Long): String {
    if (seconds <= 0) return "0s"
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hrs > 0 -> "${hrs}h ${mins}m ${secs}s"
        mins > 0 -> "${mins}m ${secs}s"
        else -> "${secs}s"
    }
}

@Composable
fun SyncStatusBar(
    lastSyncTimestamp: Long,
    syncStatus: String,
    isWebSocketConnected: Boolean = false,
    onManualSync: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppleSurface)
            .border(1.dp, AppleBorderHighlight, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                val isSyncing = syncStatus.contains("Syncing", ignoreCase = true)
                Icon(
                    imageVector = if (isSyncing) Icons.Filled.Sync else Icons.Filled.CloudDone,
                    contentDescription = null,
                    tint = if (isSyncing) AppleAccent else AppleGreen,
                    modifier = Modifier.size(16.dp)
                )

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (lastSyncTimestamp > 0) {
                                val timeStr = SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(lastSyncTimestamp))
                                "Last Synced: $timeStr"
                            } else {
                                "Sync: Ready"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppleTextPrimary
                        )

                        // WS Indicator Dot inside sync bar
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isWebSocketConnected) AppleGreen else Color(0xFF666666))
                        )
                    }

                    Text(
                        text = if (isWebSocketConnected) "$syncStatus • WebSocket Live" else syncStatus,
                        fontSize = 10.sp,
                        color = AppleTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Button(
                onClick = onManualSync,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppleCardBg,
                    contentColor = AppleAccent
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .border(1.dp, AppleAccent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .height(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Sync,
                    contentDescription = "Sync",
                    tint = AppleAccent,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Sync Now", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Apple Music Replay Analytics Overview ────────────────────────────────────
@Composable
fun DashboardTabContent(
    totalPlays: Int,
    uniqueTracks: Int,
    totalListenSeconds: Long,
    dailyStats: List<DayStat>,
    topTracks: List<TrackStat>,
    onViewAllClick: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Total Plays",
                    value = "$totalPlays",
                    iconColor = AppleIndigo
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Songs",
                    value = "$uniqueTracks",
                    iconColor = AppleCyan
                )
            }
        }

        // Total Listening Time Hero Replay Card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                AppleAccent.copy(alpha = 0.16f),
                                AppleCardBg
                            )
                        )
                    )
                    .border(1.dp, AppleBorderHighlight, RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "TOTAL LISTENING TIME",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppleTextSecondary,
                            letterSpacing = 0.6.sp
                        )
                        Text(
                            formatListenTime(totalListenSeconds),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = AppleTextPrimary
                        )
                        Text(
                            "Across Apple Music sessions",
                            fontSize = 11.sp,
                            color = AppleTextSecondary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppleAccent.copy(alpha = 0.2f))
                            .border(1.dp, AppleAccent.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = AppleAccent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // Daily Activity breakdown
        if (dailyStats.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(AppleSurface)
                        .border(1.dp, AppleBorderHighlight, RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "DAILY LISTENING TIME",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppleTextSecondary,
                            letterSpacing = 0.6.sp
                        )
                        Text(
                            "${dailyStats.size} days tracked",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppleTextSecondary
                        )
                    }

                    dailyStats.take(7).forEach { day ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppleCardBg)
                                .border(1.dp, AppleBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = day.dayLabel,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppleTextPrimary
                                )
                                Text(
                                    text = if (day.playCount > 0) "${day.playCount} plays" else "Active playback",
                                    fontSize = 11.sp,
                                    color = AppleTextSecondary
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AppleAccent.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = formatListenTime(day.totalSeconds),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppleAccent
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "MOST PLAYED",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppleTextSecondary,
                    letterSpacing = 0.6.sp
                )
                Text(
                    "See All",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppleAccent,
                    modifier = Modifier.clickable { onViewAllClick() }
                )
            }
        }

        if (topTracks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No songs tracked yet.\nPlay a song on Apple Music to start tracking.",
                        color = AppleTextSecondary,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            items(topTracks.withIndex().toList()) { (idx, track) ->
                TrackItemRow(track = track, rankIndex = idx + 1)
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

// ── Apple Music Library Tab ──────────────────────────────────────────────────
@Composable
fun LibraryTabContent(
    tracks: List<TrackStat>,
    searchQuery: String,
    onSearchChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Apple Music Search Capsule
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Search songs, artists, albums...", color = AppleTextSecondary, fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AppleTextSecondary) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear", tint = AppleTextSecondary)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AppleSurface,
                unfocusedContainerColor = AppleSurface,
                focusedTextColor = AppleTextPrimary,
                unfocusedTextColor = AppleTextPrimary,
                focusedBorderColor = AppleAccent,
                unfocusedBorderColor = AppleBorder
            ),
            shape = RoundedCornerShape(999.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("ALL SONGS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppleTextSecondary, letterSpacing = 0.6.sp)
            Text("${tracks.size} songs", fontSize = 10.sp, color = AppleTextSecondary)
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (tracks.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("No matching songs found", color = AppleTextSecondary, fontSize = 13.sp)
                    }
                }
            } else {
                items(tracks.withIndex().toList()) { (idx, track) ->
                    TrackItemRow(track = track, rankIndex = idx + 1)
                }
            }
        }
    }
}

// ── Apple Music Track Item Row ───────────────────────────────────────────────
@Composable
fun TrackItemRow(track: TrackStat, rankIndex: Int? = null) {
    val dateStr = remember(track.lastPlayed) {
        val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        sdf.format(Date(track.lastPlayed))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppleSurface)
            .border(1.dp, AppleBorder, RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Optional rank number (Apple Top Charts)
            if (rankIndex != null) {
                Text(
                    text = if (rankIndex < 10) "0$rankIndex" else "$rankIndex",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppleTextTertiary,
                    modifier = Modifier.width(22.dp)
                )
            }

            // High resolution album artwork thumbnail with Apple Squircle
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppleCardBg)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (track.artworkPath != null && File(track.artworkPath).exists()) {
                    AsyncImage(
                        model = File(track.artworkPath),
                        contentDescription = "Cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = AppleTextSecondary, modifier = Modifier.size(20.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppleTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = if (track.album.isNotBlank()) {
                    "${track.artists.joinToString(", ")} • ${track.album}"
                } else {
                    track.artists.joinToString(", ")
                }
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppleAccentHover,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Last: $dateStr",
                        fontSize = 10.sp,
                        color = AppleTextTertiary
                    )
                    if (track.totalSeconds > 0) {
                        Text("•", fontSize = 10.sp, color = AppleTextTertiary)
                        Text(
                            text = formatListenTime(track.totalSeconds),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppleGreen
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(AppleAccent.copy(alpha = 0.15f))
                        .border(1.dp, AppleAccent.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "${track.playCount}x",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppleAccent
                    )
                }

                if (track.totalSeconds > 0) {
                    Text(
                        text = formatListenTime(track.totalSeconds),
                        fontSize = 10.sp,
                        color = AppleTextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ── Apple Music Stat Card ────────────────────────────────────────────────────
@Composable
fun StatCard(modifier: Modifier = Modifier, title: String, value: String, iconColor: Color = AppleIndigo) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(AppleSurface)
            .border(1.dp, AppleBorderHighlight, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppleTextSecondary,
                    letterSpacing = 0.6.sp
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(iconColor.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.BarChart,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = AppleTextPrimary)
        }
    }
}

// ── Apple Music About Tab ────────────────────────────────────────────────────
@Composable
fun AboutTabContent(
    totalPlays: Int,
    uniqueTracks: Int,
    totalListenSeconds: Long,
    onClearClick: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppleSurface)
                    .border(1.dp, AppleBorderHighlight, RoundedCornerShape(18.dp))
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("", fontSize = 14.sp, color = AppleAccent)
                            Text("SYNKER", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppleTextPrimary, letterSpacing = 0.6.sp)
                        }
                        Text("v0.0.1", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppleAccent)
                    }

                    Text(
                        "Synker tracks your Apple Music listening habits directly on your phone. It runs silently in the background, keeping track of how many times you play your favourite songs, artists, and album covers.",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = AppleTextPrimary.copy(alpha = 0.85f)
                    )
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppleSurface)
                    .border(1.dp, AppleBorderHighlight, RoundedCornerShape(18.dp))
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("OVERVIEW", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppleTextSecondary, letterSpacing = 0.6.sp)

                    AboutRow(label = "Music Service", value = "Apple Music")
                    AboutRow(label = "Total Tracks", value = "$uniqueTracks songs")
                    AboutRow(label = "Total Plays", value = "$totalPlays plays")
                    AboutRow(label = "Total Time", value = formatListenTime(totalListenSeconds))
                    AboutRow(label = "Interface Theme", value = "Apple Music Dark")
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppleSurface)
                    .border(1.dp, AppleBorderHighlight, RoundedCornerShape(18.dp))
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("DATA", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppleTextSecondary, letterSpacing = 0.6.sp)
                    Text(
                        "Wipe all tracked song statistics, play history logs, artwork files, and reset counters.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = AppleTextSecondary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedButton(
                        onClick = onClearClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = AppleAccent
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AppleAccent.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear All Logs & History", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = AppleTextSecondary, fontSize = 12.sp)
        Text(value, color = AppleTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
