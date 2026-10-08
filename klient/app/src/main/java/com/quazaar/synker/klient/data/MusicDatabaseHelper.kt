package com.quazaar.synker.klient.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

data class TrackStat(
    val songId: String,
    val title: String,
    val artists: List<String>,
    val album: String = "",
    val playCount: Int,
    val firstPlayed: Long,
    val lastPlayed: Long,
    val artworkPath: String? = null,
    val totalSeconds: Long = 0L
)

data class PlayLog(
    val id: Long,
    val songId: String,
    val title: String,
    val artists: List<String>,
    val album: String = "",
    val timestamp: Long,
    val artworkPath: String? = null
)

data class CurrentlyPlaying(
    val songId: String,
    val title: String,
    val artists: List<String>,
    val album: String = "",
    val isPlaying: Boolean = true,
    val artworkPath: String? = null,
    val firstDetectedAt: Long = System.currentTimeMillis(),
    val isCommitted: Boolean = false,
    val currentSongSeconds: Long = 0L
)

data class DayStat(
    val date: String,          // e.g. "2026-09-27"
    val dayLabel: String,      // e.g. "Today", "Yesterday", "Sat, Sep 26"
    val totalSeconds: Long,    // total seconds listened on that day
    val playCount: Int         // plays recorded on that day
)

