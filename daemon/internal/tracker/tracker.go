package tracker

import (
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/quazaar/synker/daemon/internal/db"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Song represents a row in the 'songs' table.
type Song struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	Artist      string `json:"artist"`
	Album       string `json:"album"`
	ArtworkPath string `json:"artwork_path,omitempty"`
	DurationSec int    `json:"duration_sec"`
}

// Device represents a row in the 'devices' table.
type Device struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	CreatedAt time.Time `json:"created_at"`
}

// DeviceHistory represents a row in the 'device_history' table.
type DeviceHistory struct {
	DeviceID              string    `json:"device_id"`
	DeviceInfo            *Device   `json:"_info,omitempty"`
	TotalSongsCount       int       `json:"total_songs_count"`
	TotalSongPlays        int       `json:"total_song_plays"`
	TotalListeningTimeSec int64     `json:"total_listening_time_sec"`
	UpdatedAt             time.Time `json:"updated_at"`
}

// SongPlayHistory represents a row in the 'songs_play_history' table.
type SongPlayHistory struct {
	DeviceID               string    `json:"device_id"`
	DeviceInfo             *Device   `json:"_device_info,omitempty"`
	SongID                 string    `json:"song_id"`
	SongInfo               *Song     `json:"_song_info,omitempty"`
	PlayCount              int       `json:"play_count"`
	TotalListenDurationSec int64     `json:"total_listen_duration_sec"`
	LastPlayedAt           time.Time `json:"last_played_at"`
}

// PlayLogEntry represents an individual recorded play event.
type PlayLogEntry struct {
	EventID       string    `json:"event_id"`
	SongID        string    `json:"song_id"`
	SongInfo      *Song     `json:"_song_info,omitempty"`
	Title         string    `json:"title"`
	Artists       []string  `json:"artists"`
	Album         string    `json:"album,omitempty"`
	SourceApp     string    `json:"source_app"`
	DeviceID      string    `json:"device_id"`
	DeviceInfo    *Device   `json:"_device_info,omitempty"`
	DeviceName    string    `json:"device_name"`
	Timestamp     time.Time `json:"timestamp"`
	UnixTimestamp int64     `json:"unix_timestamp"`
	DurationMS    int64     `json:"duration_ms,omitempty"`
}

// TrackStats stores aggregated statistics for a unique song.
type TrackStats struct {
	SongID       string    `json:"song_id"`
	Title        string    `json:"title"`
	Artists      []string  `json:"artists"`
	Album        string    `json:"album,omitempty"`
	PlayCount    int       `json:"play_count"`
	FirstPlayed  time.Time `json:"first_played"`
	LastPlayed   time.Time `json:"last_played"`
	LastDevice   string    `json:"last_device"`
	SourceApps   []string  `json:"source_apps"`
	TotalSeconds int64     `json:"total_seconds"`
	ArtworkURL   string    `json:"artwork_url,omitempty"` // served via /api/v1/artwork/{song_id}
}

// DeviceInfo represents an enrolled/synced mobile or desktop client device.
type DeviceInfo struct {
	DeviceID     string    `json:"device_id"`
	DeviceName   string    `json:"device_name"`
	LastSync     time.Time `json:"last_sync"`
	TotalSeconds int64     `json:"total_seconds"`
	TotalPlays   int       `json:"total_plays"`
	UniqueTracks int       `json:"unique_tracks"`
}

// SyncedTrackPayload represents an incoming track record from a mobile client.
type SyncedTrackPayload struct {
	SongID       string   `json:"song_id"`
	Title        string   `json:"title"`
	Artists      []string `json:"artists"`
	Album        string   `json:"album"`
	PlayCount    int      `json:"play_count"`
	FirstPlayed  int64    `json:"first_played"`
	LastPlayed   int64    `json:"last_played"`
	TotalSeconds int64    `json:"total_seconds"`
	ArtworkData  string   `json:"artwork_data,omitempty"` // Base64-encoded JPEG from client filesystem
}

// SyncedPlayLogPayload represents an incoming play log event from a client.
type SyncedPlayLogPayload struct {
	SongID     string   `json:"song_id"`
	Title      string   `json:"title"`
	Artists    []string `json:"artists"`
	Album      string   `json:"album"`
	Timestamp  int64    `json:"timestamp"`
	DurationMS int64    `json:"duration_ms,omitempty"`
}

// SyncedDailyStat represents aggregated daily listening time from client.
type SyncedDailyStat struct {
	Date         string `json:"date"`
	TotalSeconds int64  `json:"total_seconds"`
	PlayCount    int    `json:"play_count"`
}

// DeviceSyncPayload represents the complete batch sync packet sent by a mobile client.
type DeviceSyncPayload struct {
	DeviceID            string                 `json:"device_id"`
	DeviceName          string                 `json:"device_name"`
	OverrideServerStats bool                   `json:"override_server_stats"`
	TotalSeconds        int64                  `json:"total_seconds"`
	TotalPlays          int                    `json:"total_plays"`
	Tracks              []SyncedTrackPayload   `json:"tracks"`
	Plays               []SyncedPlayLogPayload `json:"plays"`
	DailyStats          []SyncedDailyStat      `json:"daily_stats"`
}

