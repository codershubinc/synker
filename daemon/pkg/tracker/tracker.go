package tracker

import (
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

// PlayLogEntry represents an individual recorded play event.
type PlayLogEntry struct {
	EventID       string    `json:"event_id"`
	SongID        string    `json:"song_id"`
	Title         string    `json:"title"`
	Artists       []string  `json:"artists"`
	Album         string    `json:"album,omitempty"`
	SourceApp     string    `json:"source_app"`
	DeviceID      string    `json:"device_id"`
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
	SongID    string   `json:"song_id"`
	Title     string   `json:"title"`
	Artists   []string `json:"artists"`
	Album     string   `json:"album"`
	Timestamp int64    `json:"timestamp"`
}

// SyncedDailyStat represents aggregated daily listening time from client.
type SyncedDailyStat struct {
	Date         string `json:"date"`
	TotalSeconds int64  `json:"total_seconds"`
	PlayCount    int    `json:"play_count"`
}

// DeviceSyncPayload represents the complete batch sync packet sent by a mobile client.
type DeviceSyncPayload struct {
	DeviceID     string                 `json:"device_id"`
	DeviceName   string                 `json:"device_name"`
	TotalSeconds int64                  `json:"total_seconds"`
	TotalPlays   int                    `json:"total_plays"`
	Tracks       []SyncedTrackPayload   `json:"tracks"`
	Plays        []SyncedPlayLogPayload `json:"plays"`
	DailyStats   []SyncedDailyStat      `json:"daily_stats"`
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
	db         *sql.DB
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

	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		return nil, fmt.Errorf("failed to open sqlite database at %s: %w", dbPath, err)
	}

	// Optimize SQLite for concurrent reading & quick writes
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(`
		PRAGMA journal_mode=WAL;
		PRAGMA synchronous=NORMAL;
	`); err != nil {
		log.Printf("[Tracker] Pragma warning: %v", err)
	}

	mt := &MusicTracker{
		db:         db,
		dbPath:     dbPath,
		jsonBackup: jsonBackup,
		artworkDir: artworkDir,
	}

	if err := mt.initSchema(); err != nil {
		db.Close()
		return nil, fmt.Errorf("failed to initialize sqlite schema: %w", err)
	}
	mt.migrateSchemaIfNeeded()

	// Migrate from previous JSON file if it exists and SQLite is empty
	mt.migrateFromJSONIfEmpty()

	totalTracks, totalPlays := mt.getCounts()
	log.Printf("[Tracker] SQLite DB ready at %s (%d tracks, %d total plays)", dbPath, totalTracks, totalPlays)

	return mt, nil
}

func (mt *MusicTracker) initSchema() error {
	schema := `
	CREATE TABLE IF NOT EXISTS tracks (
		song_id TEXT PRIMARY KEY,
		title TEXT NOT NULL,
		artists_json TEXT NOT NULL,
		album TEXT,
		play_count INTEGER NOT NULL DEFAULT 0,
		first_played TIMESTAMP NOT NULL,
		last_played TIMESTAMP NOT NULL,
		last_device TEXT,
		source_apps_json TEXT NOT NULL,
		total_seconds INTEGER NOT NULL DEFAULT 0,
		artwork_path TEXT
	);

	CREATE TABLE IF NOT EXISTS play_logs (
		event_id TEXT PRIMARY KEY,
		song_id TEXT NOT NULL,
		title TEXT NOT NULL,
		artists_json TEXT NOT NULL,
		album TEXT,
		source_app TEXT NOT NULL,
		device_id TEXT NOT NULL,
		device_name TEXT NOT NULL,
		timestamp TIMESTAMP NOT NULL,
		unix_timestamp INTEGER NOT NULL,
		duration_ms INTEGER DEFAULT 0,
		FOREIGN KEY(song_id) REFERENCES tracks(song_id)
	);

	CREATE TABLE IF NOT EXISTS devices (
		device_id TEXT PRIMARY KEY,
		device_name TEXT NOT NULL,
		last_sync TIMESTAMP NOT NULL,
		total_seconds INTEGER NOT NULL DEFAULT 0,
		total_plays INTEGER NOT NULL DEFAULT 0,
		unique_tracks INTEGER NOT NULL DEFAULT 0
	);

	CREATE TABLE IF NOT EXISTS daily_stats (
		date TEXT PRIMARY KEY,
		total_seconds INTEGER NOT NULL DEFAULT 0,
		play_count INTEGER NOT NULL DEFAULT 0
	);

	CREATE INDEX IF NOT EXISTS idx_play_logs_song_id ON play_logs(song_id);
	CREATE INDEX IF NOT EXISTS idx_play_logs_timestamp ON play_logs(timestamp DESC);
	`
	_, err := mt.db.Exec(schema)
	return err
}