class MusicDatabaseHelper(private val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "quazaar_music.db"
        const val DATABASE_VERSION = 5

        const val TABLE_TRACKS = "tracks"
        const val COL_SONG_ID = "song_id"
        const val COL_TITLE = "title"
        const val COL_ARTISTS = "artists_json"
        const val COL_ALBUM = "album"
        const val COL_PLAY_COUNT = "play_count"
        const val COL_FIRST_PLAYED = "first_played"
        const val COL_LAST_PLAYED = "last_played"
        const val COL_ARTWORK_PATH = "artwork_path"
        const val COL_TOTAL_SECONDS = "total_seconds"

        const val TABLE_PLAY_LOGS = "play_logs"
        const val COL_LOG_ID = "id"
        const val COL_LOG_SONG_ID = "song_id"
        const val COL_LOG_TITLE = "title"
        const val COL_LOG_ARTISTS = "artists_json"
        const val COL_LOG_ALBUM = "album"
        const val COL_LOG_TIMESTAMP = "timestamp"
        const val COL_LOG_ARTWORK_PATH = "artwork_path"

        const val TABLE_DAILY_STATS = "daily_stats"
        const val COL_DAY_DATE = "date"            // Format: YYYY-MM-DD
        const val COL_DAY_SECONDS = "total_seconds"
        const val COL_DAY_PLAYS = "play_count"

        const val TABLE_META = "metadata"
        const val KEY_TOTAL_SECONDS = "total_listen_seconds"

        // Required continuous playback time (10 seconds) before committing a play count
        const val MIN_PLAY_DURATION_MS = 10_000L
        // Cooldown before the same song can be counted again (2 minutes)
        const val SONG_COUNT_COOLDOWN_MS = 120_000L
    }

    private val dbMutex = Mutex()

    private val artworkDir: File by lazy {
        val dir = File(context.filesDir, "artworks")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private val _currentPlaying = MutableStateFlow<CurrentlyPlaying?>(null)
    val currentPlaying: StateFlow<CurrentlyPlaying?> = _currentPlaying.asStateFlow()

    private val _totalListenSeconds = MutableStateFlow(0L)
    val totalListenSeconds: StateFlow<Long> = _totalListenSeconds.asStateFlow()

    // In-memory cache of last committed timestamps per songId to prevent duplicate counts
    private val songLastCommittedMap = mutableMapOf<String, Long>()

    // For second-by-second ticking
    private var lastTickTimestamp: Long = 0L

    init {
        // Load initial total listen seconds
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT value FROM metadata WHERE key = ?", arrayOf(KEY_TOTAL_SECONDS))
        if (cursor.moveToFirst()) {
            _totalListenSeconds.value = cursor.getLong(0)
        }
        cursor.close()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_TRACKS (
                $COL_SONG_ID TEXT PRIMARY KEY,
                $COL_TITLE TEXT NOT NULL,
                $COL_ARTISTS TEXT NOT NULL,
                $COL_ALBUM TEXT,
                $COL_PLAY_COUNT INTEGER NOT NULL DEFAULT 1,
                $COL_FIRST_PLAYED INTEGER NOT NULL,
                $COL_LAST_PLAYED INTEGER NOT NULL,
                $COL_ARTWORK_PATH TEXT,
                $COL_TOTAL_SECONDS INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_PLAY_LOGS (
                $COL_LOG_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_LOG_SONG_ID TEXT NOT NULL,
                $COL_LOG_TITLE TEXT NOT NULL,
                $COL_LOG_ARTISTS TEXT NOT NULL,
                $COL_LOG_ALBUM TEXT,
                $COL_LOG_TIMESTAMP INTEGER NOT NULL,
                $COL_LOG_ARTWORK_PATH TEXT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_META (
                key TEXT PRIMARY KEY,
                value INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_DAILY_STATS (
                $COL_DAY_DATE TEXT PRIMARY KEY,
                $COL_DAY_SECONDS INTEGER NOT NULL DEFAULT 0,
                $COL_DAY_PLAYS INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("INSERT OR IGNORE INTO $TABLE_META (key, value) VALUES ('$KEY_TOTAL_SECONDS', 0)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_timestamp ON $TABLE_PLAY_LOGS ($COL_LOG_TIMESTAMP DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRACKS ADD COLUMN $COL_ARTWORK_PATH TEXT")
                db.execSQL("ALTER TABLE $TABLE_PLAY_LOGS ADD COLUMN $COL_LOG_ARTWORK_PATH TEXT")
            } catch (_: Exception) {}
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRACKS ADD COLUMN $COL_TOTAL_SECONDS INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS $TABLE_META (
                        key TEXT PRIMARY KEY,
                        value INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("INSERT OR IGNORE INTO $TABLE_META (key, value) VALUES ('$KEY_TOTAL_SECONDS', 0)")
            } catch (_: Exception) {}
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS $TABLE_DAILY_STATS (
                        $COL_DAY_DATE TEXT PRIMARY KEY,
                        $COL_DAY_SECONDS INTEGER NOT NULL DEFAULT 0,
                        $COL_DAY_PLAYS INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            } catch (_: Exception) {}
        }
        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRACKS ADD COLUMN $COL_ALBUM TEXT")
                db.execSQL("ALTER TABLE $TABLE_PLAY_LOGS ADD COLUMN $COL_LOG_ALBUM TEXT")
            } catch (_: Exception) {}
        }
    }

    fun saveArtworkToFile(bitmap: Bitmap, songId: String): String? {
        return try {
            val file = File(artworkDir, "$songId.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun splitArtists(rawArtists: String): List<String> {
        if (rawArtists.isBlank()) return emptyList()
        val regex = Regex("(?i)\\s*(?:&|feat\\.?|ft\\.?|vs\\.?|,|;|/|×)\\s*")
        return rawArtists.split(regex)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .ifEmpty { listOf(rawArtists.trim()) }
    }

    fun generateSongId(title: String, primaryArtist: String): String {
        val clean = "${title.trim().lowercase()}|${primaryArtist.trim().lowercase()}"
        val bytes = MessageDigest.getInstance("SHA-256").digest(clean.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Called second-by-second while music is actively playing.
     * Increments both total listening time and track-specific listening time,
     * as well as daily accumulated listening time in TABLE_DAILY_STATS.
     */
    suspend fun incrementListeningSecond(songId: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastTickTimestamp < 900) {
            return@withContext // Prevent burst increments
        }
        lastTickTimestamp = now

        val newTotal = _totalListenSeconds.value + 1
        _totalListenSeconds.value = newTotal

        val current = _currentPlaying.value
        if (current != null && current.songId == songId) {
            _currentPlaying.value = current.copy(currentSongSeconds = current.currentSongSeconds + 1)
        }

        // Persist to SQLite
        try {
            val db = writableDatabase
            db.execSQL("UPDATE $TABLE_META SET value = value + 1 WHERE key = ?", arrayOf(KEY_TOTAL_SECONDS))
            db.execSQL("UPDATE $TABLE_TRACKS SET $COL_TOTAL_SECONDS = $COL_TOTAL_SECONDS + 1 WHERE $COL_SONG_ID = ?", arrayOf(songId))

            // Update today's daily listening stats
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
            db.execSQL("""
                INSERT INTO $TABLE_DAILY_STATS ($COL_DAY_DATE, $COL_DAY_SECONDS, $COL_DAY_PLAYS)
                VALUES (?, 1, 0)
                ON CONFLICT($COL_DAY_DATE) DO UPDATE SET $COL_DAY_SECONDS = $COL_DAY_SECONDS + 1
            """.trimIndent(), arrayOf(todayStr))
        } catch (_: Exception) {}
    }

    /**
     * Process playback state.
     * Validates that the track has played for at least 10 seconds before counting it.
     */
    suspend fun processPlaybackTick(
        title: String,
        rawArtist: String,
        isPlaying: Boolean,
        album: String = "",
        artworkBitmap: Bitmap? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (title.isBlank()) {
            if (!isPlaying) {
                setIdle()
            }
            return@withContext false
        }

        val artists = splitArtists(rawArtist)
        val primaryArtist = artists.firstOrNull() ?: "Unknown Artist"
        val songId = generateSongId(title, primaryArtist)
        val now = System.currentTimeMillis()

        var artworkPath: String? = null
        if (artworkBitmap != null) {
            artworkPath = saveArtworkToFile(artworkBitmap, songId)
        }

        val current = _currentPlaying.value

        if (!isPlaying) {
            if (current != null && current.songId == songId) {
                _currentPlaying.value = current.copy(isPlaying = false)
            }
            return@withContext false
        }

        // Song is PLAYING: increment listening time second-by-second
        incrementListeningSecond(songId)

        if (current == null || current.songId != songId) {
            // New song detected! Fetch accumulated listening seconds from SQLite if previously played
            var initialSeconds = 1L
            try {
                val db = readableDatabase
                val cursor = db.rawQuery("SELECT $COL_TOTAL_SECONDS FROM $TABLE_TRACKS WHERE $COL_SONG_ID = ?", arrayOf(songId))
                if (cursor.moveToFirst()) {
                    initialSeconds = cursor.getLong(0).coerceAtLeast(0L) + 1L
                }
                cursor.close()
            } catch (_: Exception) {}

            Log.d("MusicDB", "Track started: \"$title\" by \"$primaryArtist\" ($album). Need 10s playback to count.")
            _currentPlaying.value = CurrentlyPlaying(
                songId = songId,
                title = title,
                artists = artists,
                album = album,
                isPlaying = true,
                artworkPath = artworkPath,
                firstDetectedAt = now,
                isCommitted = false,
                currentSongSeconds = initialSeconds
            )
            return@withContext false
        } else {
            // Same song continuing to play
            val updatedArt = artworkPath ?: current.artworkPath
            _currentPlaying.value = current.copy(
                isPlaying = true,
                artworkPath = updatedArt,
                album = if (album.isNotBlank()) album else current.album
            )

            if (current.isCommitted) {
                return@withContext false
            }

            // Check if played for at least 10 seconds
            val playedDuration = now - current.firstDetectedAt
            if (playedDuration < MIN_PLAY_DURATION_MS) {
                return@withContext false
            }

            // 10 seconds threshold met! Commit the play count to SQLite
            val lastCommitted = songLastCommittedMap[songId] ?: 0L
            if (now - lastCommitted < SONG_COUNT_COOLDOWN_MS) {
                _currentPlaying.value = current.copy(isCommitted = true)
                return@withContext false
            }

            _currentPlaying.value = current.copy(isCommitted = true)
            songLastCommittedMap[songId] = now

            val finalAlbum = if (album.isNotBlank()) album else current.album
            return@withContext commitPlayToDatabase(songId, title, artists, finalAlbum, now, updatedArt)
        }
    }

    private suspend fun commitPlayToDatabase(
        songId: String,
        title: String,
        artists: List<String>,
        album: String,
        now: Long,
        artworkPath: String?
    ): Boolean = dbMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val db = writableDatabase
                val artistsJson = JSONArray(artists).toString()

                db.beginTransaction()
                try {
                    val trackCursor = db.rawQuery(
                        "SELECT $COL_PLAY_COUNT, $COL_ARTWORK_PATH, $COL_ALBUM FROM $TABLE_TRACKS WHERE $COL_SONG_ID = ?",
                        arrayOf(songId)
                    )
                    var playCount = 1
                    if (trackCursor.moveToFirst()) {
                        val count = trackCursor.getInt(0)
                        val existingArt = trackCursor.getString(1)
                        val existingAlbum = trackCursor.getString(2) ?: ""
                        val finalArt = artworkPath ?: existingArt
                        val finalAlbum = if (album.isNotBlank()) album else existingAlbum

                        playCount = count + 1
                        val cv = ContentValues().apply {
                            put(COL_PLAY_COUNT, playCount)
                            put(COL_LAST_PLAYED, now)
                            if (finalAlbum.isNotBlank()) put(COL_ALBUM, finalAlbum)
                            if (finalArt != null) put(COL_ARTWORK_PATH, finalArt)
                        }
                        db.update(TABLE_TRACKS, cv, "$COL_SONG_ID = ?", arrayOf(songId))
                    } else {
                        val cv = ContentValues().apply {
                            put(COL_SONG_ID, songId)
                            put(COL_TITLE, title.trim())
                            put(COL_ARTISTS, artistsJson)
                            put(COL_ALBUM, album.trim())
                            put(COL_PLAY_COUNT, 1)
                            put(COL_FIRST_PLAYED, now)
                            put(COL_LAST_PLAYED, now)
                            put(COL_TOTAL_SECONDS, 10)
                            if (artworkPath != null) put(COL_ARTWORK_PATH, artworkPath)
                        }
                        db.insert(TABLE_TRACKS, null, cv)
                    }
                    trackCursor.close()

                    val logCv = ContentValues().apply {
                        put(COL_LOG_SONG_ID, songId)
                        put(COL_LOG_TITLE, title.trim())
                        put(COL_LOG_ARTISTS, artistsJson)
                        put(COL_LOG_ALBUM, album.trim())
                        put(COL_LOG_TIMESTAMP, now)
                        if (artworkPath != null) put(COL_LOG_ARTWORK_PATH, artworkPath)
                    }
                    db.insert(TABLE_PLAY_LOGS, null, logCv)

                    // Increment today's daily play count in TABLE_DAILY_STATS
                    val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
                    db.execSQL("""
                        INSERT INTO $TABLE_DAILY_STATS ($COL_DAY_DATE, $COL_DAY_SECONDS, $COL_DAY_PLAYS)
                        VALUES (?, 0, 1)
                        ON CONFLICT($COL_DAY_DATE) DO UPDATE SET $COL_DAY_PLAYS = $COL_DAY_PLAYS + 1
                    """.trimIndent(), arrayOf(todayStr))

                    db.setTransactionSuccessful()
                    Log.i("MusicDB", "Successfully committed play #$playCount for \"$title\" ($album) after 10s playback!")
                    return@withContext true
                } finally {
                    db.endTransaction()
                }
            } catch (e: Exception) {
                Log.e("MusicDB", "Failed to commit play: ${e.message}", e)
                return@withContext false
            }
        }
    }

    fun setIdle() {
        val current = _currentPlaying.value
        if (current != null && current.isPlaying) {
            _currentPlaying.value = current.copy(isPlaying = false)
        }
    }

    suspend fun getAllTracks(searchQuery: String = ""): List<TrackStat> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TrackStat>()
        val db = readableDatabase
        val sql: String
        val args: Array<String>?
        if (searchQuery.isBlank()) {
            sql = "SELECT $COL_SONG_ID, $COL_TITLE, $COL_ARTISTS, $COL_ALBUM, $COL_PLAY_COUNT, $COL_FIRST_PLAYED, $COL_LAST_PLAYED, $COL_ARTWORK_PATH, $COL_TOTAL_SECONDS FROM $TABLE_TRACKS ORDER BY $COL_PLAY_COUNT DESC"
            args = null
        } else {
            sql = "SELECT $COL_SONG_ID, $COL_TITLE, $COL_ARTISTS, $COL_ALBUM, $COL_PLAY_COUNT, $COL_FIRST_PLAYED, $COL_LAST_PLAYED, $COL_ARTWORK_PATH, $COL_TOTAL_SECONDS FROM $TABLE_TRACKS WHERE $COL_TITLE LIKE ? OR $COL_ARTISTS LIKE ? OR $COL_ALBUM LIKE ? ORDER BY $COL_PLAY_COUNT DESC"
            val query = "%$searchQuery%"
            args = arrayOf(query, query, query)
        }

        val cursor = db.rawQuery(sql, args)
        while (cursor.moveToNext()) {
            val songId = cursor.getString(0)
            val title = cursor.getString(1)
            val artistsJson = cursor.getString(2)
            val album = cursor.getString(3) ?: ""
            val playCount = cursor.getInt(4)
            val firstPlayed = cursor.getLong(5)
            val lastPlayed = cursor.getLong(6)
            val artworkPath = cursor.getString(7)
            val totalSec = cursor.getLong(8)

            val jsonArr = JSONArray(artistsJson)
            val artists = mutableListOf<String>()
            for (i in 0 until jsonArr.length()) {
                artists.add(jsonArr.getString(i))
            }

            list.add(TrackStat(songId, title, artists, album, playCount, firstPlayed, lastPlayed, artworkPath, totalSec))
        }
        cursor.close()
        return@withContext list
    }

    suspend fun getTotalPlaysCount(): Int = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_PLAY_LOGS", null)
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return@withContext count
    }

    suspend fun getUniqueTracksCount(): Int = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_TRACKS", null)
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return@withContext count
    }

    suspend fun getDailyStats(): List<DayStat> = withContext(Dispatchers.IO) {
        val list = mutableListOf<DayStat>()
        val db = readableDatabase
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(yesterdayCal.time)
        val inFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val displayFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

        val cursor = db.rawQuery(
            "SELECT $COL_DAY_DATE, $COL_DAY_SECONDS, $COL_DAY_PLAYS FROM $TABLE_DAILY_STATS WHERE $COL_DAY_SECONDS > 0 OR $COL_DAY_PLAYS > 0 ORDER BY $COL_DAY_DATE DESC LIMIT 30",
            null
        )
        while (cursor.moveToNext()) {
            val date = cursor.getString(0)
            val seconds = cursor.getLong(1)
            val plays = cursor.getInt(2)

            val label = when (date) {
                todayStr -> "Today"
                yesterdayStr -> "Yesterday"
                else -> {
                    try {
                        val parsed = inFormat.parse(date)
                        if (parsed != null) displayFormat.format(parsed) else date
                    } catch (_: Exception) {
                        date
                    }
                }
            }

            list.add(DayStat(date = date, dayLabel = label, totalSeconds = seconds, playCount = plays))
        }
        cursor.close()
        return@withContext list
    }

    suspend fun getRecentPlayLogs(limit: Int = 100): List<PlayLog> = withContext(Dispatchers.IO) {
        val list = mutableListOf<PlayLog>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT $COL_LOG_ID, $COL_LOG_SONG_ID, $COL_LOG_TITLE, $COL_LOG_ARTISTS, $COL_LOG_ALBUM, $COL_LOG_TIMESTAMP, $COL_LOG_ARTWORK_PATH FROM $TABLE_PLAY_LOGS ORDER BY $COL_LOG_TIMESTAMP DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val songId = cursor.getString(1)
            val title = cursor.getString(2)
            val artistsJson = cursor.getString(3)
            val album = cursor.getString(4) ?: ""
            val timestamp = cursor.getLong(5)
            val artworkPath = cursor.getString(6)

            val jsonArr = JSONArray(artistsJson)
            val artists = mutableListOf<String>()
            for (i in 0 until jsonArr.length()) {
                artists.add(jsonArr.getString(i))
            }
            list.add(PlayLog(id, songId, title, artists, album, timestamp, artworkPath))
        }
        cursor.close()
        return@withContext list
    }

    
    suspend fun restoreDataFromJson(jsonStr: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val json = org.json.JSONObject(jsonStr)
            db.execSQL("DELETE FROM tracks")
            db.execSQL("DELETE FROM play_logs")
            db.execSQL("DELETE FROM daily_stats")
            
            val tracks = json.optJSONArray("tracks")
            if (tracks != null) {
                for (i in 0 until tracks.length()) {
                    val t = tracks.getJSONObject(i)
                    val cv = ContentValues().apply {
                        put("song_id", t.optString("song_id"))
                        put("title", t.optString("title"))
                        put("artists_json", t.optJSONArray("artists")?.toString() ?: "[]")
                        put("album", t.optString("album", ""))
                        put("play_count", t.optInt("play_count"))
                        put("first_played", t.optLong("first_played"))
                        put("last_played", t.optLong("last_played"))
                        put("total_seconds", t.optLong("total_seconds"))
                        put("artwork_path", "")
                    }
                    db.insert("tracks", null, cv)
                }
            }
            
            val plays = json.optJSONArray("plays")
            if (plays != null) {
                for (i in 0 until plays.length()) {
                    val p = plays.getJSONObject(i)
                    val cv = ContentValues().apply {
                        put("event_id", p.optString("song_id") + "_" + p.optLong("timestamp"))
                        put("song_id", p.optString("song_id"))
                        put("title", p.optString("title"))
                        put("artists_json", p.optJSONArray("artists")?.toString() ?: "[]")
                        put("album", p.optString("album", ""))
                        put("timestamp", p.optLong("timestamp"))
                    }
                    db.insert("play_logs", null, cv)
                }
            }
            db.setTransactionSuccessful()
            _totalListenSeconds.value = json.optLong("total_seconds", 0L)
        } catch (e: Exception) {
            Log.e("DBHelper", "Restore failed", e)
        } finally {
            db.endTransaction()
        }
    }

    suspend fun clearAllData(): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_PLAY_LOGS, null, null)
            db.delete(TABLE_TRACKS, null, null)
            db.delete(TABLE_DAILY_STATS, null, null)
            db.delete(TABLE_META, null, null)
            db.execSQL("INSERT OR REPLACE INTO $TABLE_META (key, value) VALUES (?, ?)", arrayOf(KEY_TOTAL_SECONDS, 0))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // Delete cached artwork images
        try {
            val artDir = File(context.filesDir, "artworks")
            if (artDir.exists()) {
                artDir.listFiles()?.forEach { it.delete() }
            }
        } catch (_: Exception) {}

        _totalListenSeconds.value = 0L
        _currentPlaying.value = null
        songLastCommittedMap.clear()
        lastTickTimestamp = 0L
    }
}