// StorageData is the structured format returned by the tracker and REST endpoints.
type StorageData struct {
	Version      string                `json:"version"`
	StorageType  string                `json:"storage_type"`
	LastUpdated  time.Time             `json:"last_updated"`
	TotalPlays   int                   `json:"total_plays"`
	TotalSeconds int64                 `json:"total_seconds"`
	Devices      []DeviceInfo          `json:"devices"`
	DailyStats   []SyncedDailyStat     `json:"daily_stats"`
	Tracks       map[string]TrackStats `json:"tracks"`
	RecentPlays  []PlayLogEntry        `json:"recent_plays"`
}

// MusicTracker handles listening history logging, plays counting, and SQLite storage.
type MusicTracker struct {
	db         *db.Database
	dbPath     string
	jsonBackup string
	artworkDir string // directory where synced artwork JPEGs are stored
	mu         sync.RWMutex
	lastTrack  string
	lastPlayAt time.Time
}

// NewMusicTracker creates or connects to SQLite database at configDir/music_history.db
func NewMusicTracker(configDir string) (*MusicTracker, error) {
	if err := os.MkdirAll(configDir, 0700); err != nil {
		return nil, fmt.Errorf("failed to create tracker directory: %w", err)
	}

	artworkDir := filepath.Join(configDir, "artworks")
	if err := os.MkdirAll(artworkDir, 0700); err != nil {
		return nil, fmt.Errorf("failed to create artworks directory: %w", err)
	}

	dbPath := filepath.Join(configDir, "music_history.db")
	jsonBackup := filepath.Join(configDir, "music_history.json")

	database, err := db.New(dbPath)
	if err != nil {
		return nil, fmt.Errorf("failed to open database at %s: %w", dbPath, err)
	}

	mt := &MusicTracker{
		db:         database,
		dbPath:     dbPath,
		jsonBackup: jsonBackup,
		artworkDir: artworkDir,
	}

	mt.migrateSchemaIfNeeded()
	mt.migrateFromJSONIfEmpty()

	totalTracks, totalPlays := mt.getCounts()
	log.Printf("[Tracker] DB ready at %s (%d tracks, %d total plays)", dbPath, totalTracks, totalPlays)

	return mt, nil
}

func (mt *MusicTracker) migrateSchemaIfNeeded() {
	_, _ = mt.db.Conn.Exec(`
		CREATE TABLE IF NOT EXISTS admin_users (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			password_hash TEXT NOT NULL
		);
	`)
}

func (mt *MusicTracker) Close() error {
	if mt.db != nil {
		return mt.db.Conn.Close()
	}
	return nil
}

// SplitArtists splits multiple artists separated by delimiters (&, ',', 'feat.', 'ft.', 'vs.', ';') into an array.
func SplitArtists(artistRaw string) []string {
	raw := strings.TrimSpace(artistRaw)
	if raw == "" {
		return []string{}
	}

	// Replace common collaboration patterns with a unified delimiter "|"
	re := regexp.MustCompile(`(?i)\s*(?:&|feat\.?|ft\.?|vs\.?|,|;|/|×)\s*`)
	parts := re.Split(raw, -1)

	var artists []string
	seen := make(map[string]bool)
	for _, p := range parts {
		cleaned := strings.TrimSpace(p)
		if cleaned != "" {
			lower := strings.ToLower(cleaned)
			if !seen[lower] {
				seen[lower] = true
				artists = append(artists, cleaned)
			}
		}
	}

	if len(artists) == 0 && raw != "" {
		return []string{raw}
	}
	return artists
}

// GenerateSongID produces a deterministic hash from lowercase title + primary artists.
func GenerateSongID(title string, artists []string) string {
	cleanTitle := strings.TrimSpace(strings.ToLower(title))
	primaryArtist := ""
	if len(artists) > 0 {
		primaryArtist = strings.TrimSpace(strings.ToLower(artists[0]))
	}
	h := sha256.Sum256([]byte(cleanTitle + "|" + primaryArtist))
	return hex.EncodeToString(h[:8]) // 16-character hex ID
}

// ShouldTrack returns true if the app or player represents Apple Music (ignoring YouTube/others).
func ShouldTrack(appName, title string) bool {
	if strings.TrimSpace(title) == "" {
		return false
	}

	lowerApp := strings.ToLower(appName)

	// Explicitly ignore YouTube
	if strings.Contains(lowerApp, "youtube") || strings.Contains(lowerApp, "yt") {
		return false
	}

	// Target Apple Music (Android package: com.apple.android.music, KDE Connect MPRIS, or player name)
	if strings.Contains(lowerApp, "apple") ||
		strings.Contains(lowerApp, "music") ||
		strings.Contains(lowerApp, "mpris") ||
		lowerApp == "com.apple.android.music" {
		return true
	}

	return false
}