func (mt *MusicTracker) migrateSchemaIfNeeded() {
	_, _ = mt.db.Exec(`ALTER TABLE tracks ADD COLUMN total_seconds INTEGER NOT NULL DEFAULT 0`)
	_, _ = mt.db.Exec(`ALTER TABLE tracks ADD COLUMN artwork_path TEXT`)
}

func (mt *MusicTracker) Close() error {
	if mt.db != nil {
		return mt.db.Close()
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

	// Debounce: don't log the same track repeatedly within 30 seconds
	if mt.lastTrack == songID && now.Sub(mt.lastPlayAt) < 30*time.Second {
		return false
	}

	mt.lastTrack = songID
	mt.lastPlayAt = now

	eventID := fmt.Sprintf("evt_%d_%s", now.UnixNano(), songID[:6])
	artistsJSON, _ := json.Marshal(artists)

	tx, err := mt.db.Begin()
	if err != nil {
		log.Printf("[Tracker] Error starting SQL transaction: %v", err)
		return false
	}
	defer tx.Rollback()

	// 1. Fetch or create track in `tracks` table
	var existingPlayCount int
	var existingFirstPlayed time.Time
	var existingAppsJSON string
	err = tx.QueryRow(`
		SELECT play_count, first_played, source_apps_json 
		FROM tracks WHERE song_id = ?
	`, songID).Scan(&existingPlayCount, &existingFirstPlayed, &existingAppsJSON)

	var sourceApps []string
	if err == sql.ErrNoRows {
		sourceApps = []string{appName}
		appsJSON, _ := json.Marshal(sourceApps)
		lastDev := fmt.Sprintf("%s (%s)", deviceName, deviceID)

		_, err = tx.Exec(`
			INSERT INTO tracks (song_id, title, artists_json, album, play_count, first_played, last_played, last_device, source_apps_json)
			VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?)
		`, songID, strings.TrimSpace(title), string(artistsJSON), strings.TrimSpace(album), now, now, lastDev, string(appsJSON))
		if err != nil {
			log.Printf("[Tracker] Error inserting track into SQLite: %v", err)
			return false
		}
	} else if err == nil {
		_ = json.Unmarshal([]byte(existingAppsJSON), &sourceApps)
		hasApp := false
		for _, a := range sourceApps {
			if a == appName {
				hasApp = true
				break
			}
		}
		if !hasApp {
			sourceApps = append(sourceApps, appName)
		}
		appsJSON, _ := json.Marshal(sourceApps)
		lastDev := fmt.Sprintf("%s (%s)", deviceName, deviceID)

		_, err = tx.Exec(`
			UPDATE tracks 
			SET play_count = play_count + 1,
			    last_played = ?,
			    last_device = ?,
			    source_apps_json = ?,
			    album = CASE WHEN ? != '' THEN ? ELSE album END
			WHERE song_id = ?
		`, now, lastDev, string(appsJSON), strings.TrimSpace(album), strings.TrimSpace(album), songID)
		if err != nil {
			log.Printf("[Tracker] Error updating track in SQLite: %v", err)
			return false
		}
	} else {
		log.Printf("[Tracker] Query error: %v", err)
		return false
	}

	// 2. Insert into play_logs
	_, err = tx.Exec(`
		INSERT INTO play_logs (event_id, song_id, title, artists_json, album, source_app, device_id, device_name, timestamp, unix_timestamp, duration_ms)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`, eventID, songID, strings.TrimSpace(title), string(artistsJSON), strings.TrimSpace(album), appName, deviceID, deviceName, now, now.UnixMilli(), durationMS)
	if err != nil {
		log.Printf("[Tracker] Error inserting play log into SQLite: %v", err)
		return false
	}

	if err := tx.Commit(); err != nil {
		log.Printf("[Tracker] Commit error: %v", err)
		return false
	}

	playNum := existingPlayCount + 1
	log.Printf("[Tracker] [SQLite] Recorded Apple Music play: \"%s\" by %s (Play #%d) [Device: %s]",
		title, strings.Join(artists, ", "), playNum, deviceName)

	// Save mirror JSON snapshot in background
	go mt.exportSnapshotJSON()

	return true
}

// GetData queries SQLite and returns the full structured data representation.
func (mt *MusicTracker) GetData() StorageData {
	mt.mu.RLock()
	defer mt.mu.RUnlock()

	data := StorageData{
		Version:     "0.0.1",
		StorageType: "sqlite3",
		LastUpdated: time.Now(),
		Tracks:      make(map[string]TrackStats),
		RecentPlays: make([]PlayLogEntry, 0),
	}

	// Total plays count
	_ = mt.db.QueryRow(`SELECT COUNT(*) FROM play_logs`).Scan(&data.TotalPlays)

	// Fetch tracks
	rows, err := mt.db.Query(`
		SELECT song_id, title, artists_json, album, play_count, first_played, last_played, last_device, source_apps_json, total_seconds, artwork_path
		FROM tracks
	`)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var t TrackStats
			var artistsJSON, appsJSON string
			var album, lastDev, artworkPath sql.NullString
			if err := rows.Scan(&t.SongID, &t.Title, &artistsJSON, &album, &t.PlayCount, &t.FirstPlayed, &t.LastPlayed, &lastDev, &appsJSON, &t.TotalSeconds, &artworkPath); err == nil {
				_ = json.Unmarshal([]byte(artistsJSON), &t.Artists)
				_ = json.Unmarshal([]byte(appsJSON), &t.SourceApps)
				if album.Valid {
					t.Album = album.String
				}
				if lastDev.Valid {
					t.LastDevice = lastDev.String
				}
				// Populate ArtworkURL if artwork file exists on disk
				if artworkPath.Valid && artworkPath.String != "" {
					if _, err := os.Stat(artworkPath.String); err == nil {
						t.ArtworkURL = "/api/v1/artwork/" + t.SongID
					}
				}
				data.Tracks[t.SongID] = t
			}
		}
	}

	// Fetch recent plays (up to 100)
	pRows, err := mt.db.Query(`
		SELECT event_id, song_id, title, artists_json, album, source_app, device_id, device_name, timestamp, unix_timestamp, duration_ms
		FROM play_logs
		ORDER BY timestamp DESC
		LIMIT 100
	`)
	if err == nil {
		defer pRows.Close()
		for pRows.Next() {
			var p PlayLogEntry
			var artistsJSON string
			var album sql.NullString
			if err := pRows.Scan(&p.EventID, &p.SongID, &p.Title, &artistsJSON, &album, &p.SourceApp, &p.DeviceID, &p.DeviceName, &p.Timestamp, &p.UnixTimestamp, &p.DurationMS); err == nil {
				_ = json.Unmarshal([]byte(artistsJSON), &p.Artists)
				if album.Valid {
					p.Album = album.String
				}
				data.RecentPlays = append(data.RecentPlays, p)
			}
		}
	}

	// Fetch Devices
	devRows, err := mt.db.Query(`
		SELECT device_id, device_name, last_sync, total_seconds, total_plays, unique_tracks
		FROM devices
		ORDER BY last_sync DESC
	`)
	if err == nil {
		defer devRows.Close()
		for devRows.Next() {
			var d DeviceInfo
			if err := devRows.Scan(&d.DeviceID, &d.DeviceName, &d.LastSync, &d.TotalSeconds, &d.TotalPlays, &d.UniqueTracks); err == nil {
				data.Devices = append(data.Devices, d)
				data.TotalSeconds += d.TotalSeconds
			}
		}
	}

	// Fetch Daily Stats (last 30 days)
	dayRows, err := mt.db.Query(`
		SELECT date, total_seconds, play_count
		FROM daily_stats
		ORDER BY date DESC
		LIMIT 30
	`)
	if err == nil {
		defer dayRows.Close()
		for dayRows.Next() {
			var ds SyncedDailyStat
			if err := dayRows.Scan(&ds.Date, &ds.TotalSeconds, &ds.PlayCount); err == nil {
				data.DailyStats = append(data.DailyStats, ds)
			}
		}
	}

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

	tx, err := mt.db.Begin()
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer tx.Rollback()

	now := time.Now()

	// 1. Upsert Tracks
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
		firstTime := time.UnixMilli(t.FirstPlayed)
		if t.FirstPlayed == 0 {
			firstTime = now
		}
		lastTime := time.UnixMilli(t.LastPlayed)
		if t.LastPlayed == 0 {
			lastTime = now
		}
		sourceAppsJSON, _ := json.Marshal([]string{"Apple Music (Synced)"})
		lastDev := fmt.Sprintf("%s (%s)", payload.DeviceName, payload.DeviceID)

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

		if artworkPath != "" {
			_, err = tx.Exec(`
				INSERT INTO tracks (song_id, title, artists_json, album, play_count, first_played, last_played, last_device, source_apps_json, total_seconds, artwork_path)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT(song_id) DO UPDATE SET
					play_count = MAX(tracks.play_count, excluded.play_count),
					album = CASE WHEN excluded.album != '' THEN excluded.album ELSE tracks.album END,
					last_played = CASE WHEN excluded.last_played > tracks.last_played THEN excluded.last_played ELSE tracks.last_played END,
					last_device = excluded.last_device,
					total_seconds = MAX(tracks.total_seconds, excluded.total_seconds),
					artwork_path = excluded.artwork_path
			`, songID, strings.TrimSpace(t.Title), string(artistsJSON), strings.TrimSpace(t.Album), t.PlayCount, firstTime, lastTime, lastDev, string(sourceAppsJSON), t.TotalSeconds, artworkPath)
		} else {
			_, err = tx.Exec(`
				INSERT INTO tracks (song_id, title, artists_json, album, play_count, first_played, last_played, last_device, source_apps_json, total_seconds)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT(song_id) DO UPDATE SET
					play_count = MAX(tracks.play_count, excluded.play_count),
					album = CASE WHEN excluded.album != '' THEN excluded.album ELSE tracks.album END,
					last_played = CASE WHEN excluded.last_played > tracks.last_played THEN excluded.last_played ELSE tracks.last_played END,
					last_device = excluded.last_device,
					total_seconds = MAX(tracks.total_seconds, excluded.total_seconds)
			`, songID, strings.TrimSpace(t.Title), string(artistsJSON), strings.TrimSpace(t.Album), t.PlayCount, firstTime, lastTime, lastDev, string(sourceAppsJSON), t.TotalSeconds)
		}
		if err != nil {
			log.Printf("[Tracker] Sync error inserting track %s: %v", t.Title, err)
		}
	}

	// 2. Insert Play Logs
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

		_, _ = tx.Exec(`
			INSERT OR IGNORE INTO play_logs (event_id, song_id, title, artists_json, album, source_app, device_id, device_name, timestamp, unix_timestamp, duration_ms)
			VALUES (?, ?, ?, ?, ?, 'Apple Music (Client)', ?, ?, ?, ?, 0)
		`, eventID, songID, strings.TrimSpace(p.Title), string(artistsJSON), strings.TrimSpace(p.Album), payload.DeviceID, payload.DeviceName, playTime, playTime.UnixMilli())
	}

	// 3. Upsert Daily Stats
	for _, ds := range payload.DailyStats {
		if strings.TrimSpace(ds.Date) == "" {
			continue
		}
		_, _ = tx.Exec(`
			INSERT INTO daily_stats (date, total_seconds, play_count)
			VALUES (?, ?, ?)
			ON CONFLICT(date) DO UPDATE SET
				total_seconds = MAX(daily_stats.total_seconds, excluded.total_seconds),
				play_count = MAX(daily_stats.play_count, excluded.play_count)
		`, ds.Date, ds.TotalSeconds, ds.PlayCount)
	}

	// 4. Update Device Record
	uniqueTracksCount := len(payload.Tracks)
	_, err = tx.Exec(`
		INSERT INTO devices (device_id, device_name, last_sync, total_seconds, total_plays, unique_tracks)
		VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT(device_id) DO UPDATE SET
			device_name = excluded.device_name,
			last_sync = excluded.last_sync,
			total_seconds = MAX(devices.total_seconds, excluded.total_seconds),
			total_plays = MAX(devices.total_plays, excluded.total_plays),
			unique_tracks = MAX(devices.unique_tracks, excluded.unique_tracks)
	`, payload.DeviceID, payload.DeviceName, now, payload.TotalSeconds, payload.TotalPlays, uniqueTracksCount)
	if err != nil {
		log.Printf("[Tracker] Device record update error: %v", err)
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("failed to commit sync transaction: %w", err)
	}

	log.Printf("[Tracker] Successfully synced data from %s (%s): %d tracks, %d plays, %d total seconds",
		payload.DeviceName, payload.DeviceID, len(payload.Tracks), len(payload.Plays), payload.TotalSeconds)

	go mt.exportSnapshotJSON()
	return nil
}

