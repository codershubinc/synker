package com.quazaar.synker.klient.ui

import androidx.compose.animation.*
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

// Pure AMOLED palette
val AmoledBlack = Color(0xFF000000)
val AmoledSurface = Color(0xFF0A0A0A)
val AmoledCard = Color(0xFF121212)
val AmoledBorder = Color(0xFF1E1E1E)
val AmoledAccent = Color(0xFFFA2D48)
val AmoledTextPrimary = Color(0xFFFFFFFF)
val AmoledTextSecondary = Color(0xFF8E8E93)
val AmoledGreen = Color(0xFF30D158)

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
    onManualSync: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(AppTab.DASHBOARD) }
    var showClearWarningDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = AmoledBlack,
        bottomBar = {
            NavigationBar(
                containerColor = AmoledSurface,
                tonalElevation = 0.dp,
                modifier = Modifier.border(width = 1.dp, color = AmoledBorder, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                NavigationBarItem(
                    selected = selectedTab == AppTab.DASHBOARD,
                    onClick = { selectedTab = AppTab.DASHBOARD },
                    icon = { Icon(if (selectedTab == AppTab.DASHBOARD) Icons.Filled.Dashboard else Icons.Outlined.Dashboard, contentDescription = "Dashboard") },
                    label = { Text("Overview", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AmoledAccent,
                        selectedTextColor = AmoledAccent,
                        unselectedIconColor = AmoledTextSecondary,
                        unselectedTextColor = AmoledTextSecondary,
                        indicatorColor = AmoledAccent.copy(alpha = 0.15f)
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == AppTab.LIBRARY,
                    onClick = { selectedTab = AppTab.LIBRARY },
                    icon = { Icon(if (selectedTab == AppTab.LIBRARY) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic, contentDescription = "Library") },
                    label = { Text("Tracks", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AmoledAccent,
                        selectedTextColor = AmoledAccent,
                        unselectedIconColor = AmoledTextSecondary,
                        unselectedTextColor = AmoledTextSecondary,
                        indicatorColor = AmoledAccent.copy(alpha = 0.15f)
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == AppTab.ABOUT,
                    onClick = { selectedTab = AppTab.ABOUT },
                    icon = { Icon(if (selectedTab == AppTab.ABOUT) Icons.Filled.Info else Icons.Outlined.Info, contentDescription = "About") },
                    label = { Text("About", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AmoledAccent,
                        selectedTextColor = AmoledAccent,
                        unselectedIconColor = AmoledTextSecondary,
                        unselectedTextColor = AmoledTextSecondary,
                        indicatorColor = AmoledAccent.copy(alpha = 0.15f)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AmoledBlack)
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp)
            ) {
                Spacer(modifier = Modifier.height(14.dp))
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
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(AmoledAccent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text(
                                "Apple Music Tracker",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = AmoledTextPrimary
                            )
                            Text(
                                "v0.0.1",
                                fontSize = 11.sp,
                                color = AmoledTextSecondary
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
                                .background(if (isWebSocketConnected) AmoledGreen.copy(alpha = 0.15f) else AmoledCard)
                                .border(1.dp, if (isWebSocketConnected) AmoledGreen.copy(alpha = 0.4f) else AmoledBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isWebSocketConnected) AmoledGreen else AmoledTextSecondary)
                                )
                                Text(
                                    if (isWebSocketConnected) "WS LIVE" else "WS IDLE",
                                    color = if (isWebSocketConnected) AmoledGreen else AmoledTextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(AmoledCard)
                                .border(1.dp, AmoledBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "$totalPlays plays",
                                color = AmoledAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = { showClearWarningDialog = true },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(AmoledCard)
                                .border(1.dp, AmoledBorder, CircleShape)
                        ) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = "Clear logs",
                                tint = AmoledTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                if (showClearWarningDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearWarningDialog = false },
                        containerColor = AmoledSurface,
                        titleContentColor = AmoledTextPrimary,
                        textContentColor = AmoledTextSecondary,
                        icon = {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(AmoledAccent.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.WarningAmber,
                                    contentDescription = null,
                                    tint = AmoledAccent,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        },
                        title = {
                            Text(
                                "Clear All Play Logs?",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = AmoledTextPrimary
                            )
                        },
                        text = {
                            Text(
                                "This will permanently delete all logged playback history, tracked song counts, listening time, and saved album artworks. This cannot be undone.",
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = AmoledTextSecondary
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showClearWarningDialog = false
                                    onClearAllLogs()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AmoledAccent,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Clear Everything", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showClearWarningDialog = false }
                            ) {
                                Text("Cancel", color = AmoledTextSecondary, fontSize = 12.sp)
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.border(1.dp, AmoledBorder, RoundedCornerShape(16.dp))
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Permission Warning Bar
                if (!hasNotificationAccess) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF2A1208))
                            .border(1.dp, Color(0xFFFF9500).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .clickable { onOpenNotificationSettings() }
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFF9500), modifier = Modifier.size(22.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Notification Access Needed",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    "Tap to enable tracking",
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

                // Currently Playing Widget
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

@Composable
fun CurrentlyPlayingWidget(current: CurrentlyPlaying?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AmoledSurface)
            .border(1.dp, if (current?.isPlaying == true) AmoledAccent.copy(alpha = 0.5f) else AmoledBorder, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Artwork or Placeholder
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (current?.isPlaying == true) AmoledAccent.copy(alpha = 0.2f) else AmoledCard),
                contentAlignment = Alignment.Center
            ) {
                if (current?.artworkPath != null && File(current.artworkPath).exists()) {
                    AsyncImage(
                        model = File(current.artworkPath),
                        contentDescription = "Cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (current?.isPlaying == true) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = AmoledAccent, modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Filled.MusicOff, contentDescription = null, tint = AmoledTextSecondary, modifier = Modifier.size(20.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (current?.isPlaying == true) AmoledGreen else AmoledTextSecondary)
                    )
                    Text(
                        if (current?.isPlaying == true) "NOW PLAYING" else "IDLE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (current?.isPlaying == true) AmoledGreen else AmoledTextSecondary,
                        letterSpacing = 0.5.sp
                    )
                }

                Text(
                    text = current?.title ?: "No active song playing",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AmoledTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val artistAlbumText = buildString {
                    append(current?.artists?.joinToString(", ") ?: "Apple Music")
                    if (!current?.album.isNullOrBlank()) {
                        append(" • ")
                        append(current!!.album)
                    }
                }
                Text(
                    text = artistAlbumText,
                    fontSize = 12.sp,
                    color = AmoledTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (current?.isPlaying == true && current.currentSongSeconds > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AmoledCard)
                        .border(1.dp, AmoledAccent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = formatListenTime(current.currentSongSeconds),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AmoledAccent
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
            .clip(RoundedCornerShape(12.dp))
            .background(AmoledSurface)
            .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
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
                    tint = if (isSyncing) AmoledAccent else AmoledGreen,
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
                                "Sync: Not synced yet"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AmoledTextPrimary
                        )

                        // WS Indicator Dot inside sync bar
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isWebSocketConnected) AmoledGreen else Color(0xFF666666))
                        )
                    }

                    Text(
                        text = if (isWebSocketConnected) "$syncStatus • WebSocket Live" else syncStatus,
                        fontSize = 10.sp,
                        color = AmoledTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Button(
                onClick = onManualSync,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmoledCard,
                    contentColor = AmoledAccent
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .border(1.dp, AmoledAccent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .height(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Sync,
                    contentDescription = "Sync",
                    tint = AmoledAccent,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Sync Now", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

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
                    value = "$totalPlays"
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Songs",
                    value = "$uniqueTracks"
                )
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AmoledSurface)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp)
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
                            color = AmoledTextSecondary,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            formatListenTime(totalListenSeconds),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = AmoledAccent
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(AmoledAccent.copy(alpha = 0.15f))
                            .padding(10.dp)
                    ) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = AmoledAccent,
                            modifier = Modifier.size(22.dp)
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
                        .clip(RoundedCornerShape(14.dp))
                        .background(AmoledSurface)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp),
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
                            color = AmoledTextSecondary,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            "${dailyStats.size} days tracked",
                            fontSize = 11.sp,
                            color = AmoledTextSecondary
                        )
                    }

                    dailyStats.take(7).forEach { day ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AmoledCard)
                                .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = day.dayLabel,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AmoledTextPrimary
                                )
                                Text(
                                    text = if (day.playCount > 0) "${day.playCount} plays" else "Active playback",
                                    fontSize = 11.sp,
                                    color = AmoledTextSecondary
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AmoledAccent.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = formatListenTime(day.totalSeconds),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AmoledAccent
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
                    color = AmoledTextSecondary,
                    letterSpacing = 0.5.sp
                )
                Text(
                    "See All",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AmoledAccent,
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
                        color = AmoledTextSecondary,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            items(topTracks) { track ->
                TrackItemRow(track)
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun LibraryTabContent(
    tracks: List<TrackStat>,
    searchQuery: String,
    onSearchChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Search title or artist...", color = AmoledTextSecondary, fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AmoledTextSecondary) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear", tint = AmoledTextSecondary)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AmoledSurface,
                unfocusedContainerColor = AmoledSurface,
                focusedTextColor = AmoledTextPrimary,
                unfocusedTextColor = AmoledTextPrimary,
                focusedBorderColor = AmoledAccent,
                unfocusedBorderColor = AmoledBorder
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("ALL SONGS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AmoledTextSecondary, letterSpacing = 0.5.sp)
            Text("${tracks.size} songs", fontSize = 10.sp, color = AmoledTextSecondary)
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (tracks.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("No matching songs found", color = AmoledTextSecondary, fontSize = 13.sp)
                    }
                }
            } else {
                items(tracks) { track ->
                    TrackItemRow(track)
                }
            }
        }
    }
}

@Composable
fun TrackItemRow(track: TrackStat) {
    val dateStr = remember(track.lastPlayed) {
        val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        sdf.format(Date(track.lastPlayed))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AmoledSurface)
            .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Artwork Thumbnail
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AmoledCard),
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
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = AmoledTextSecondary, modifier = Modifier.size(20.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AmoledTextPrimary,
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
                    color = AmoledTextSecondary,
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
                        color = Color(0xFF6E6E73)
                    )
                    if (track.totalSeconds > 0) {
                        Text("•", fontSize = 10.sp, color = Color(0xFF48484A))
                        Text(
                            text = formatListenTime(track.totalSeconds),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = AmoledAccent.copy(alpha = 0.85f)
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
                        .clip(RoundedCornerShape(8.dp))
                        .background(AmoledCard)
                        .border(1.dp, AmoledAccent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${track.playCount}x",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AmoledAccent
                    )
                }

                if (track.totalSeconds > 0) {
                    Text(
                        text = formatListenTime(track.totalSeconds),
                        fontSize = 10.sp,
                        color = AmoledTextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun StatCard(modifier: Modifier = Modifier, title: String, value: String) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(AmoledSurface)
            .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AmoledTextSecondary, letterSpacing = 0.5.sp)
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AmoledTextPrimary)
        }
    }
}

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
                    .clip(RoundedCornerShape(14.dp))
                    .background(AmoledSurface)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("QUAZAAR", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AmoledTextSecondary, letterSpacing = 0.5.sp)
                        Text("v0.0.1", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AmoledAccent)
                    }

                    Text(
                        "Quazaar tracks your Apple Music listening habits directly on your phone. It runs silently in the background, keeping track of how many times you play your favourite songs, artists, and album covers.",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = AmoledTextPrimary.copy(alpha = 0.85f)
                    )
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AmoledSurface)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("OVERVIEW", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AmoledTextSecondary, letterSpacing = 0.5.sp)

                    AboutRow(label = "Music Service", value = "Apple Music")
                    AboutRow(label = "Total Tracks", value = "$uniqueTracks songs")
                    AboutRow(label = "Total Plays", value = "$totalPlays plays")
                    AboutRow(label = "Total Time", value = formatListenTime(totalListenSeconds))
                    AboutRow(label = "Interface", value = "AMOLED Black")
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AmoledSurface)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("DATA", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AmoledTextSecondary, letterSpacing = 0.5.sp)
                    Text(
                        "Wipe all tracked song statistics, play history logs, artwork files, and reset counters.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = AmoledTextSecondary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedButton(
                        onClick = onClearClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = AmoledAccent
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AmoledAccent.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
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
        Text(label, color = AmoledTextSecondary, fontSize = 12.sp)
        Text(value, color = AmoledTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