// RecordPlay records an Apple Music play event in SQLite with array of artists and avoids rapid duplicate spam.
func (mt *MusicTracker) RecordPlay(appName, title string, artists []string, album, deviceID, deviceName string, durationMS int64) bool {
	if !ShouldTrack(appName, title) {
		return false
	}

	if len(artists) == 0 {
		artists = []string{"Unknown Artist"}
	}

	songID := GenerateSongID(title, artists)
	now := time.Now()

	mt.mu.Lock()
	defer mt.mu.Unlock()

	// Debounce
	if mt.lastTrack == songID && now.Sub(mt.lastPlayAt) < 30*time.Second {
		return false
	}

	mt.lastTrack = songID
	mt.lastPlayAt = now

	eventID := fmt.Sprintf("evt_%d_%s", now.UnixNano(), songID[:6])
	artistsJSON, _ := json.Marshal(artists)

	tx, err := mt.db.Conn.Begin()
	if err != nil {
		return false
	}
	defer tx.Rollback()

	// Ensure device
	_, _ = tx.Exec(`
		INSERT INTO devices (id, name, created_at) VALUES (?, ?, ?)
		ON CONFLICT(id) DO UPDATE SET name = excluded.name
	`, deviceID, deviceName, now)

	// Ensure song
	_, _ = tx.Exec(`
		INSERT INTO songs (id, title, artist, album, artwork_path, duration_sec)
		VALUES (?, ?, ?, ?, '', 0)
		ON CONFLICT(id) DO UPDATE SET
			album = CASE WHEN excluded.album != '' THEN excluded.album ELSE songs.album END
	`, songID, strings.TrimSpace(title), string(artistsJSON), strings.TrimSpace(album))

	// Upsert songs_play_history
	_, err = tx.Exec(`
		INSERT INTO songs_play_history (device_id, song_id, play_count, total_listen_duration_sec, last_played_at)
		VALUES (?, ?, 1, ?, ?)
		ON CONFLICT(device_id, song_id) DO UPDATE SET
			play_count = songs_play_history.play_count + 1,
			total_listen_duration_sec = songs_play_history.total_listen_duration_sec + excluded.total_listen_duration_sec,
			last_played_at = excluded.last_played_at
	`, deviceID, songID, durationMS/1000, now)

	if err != nil {
		log.Printf("[Tracker] Error updating play history in SQLite: %v", err)
		return false
	}

	// Insert into play_logs
	_, err = tx.Exec(`
		INSERT INTO play_logs (event_id, song_id, title, artists_json, album, source_app, device_id, device_name, timestamp, unix_timestamp, duration_ms)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`, eventID, songID, strings.TrimSpace(title), string(artistsJSON), strings.TrimSpace(album), appName, deviceID, deviceName, now, now.UnixMilli(), durationMS)

	// Upsert device_history
	_, _ = tx.Exec(`
		INSERT INTO device_history (device_id, total_songs_count, total_song_plays, total_listening_time_sec, updated_at)
		VALUES (?, 1, 1, ?, ?)
		ON CONFLICT(device_id) DO UPDATE SET
			total_songs_count = (SELECT COUNT(*) FROM songs_play_history WHERE device_id = excluded.device_id),
			total_song_plays = device_history.total_song_plays + 1,
			total_listening_time_sec = device_history.total_listening_time_sec + excluded.total_listening_time_sec,
			updated_at = excluded.updated_at
	`, deviceID, durationMS/1000, now)

	if err := tx.Commit(); err != nil {
		return false
	}

	go mt.exportSnapshotJSON()
	return true
}

// GetData queries SQLite and returns the full structured data representation.

func (mt *MusicTracker) GetHistoryStats() StorageData {
	mt.mu.RLock()
	defer mt.mu.RUnlock()

	data := StorageData{
		Version:     "0.0.1",
		StorageType: "sqlite3",
		LastUpdated: time.Now(),
		Tracks:      make(map[string]TrackStats),
		RecentPlays: make([]PlayLogEntry, 0),
		Devices:     make([]DeviceInfo, 0),
		DailyStats:  make([]SyncedDailyStat, 0),
	}
	_ = mt.db.Conn.QueryRow(`SELECT COUNT(*) FROM play_logs`).Scan(&data.TotalPlays)
	_ = mt.db.Conn.QueryRow(`SELECT COALESCE(SUM(total_listening_time_sec), 0) FROM device_history`).Scan(&data.TotalSeconds)
	return data
}

func (mt *MusicTracker) GetTracks() map[string]TrackStats {
	mt.mu.RLock()
	defer mt.mu.RUnlock()
	tracks := make(map[string]TrackStats)
	rows, err := mt.db.Conn.Query(`
		SELECT s.id, s.title, s.artist, s.album, s.artwork_path,
		       SUM(sph.play_count), MIN(sph.last_played_at), MAX(sph.last_played_at), SUM(sph.total_listen_duration_sec)
		FROM songs s
		LEFT JOIN songs_play_history sph ON s.id = sph.song_id
		GROUP BY s.id
	`)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var t TrackStats
			var artistsJSON string
			var album, artworkPath sql.NullString
			var minTimeStr, maxTimeStr sql.NullString
			var playCount, totalSec sql.NullInt64

			if err := rows.Scan(&t.SongID, &t.Title, &artistsJSON, &album, &artworkPath, &playCount, &minTimeStr, &maxTimeStr, &totalSec); err == nil {
				_ = json.Unmarshal([]byte(artistsJSON), &t.Artists)
				if album.Valid {
					t.Album = album.String
				}
				if artworkPath.Valid && artworkPath.String != "" {
					if _, err := os.Stat(artworkPath.String); err == nil {
						t.ArtworkURL = "/api/v1/artwork/" + t.SongID
					}
				}
				if playCount.Valid {
					t.PlayCount = int(playCount.Int64)
				}

				layout := "2006-01-02 15:04:05.999999999 -0700 MST"
				if minTimeStr.Valid {
					parsed, _ := time.Parse(time.RFC3339Nano, minTimeStr.String)
					if parsed.IsZero() {
						parsed, _ = time.Parse(layout, minTimeStr.String)
					}
					t.FirstPlayed = parsed
				}
				if maxTimeStr.Valid {
					parsed, _ := time.Parse(time.RFC3339Nano, maxTimeStr.String)
					if parsed.IsZero() {
						parsed, _ = time.Parse(layout, maxTimeStr.String)
					}
					t.LastPlayed = parsed
				}
				if totalSec.Valid {
					t.TotalSeconds = totalSec.Int64
				}
				tracks[t.SongID] = t
			}
		}
	}
	return tracks
}