func (mt *MusicTracker) getCounts() (tracks int, plays int) {
	_ = mt.db.QueryRow(`SELECT COUNT(*) FROM tracks`).Scan(&tracks)
	_ = mt.db.QueryRow(`SELECT COUNT(*) FROM play_logs`).Scan(&plays)
	return
}

// GetArtworkPath returns the local file path for a track's artwork, or "" if not available.
func (mt *MusicTracker) GetArtworkPath(songID string) string {
	var artworkPath sql.NullString
	_ = mt.db.QueryRow(`SELECT artwork_path FROM tracks WHERE song_id = ?`, songID).Scan(&artworkPath)
	if artworkPath.Valid {
		return artworkPath.String
	}
	return ""
}

func (mt *MusicTracker) exportSnapshotJSON() {
	data := mt.GetData()
	bytes, err := json.MarshalIndent(data, "", "  ")
	if err == nil {
		_ = os.WriteFile(mt.jsonBackup, bytes, 0644)
	}
}

func (mt *MusicTracker) migrateFromJSONIfEmpty() {
	var count int
	if err := mt.db.QueryRow(`SELECT COUNT(*) FROM tracks`).Scan(&count); err != nil || count > 0 {
		return
	}

	raw, err := os.ReadFile(mt.jsonBackup)
	if err != nil {
		return
	}

	type OldStorageData struct {
		Tracks map[string]struct {
			SongID      string    `json:"song_id"`
			Title       string    `json:"title"`
			Artist      string    `json:"artist"`
			Album       string    `json:"album"`
			PlayCount   int       `json:"play_count"`
			FirstPlayed time.Time `json:"first_played"`
			LastPlayed  time.Time `json:"last_played"`
			LastDevice  string    `json:"last_device"`
			SourceApps  []string  `json:"source_apps"`
		} `json:"tracks"`
		RecentPlays []struct {
			EventID       string    `json:"event_id"`
			SongID        string    `json:"song_id"`
			Title         string    `json:"title"`
			Artist        string    `json:"artist"`
			Album         string    `json:"album"`
			SourceApp     string    `json:"source_app"`
			DeviceID      string    `json:"device_id"`
			DeviceName    string    `json:"device_name"`
			Timestamp     time.Time `json:"timestamp"`
			UnixTimestamp int64     `json:"unix_timestamp"`
			DurationMS    int64     `json:"duration_ms"`
		} `json:"recent_plays"`
	}

	var old OldStorageData
	if err := json.Unmarshal(raw, &old); err != nil {
		return
	}

	log.Printf("[Tracker] Migrating %d tracks and %d plays from legacy JSON to SQLite...", len(old.Tracks), len(old.RecentPlays))
	for _, t := range old.Tracks {
		artists := SplitArtists(t.Artist)
		artistsJSON, _ := json.Marshal(artists)
		appsJSON, _ := json.Marshal(t.SourceApps)

		_, _ = mt.db.Exec(`
			INSERT OR REPLACE INTO tracks (song_id, title, artists_json, album, play_count, first_played, last_played, last_device, source_apps_json)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
		`, t.SongID, t.Title, string(artistsJSON), t.Album, t.PlayCount, t.FirstPlayed, t.LastPlayed, t.LastDevice, string(appsJSON))
	}

	for _, p := range old.RecentPlays {
		artists := SplitArtists(p.Artist)
		artistsJSON, _ := json.Marshal(artists)
		_, _ = mt.db.Exec(`
			INSERT OR IGNORE INTO play_logs (event_id, song_id, title, artists_json, album, source_app, device_id, device_name, timestamp, unix_timestamp, duration_ms)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		`, p.EventID, p.SongID, p.Title, string(artistsJSON), p.Album, p.SourceApp, p.DeviceID, p.DeviceName, p.Timestamp, p.UnixTimestamp, p.DurationMS)
	}

	log.Printf("[Tracker] Migration to SQLite completed.")
}