func (mt *MusicTracker) GetRecentPlays() []PlayLogEntry {
	mt.mu.RLock()
	defer mt.mu.RUnlock()
	recent := make([]PlayLogEntry, 0)
	pRows, err := mt.db.Conn.Query(`
		SELECT p.event_id, p.song_id, p.title, p.artists_json, p.album, p.source_app, p.device_id, p.device_name, p.timestamp, p.unix_timestamp, p.duration_ms,
		       d.name, d.created_at,
		       s.title, s.artist, s.album, s.artwork_path, s.duration_sec
		FROM play_logs p
		LEFT JOIN devices d ON p.device_id = d.id
		LEFT JOIN songs s ON p.song_id = s.id
		ORDER BY p.timestamp DESC
		LIMIT 100
	`)
	if err == nil {
		defer pRows.Close()
		for pRows.Next() {
			var p PlayLogEntry
			var artistsJSON string
			var album sql.NullString
			var timestampStr string
			var d Device
			var s Song
			var dName sql.NullString
			var cStr sql.NullString
			var sTitle, sArtist, sAlbum, sArt sql.NullString
			var sDur sql.NullInt64

			if err := pRows.Scan(&p.EventID, &p.SongID, &p.Title, &artistsJSON, &album, &p.SourceApp, &p.DeviceID, &p.DeviceName, &timestampStr, &p.UnixTimestamp, &p.DurationMS,
				&dName, &cStr,
				&sTitle, &sArtist, &sAlbum, &sArt, &sDur); err == nil {
				_ = json.Unmarshal([]byte(artistsJSON), &p.Artists)
				if album.Valid {
					p.Album = album.String
				}
				layout := "2006-01-02 15:04:05.999999999 -0700 MST"
				parsed, err := time.Parse(time.RFC3339Nano, timestampStr)
				if err != nil {
					parsed, _ = time.Parse(layout, timestampStr)
				}
				p.Timestamp = parsed

				if dName.Valid {
					d.ID = p.DeviceID
					d.Name = dName.String
					if cStr.Valid {
						if parsed, e := time.Parse(time.RFC3339Nano, cStr.String); e == nil {
							d.CreatedAt = parsed
						} else {
							if parsed, e := time.Parse(layout, cStr.String); e == nil {
								d.CreatedAt = parsed
							}
						}
					}
					p.DeviceInfo = &d
				}

				if sTitle.Valid {
					s.ID = p.SongID
					s.Title = sTitle.String
					if sArtist.Valid {
						s.Artist = sArtist.String
					}
					if sAlbum.Valid {
						s.Album = sAlbum.String
					}
					if sArt.Valid {
						s.ArtworkPath = sArt.String
					}
					if sDur.Valid {
						s.DurationSec = int(sDur.Int64)
					}
					p.SongInfo = &s
				}

				recent = append(recent, p)
			}
		}
	}
	return recent
}

func (mt *MusicTracker) GetDevices() []DeviceInfo {
	mt.mu.RLock()
	defer mt.mu.RUnlock()
	devices := make([]DeviceInfo, 0)
	devRows, err := mt.db.Conn.Query(`
		SELECT d.id, d.name, dh.updated_at, dh.total_listening_time_sec, dh.total_song_plays, dh.total_songs_count
		FROM devices d
		LEFT JOIN device_history dh ON d.id = dh.device_id
		ORDER BY dh.updated_at DESC
	`)
	if err == nil {
		defer devRows.Close()
		for devRows.Next() {
			var d DeviceInfo
			var updatedStr sql.NullString
			var tSec, tPlays, uTracks sql.NullInt64
			if err := devRows.Scan(&d.DeviceID, &d.DeviceName, &updatedStr, &tSec, &tPlays, &uTracks); err == nil {
				if updatedStr.Valid {
					layout := "2006-01-02 15:04:05.999999999 -0700 MST"
					parsed, _ := time.Parse(time.RFC3339Nano, updatedStr.String)
					if parsed.IsZero() {
						parsed, _ = time.Parse(layout, updatedStr.String)
					}
					d.LastSync = parsed
				}
				if tSec.Valid {
					d.TotalSeconds = tSec.Int64
				}
				if tPlays.Valid {
					d.TotalPlays = int(tPlays.Int64)
				}
				if uTracks.Valid {
					d.UniqueTracks = int(uTracks.Int64)
				}
				devices = append(devices, d)
			}
		}
	}
	return devices
}

func (mt *MusicTracker) GetDailyStats() []SyncedDailyStat {
	mt.mu.RLock()
	defer mt.mu.RUnlock()
	stats := make([]SyncedDailyStat, 0)
	dayRows, err := mt.db.Conn.Query(`
		SELECT date, SUM(total_seconds), SUM(play_count)
		FROM daily_stats
		GROUP BY date
		ORDER BY date DESC
		LIMIT 30
	`)
	if err == nil {
		defer dayRows.Close()
		for dayRows.Next() {
			var ds SyncedDailyStat
			if err := dayRows.Scan(&ds.Date, &ds.TotalSeconds, &ds.PlayCount); err == nil {
				stats = append(stats, ds)
			}
		}
	}
	return stats
}

func (mt *MusicTracker) GetData() StorageData {
	data := mt.GetHistoryStats()
	data.Tracks = mt.GetTracks()
	data.RecentPlays = mt.GetRecentPlays()
	data.Devices = mt.GetDevices()
	data.DailyStats = mt.GetDailyStats()
	return data
}

// SyncDeviceData merges full or delta batch synchronization payload from mobile clients.
func (mt *MusicTracker) SyncDeviceData(payload DeviceSyncPayload) error {
	if payload.DeviceID == "" {
		payload.DeviceID = "unknown_client"
	}
	if payload.DeviceName == "" {
		payload.DeviceName = "Mobile Device"
	}

	mt.mu.Lock()
	defer mt.mu.Unlock()

	tx, err := mt.db.Conn.Begin()
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer tx.Rollback()

	now := time.Now()

	// Ensure device exists
	_, _ = tx.Exec(`
		INSERT INTO devices (id, name, created_at) VALUES (?, ?, ?)
		ON CONFLICT(id) DO UPDATE SET name = excluded.name
	`, payload.DeviceID, payload.DeviceName, now)

	// Upsert Tracks into 'songs' and 'songs_play_history'
	for _, t := range payload.Tracks {
		if strings.TrimSpace(t.Title) == "" {
			continue
		}
		if len(t.Artists) == 0 {
			t.Artists = []string{"Unknown Artist"}
		}
		songID := t.SongID
		if songID == "" {
			songID = GenerateSongID(t.Title, t.Artists)
		}
		artistsJSON, _ := json.Marshal(t.Artists)
		lastTime := time.UnixMilli(t.LastPlayed)
		if t.LastPlayed == 0 {
			lastTime = now
		}

		// Decode and save artwork to disk if provided
		var artworkPath string
		if t.ArtworkData != "" {
			jpegBytes, decErr := base64.StdEncoding.DecodeString(t.ArtworkData)
			if decErr == nil && len(jpegBytes) > 0 {
				dest := filepath.Join(mt.artworkDir, songID+".jpg")
				if writeErr := os.WriteFile(dest, jpegBytes, 0644); writeErr == nil {
					artworkPath = dest
				}
			}
		}

		// 1. Insert into songs
		if artworkPath != "" {
			_, err = tx.Exec(`
				INSERT INTO songs (id, title, artist, album, artwork_path, duration_sec)
				VALUES (?, ?, ?, ?, ?, 0)
				ON CONFLICT(id) DO UPDATE SET
					album = CASE WHEN excluded.album != '' THEN excluded.album ELSE songs.album END,
					artwork_path = excluded.artwork_path
			`, songID, strings.TrimSpace(t.Title), string(artistsJSON), strings.TrimSpace(t.Album), artworkPath)
		} else {
			_, err = tx.Exec(`
				INSERT INTO songs (id, title, artist, album, artwork_path, duration_sec)
				VALUES (?, ?, ?, ?, '', 0)
				ON CONFLICT(id) DO UPDATE SET
					album = CASE WHEN excluded.album != '' THEN excluded.album ELSE songs.album END
			`, songID, strings.TrimSpace(t.Title), string(artistsJSON), strings.TrimSpace(t.Album))
		}

		if err != nil {
			log.Printf("[Tracker] Sync error inserting song %s: %v", t.Title, err)
		}

		// 2. Upsert into songs_play_history
		if payload.OverrideServerStats {
			_, err = tx.Exec(`
				INSERT INTO songs_play_history (device_id, song_id, play_count, total_listen_duration_sec, last_played_at)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT(device_id, song_id) DO UPDATE SET
					play_count = excluded.play_count,
					total_listen_duration_sec = excluded.total_listen_duration_sec,
					last_played_at = MAX(songs_play_history.last_played_at, excluded.last_played_at)
			`, payload.DeviceID, songID, t.PlayCount, t.TotalSeconds, lastTime)
		} else {
			_, err = tx.Exec(`
				INSERT INTO songs_play_history (device_id, song_id, play_count, total_listen_duration_sec, last_played_at)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT(device_id, song_id) DO UPDATE SET
					last_played_at = MAX(songs_play_history.last_played_at, excluded.last_played_at)
			`, payload.DeviceID, songID, t.PlayCount, t.TotalSeconds, lastTime)
		}

		if err != nil {
			log.Printf("[Tracker] Sync error inserting play history %s: %v", t.Title, err)
		}
	}

	// Insert Play Logs
	for _, p := range payload.Plays {
		if strings.TrimSpace(p.Title) == "" {
			continue
		}
		if len(p.Artists) == 0 {
			p.Artists = []string{"Unknown Artist"}
		}
		songID := p.SongID
		if songID == "" {
			songID = GenerateSongID(p.Title, p.Artists)
		}
		artistsJSON, _ := json.Marshal(p.Artists)
		playTime := time.UnixMilli(p.Timestamp)
		if p.Timestamp == 0 {
			playTime = now
		}
		eventID := fmt.Sprintf("sync_%s_%d", songID, playTime.UnixNano())

		res, err := tx.Exec(`
			INSERT OR IGNORE INTO play_logs (event_id, song_id, title, artists_json, album, source_app, device_id, device_name, timestamp, unix_timestamp, duration_ms)
			VALUES (?, ?, ?, ?, ?, 'Apple Music (Client)', ?, ?, ?, ?, ?)
		`, eventID, songID, strings.TrimSpace(p.Title), string(artistsJSON), strings.TrimSpace(p.Album), payload.DeviceID, payload.DeviceName, playTime, playTime.UnixMilli(), p.DurationMS)

		if err != nil {
			log.Printf("[Tracker] Error inserting play log: %v", err)
			continue
		}

		rowsAff, _ := res.RowsAffected()
		if rowsAff == 0 {
			continue // duplicate, ignore
		}

		// For delta sync: Update daily_stats for this play if it was successfully inserted (or just blindly upsert)
		dateStr := playTime.Format("2006-01-02")
		_, _ = tx.Exec(`
			INSERT INTO daily_stats (date, device_id, total_seconds, play_count)
			VALUES (?, ?, ?, 1)
			ON CONFLICT(date, device_id) DO UPDATE SET
				total_seconds = daily_stats.total_seconds + excluded.total_seconds,
				play_count = daily_stats.play_count + 1
		`, dateStr, payload.DeviceID, p.DurationMS/1000)

		// We also need to upsert songs_play_history here IF the client stops sending it in Tracks
		_, _ = tx.Exec(`
			INSERT INTO songs_play_history (device_id, song_id, play_count, total_listen_duration_sec, last_played_at)
			VALUES (?, ?, 1, ?, ?)
			ON CONFLICT(device_id, song_id) DO UPDATE SET
				play_count = songs_play_history.play_count + 1,
				total_listen_duration_sec = songs_play_history.total_listen_duration_sec + excluded.total_listen_duration_sec,
				last_played_at = MAX(songs_play_history.last_played_at, excluded.last_played_at)
		`, payload.DeviceID, songID, p.DurationMS/1000, playTime)

		// Ensure the song exists in 'songs' table
		_, _ = tx.Exec(`
			INSERT INTO songs (id, title, artist, album, artwork_path, duration_sec)
			VALUES (?, ?, ?, ?, '', 0)
			ON CONFLICT(id) DO UPDATE SET
				album = CASE WHEN excluded.album != '' THEN excluded.album ELSE songs.album END
		`, songID, strings.TrimSpace(p.Title), string(artistsJSON), strings.TrimSpace(p.Album))

		continue // skip the rest of the old loop body

	}

	// Upsert Daily Stats
	for _, ds := range payload.DailyStats {
		if strings.TrimSpace(ds.Date) == "" {
			continue
		}
		if payload.OverrideServerStats {
			_, _ = tx.Exec(`
				INSERT INTO daily_stats (date, device_id, total_seconds, play_count)
				VALUES (?, ?, ?, ?)
				ON CONFLICT(date, device_id) DO UPDATE SET
					total_seconds = excluded.total_seconds,
					play_count = excluded.play_count
			`, ds.Date, payload.DeviceID, ds.TotalSeconds, ds.PlayCount)
		} else {
			_, _ = tx.Exec(`
				INSERT OR IGNORE INTO daily_stats (date, device_id, total_seconds, play_count)
				VALUES (?, ?, ?, ?)
			`, ds.Date, payload.DeviceID, ds.TotalSeconds, ds.PlayCount)
		}
	}

	// Delta calculation for device_history
	var newSongsCount, newTotalPlays int
	var newTotalSec int64
	_ = tx.QueryRow(`SELECT COUNT(*), COALESCE(SUM(play_count), 0), COALESCE(SUM(total_listen_duration_sec), 0) FROM songs_play_history WHERE device_id = ?`, payload.DeviceID).Scan(&newSongsCount, &newTotalPlays, &newTotalSec)

	_, err = tx.Exec(`
		INSERT INTO device_history (device_id, total_songs_count, total_song_plays, total_listening_time_sec, updated_at)
		VALUES (?, ?, ?, ?, ?)
		ON CONFLICT(device_id) DO UPDATE SET
			total_songs_count = excluded.total_songs_count,
			total_song_plays = excluded.total_song_plays,
			total_listening_time_sec = excluded.total_listening_time_sec,
			updated_at = excluded.updated_at
	`, payload.DeviceID, newSongsCount, newTotalPlays, newTotalSec, now)

	if err != nil {
		log.Printf("[Tracker] Device record update error: %v", err)
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("failed to commit sync transaction: %w", err)
	}

	go mt.exportSnapshotJSON()
	return nil
}

func (mt *MusicTracker) getCounts() (tracks int, plays int) {
	_ = mt.db.Conn.QueryRow(`SELECT COUNT(*) FROM songs`).Scan(&tracks)
	_ = mt.db.Conn.QueryRow(`SELECT COUNT(*) FROM play_logs`).Scan(&plays)
	return
}

// GetArtworkPath returns the local file path for a track's artwork, or "" if not available.
func (mt *MusicTracker) GetArtworkPath(songID string) string {
	if songID == "" {
		return ""
	}
	directFile := filepath.Join(mt.artworkDir, songID+".jpg")
	if info, err := os.Stat(directFile); err == nil && info.Size() > 0 {
		return directFile
	}
	var artworkPath sql.NullString
	_ = mt.db.Conn.QueryRow(`SELECT artwork_path FROM songs WHERE id = ?`, songID).Scan(&artworkPath)
	if artworkPath.Valid && artworkPath.String != "" {
		if info, err := os.Stat(artworkPath.String); err == nil && info.Size() > 0 {
			return artworkPath.String
		}
	}
	return ""
}

// SaveArtwork writes artwork bytes to disk and updates tracks table
func (mt *MusicTracker) SaveArtwork(songID string, jpegBytes []byte) error {
	if songID == "" || len(jpegBytes) == 0 {
		return nil
	}
	dest := filepath.Join(mt.artworkDir, songID+".jpg")
	if err := os.WriteFile(dest, jpegBytes, 0644); err != nil {
		return err
	}
	_, _ = mt.db.Conn.Exec(`UPDATE songs SET artwork_path = ? WHERE id = ?`, dest, songID)
	return nil
}

func (mt *MusicTracker) exportSnapshotJSON() {
	data := mt.GetData()
	bytes, err := json.MarshalIndent(data, "", "  ")
	if err == nil {
		_ = os.WriteFile(mt.jsonBackup, bytes, 0644)
	}
}

func (mt *MusicTracker) migrateFromJSONIfEmpty() {
	// disabled because schema changed
}
func (mt *MusicTracker) GetDBForTest() *sql.DB { return mt.db.Conn }

func (mt *MusicTracker) GetSongs() []Song {
	mt.mu.RLock()
	defer mt.mu.RUnlock()
	songs := []Song{}
	rows, err := mt.db.Conn.Query(`SELECT id, title, artist, album, artwork_path, duration_sec FROM songs`)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var s Song
			var album, art sql.NullString
			if err := rows.Scan(&s.ID, &s.Title, &s.Artist, &album, &art, &s.DurationSec); err == nil {
				if album.Valid {
					s.Album = album.String
				}
				if art.Valid {
					s.ArtworkPath = art.String
				}
				songs = append(songs, s)
			}
		}
	}
	return songs
}

func (mt *MusicTracker) GetRawDevices() []Device {
	mt.mu.RLock()
	defer mt.mu.RUnlock()
	devs := []Device{}
	rows, err := mt.db.Conn.Query(`SELECT id, name, created_at FROM devices`)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var d Device
			var cStr string
			if err := rows.Scan(&d.ID, &d.Name, &cStr); err == nil {
				layout := "2006-01-02 15:04:05.999999999 -0700 MST"
				if parsed, e := time.Parse(time.RFC3339Nano, cStr); e == nil {
					d.CreatedAt = parsed
				} else {
					if parsed, e := time.Parse(layout, cStr); e == nil {
						d.CreatedAt = parsed
					}
				}
				devs = append(devs, d)
			}
		}
	}
	return devs
}

func (mt *MusicTracker) GetDeviceHistories() []DeviceHistory {
	mt.mu.RLock()
	defer mt.mu.RUnlock()
	hists := []DeviceHistory{}
	rows, err := mt.db.Conn.Query(`
		SELECT dh.device_id, dh.total_songs_count, dh.total_song_plays, dh.total_listening_time_sec, dh.updated_at,
		       d.name, d.created_at
		FROM device_history dh
		LEFT JOIN devices d ON dh.device_id = d.id
	`)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var dh DeviceHistory
			var d Device
			var uStr, cStr sql.NullString
			var dName sql.NullString

			if err := rows.Scan(&dh.DeviceID, &dh.TotalSongsCount, &dh.TotalSongPlays, &dh.TotalListeningTimeSec, &uStr, &dName, &cStr); err == nil {
				layout := "2006-01-02 15:04:05.999999999 -0700 MST"
				if uStr.Valid {
					if parsed, e := time.Parse(time.RFC3339Nano, uStr.String); e == nil {
						dh.UpdatedAt = parsed
					} else {
						if parsed, e := time.Parse(layout, uStr.String); e == nil {
							dh.UpdatedAt = parsed
						}
					}
				}
				if dName.Valid {
					d.ID = dh.DeviceID
					d.Name = dName.String
					if cStr.Valid {
						if parsed, e := time.Parse(time.RFC3339Nano, cStr.String); e == nil {
							d.CreatedAt = parsed
						} else {
							if parsed, e := time.Parse(layout, cStr.String); e == nil {
								d.CreatedAt = parsed
							}
						}
					}
					dh.DeviceInfo = &d
				}

				hists = append(hists, dh)
			}
		}
	}
	return hists
}

func (mt *MusicTracker) GetSongsPlayHistory() []SongPlayHistory {
	mt.mu.RLock()
	defer mt.mu.RUnlock()
	sph := []SongPlayHistory{}
	rows, err := mt.db.Conn.Query(`
		SELECT sph.device_id, sph.song_id, sph.play_count, sph.total_listen_duration_sec, sph.last_played_at,
		       d.name, d.created_at,
		       s.title, s.artist, s.album, s.artwork_path, s.duration_sec
		FROM songs_play_history sph
		LEFT JOIN devices d ON sph.device_id = d.id
		LEFT JOIN songs s ON sph.song_id = s.id
	`)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var sp SongPlayHistory
			var d Device
			var s Song
			var lStr, cStr sql.NullString
			var dName, sTitle, sArtist, sAlbum, sArt sql.NullString
			var sDur sql.NullInt64

			if err := rows.Scan(&sp.DeviceID, &sp.SongID, &sp.PlayCount, &sp.TotalListenDurationSec, &lStr,
				&dName, &cStr,
				&sTitle, &sArtist, &sAlbum, &sArt, &sDur); err == nil {

				layout := "2006-01-02 15:04:05.999999999 -0700 MST"
				if lStr.Valid {
					if parsed, e := time.Parse(time.RFC3339Nano, lStr.String); e == nil {
						sp.LastPlayedAt = parsed
					} else {
						if parsed, e := time.Parse(layout, lStr.String); e == nil {
							sp.LastPlayedAt = parsed
						}
					}
				}

				if dName.Valid {
					d.ID = sp.DeviceID
					d.Name = dName.String
					if cStr.Valid {
						if parsed, e := time.Parse(time.RFC3339Nano, cStr.String); e == nil {
							d.CreatedAt = parsed
						} else {
							if parsed, e := time.Parse(layout, cStr.String); e == nil {
								d.CreatedAt = parsed
							}
						}
					}
					sp.DeviceInfo = &d
				}

				if sTitle.Valid {
					s.ID = sp.SongID
					s.Title = sTitle.String
					if sArtist.Valid {
						s.Artist = sArtist.String
					}
					if sAlbum.Valid {
						s.Album = sAlbum.String
					}
					if sArt.Valid {
						s.ArtworkPath = sArt.String
					}
					if sDur.Valid {
						s.DurationSec = int(sDur.Int64)
					}
					sp.SongInfo = &s
				}

				sph = append(sph, sp)
			}
		}
	}
	return sph
}

// DailyStatRaw represents a raw row in the daily_stats table.
type DailyStatRaw struct {
	Date         string `json:"date"`
	DeviceID     string `json:"device_id"`
	TotalSeconds int64  `json:"total_seconds"`
	PlayCount    int    `json:"play_count"`
}

func (mt *MusicTracker) GetDailyStatsRaw() []DailyStatRaw {
	mt.mu.RLock()
	defer mt.mu.RUnlock()
	stats := make([]DailyStatRaw, 0)
	rows, err := mt.db.Conn.Query(`
		SELECT date, device_id, total_seconds, play_count 
		FROM daily_stats 
		ORDER BY date DESC, device_id ASC
	`)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var ds DailyStatRaw
			if err := rows.Scan(&ds.Date, &ds.DeviceID, &ds.TotalSeconds, &ds.PlayCount); err == nil {
				stats = append(stats, ds)
			}
		}
	}
	return stats
}

// GetDeviceSyncPayload returns a full batch payload for a specific device, used for client restores.
func (mt *MusicTracker) GetDeviceSyncPayload(deviceID string) (DeviceSyncPayload, error) {
	mt.mu.RLock()
	defer mt.mu.RUnlock()

	payload := DeviceSyncPayload{
		DeviceID: deviceID,
		Tracks:   make([]SyncedTrackPayload, 0),
		Plays:    make([]SyncedPlayLogPayload, 0),
	}

	err := mt.db.Conn.QueryRow(`
		SELECT d.name, dh.total_listening_time_sec, dh.total_song_plays
		FROM devices d
		LEFT JOIN device_history dh ON d.id = dh.device_id
		WHERE d.id = ?
	`, deviceID).Scan(&payload.DeviceName, &payload.TotalSeconds, &payload.TotalPlays)
	if err != nil {
		return payload, fmt.Errorf("device not found or has no history")
	}

	tRows, err := mt.db.Conn.Query(`
		SELECT s.id, s.title, s.artist, s.album,
		       sph.play_count, sph.total_listen_duration_sec,
		       (SELECT MIN(unix_timestamp) FROM play_logs WHERE device_id = ? AND song_id = s.id) as first_played,
		       sph.last_played_at
		FROM songs s
		JOIN songs_play_history sph ON s.id = sph.song_id
		WHERE sph.device_id = ?
	`, deviceID, deviceID)
	if err == nil {
		defer tRows.Close()
		for tRows.Next() {
			var t SyncedTrackPayload
			var artistStr string
			var lpStr string
			var fp sql.NullInt64
			if err := tRows.Scan(&t.SongID, &t.Title, &artistStr, &t.Album, &t.PlayCount, &t.TotalSeconds, &fp, &lpStr); err == nil {
				if fp.Valid {
					t.FirstPlayed = fp.Int64
				}
				if parsed, e := time.Parse("2006-01-02 15:04:05.999999999 -0700 MST", lpStr); e == nil {
					t.LastPlayed = parsed.UnixMilli()
				} else if parsed, e := time.Parse(time.RFC3339Nano, lpStr); e == nil {
					t.LastPlayed = parsed.UnixMilli()
				}
				json.Unmarshal([]byte(artistStr), &t.Artists)
				payload.Tracks = append(payload.Tracks, t)
			}
		}
	}

	pRows, err := mt.db.Conn.Query(`
		SELECT song_id, title, artists_json, album, unix_timestamp, duration_ms
		FROM play_logs
		WHERE device_id = ?
	`, deviceID)
	if err == nil {
		defer pRows.Close()
		for pRows.Next() {
			var p SyncedPlayLogPayload
			var artistStr string
			if err := pRows.Scan(&p.SongID, &p.Title, &artistStr, &p.Album, &p.Timestamp, &p.DurationMS); err == nil {
				json.Unmarshal([]byte(artistStr), &p.Artists)
				payload.Plays = append(payload.Plays, p)
			}
		}
	}

	return payload, nil
}

func (mt *MusicTracker) CreateAdmin(password string) error {
	hash := sha256.Sum256([]byte(password))
	hashHex := hex.EncodeToString(hash[:])

	_, err := mt.db.Conn.Exec(`
		INSERT INTO admin_users (id, password_hash) VALUES (1, ?)
		ON CONFLICT(id) DO UPDATE SET password_hash = excluded.password_hash
	`, hashHex)
	return err
}

func (mt *MusicTracker) VerifyAdmin(password string) bool {
	hash := sha256.Sum256([]byte(password))
	hashHex := hex.EncodeToString(hash[:])

	var storedHash string
	err := mt.db.Conn.QueryRow(`SELECT password_hash FROM admin_users WHERE id = 1`).Scan(&storedHash)
	if err != nil {
		return false
	}
	return storedHash == hashHex
}
