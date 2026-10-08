package db

import (
	"database/sql"
	"log"

	_ "modernc.org/sqlite"
)

// Database wraps the SQLite connection
type Database struct {
	Conn *sql.DB
}

// New initializes the database connection and ensures the schema exists.
func New(dbPath string) (*Database, error) {
	conn, err := sql.Open("sqlite", dbPath)
	if err != nil {
		return nil, err
	}

	// Optimize SQLite for concurrent reading & quick writes
	conn.SetMaxOpenConns(1)
	if _, err := conn.Exec(`
		PRAGMA journal_mode=WAL;
		PRAGMA synchronous=NORMAL;
	`); err != nil {
		log.Printf("[DB] Pragma warning: %v", err)
	}

	db := &Database{Conn: conn}
	if err := db.InitSchema(); err != nil {
		conn.Close()
		return nil, err
	}

	db.MigrateLegacyData()

	return db, nil
}

// Close closes the database connection.
func (db *Database) Close() error {
	if db.Conn != nil {
		return db.Conn.Close()
	}
	return nil
}

// InitSchema creates the tables if they do not exist.
func (db *Database) InitSchema() error {
	schema := `
	CREATE TABLE IF NOT EXISTS songs (
		id VARCHAR(50) PRIMARY KEY,
		title VARCHAR(255) NOT NULL,
		artist VARCHAR(255) NOT NULL,
		album VARCHAR(255),
		artwork_path VARCHAR(500),
		duration_sec INT NOT NULL DEFAULT 0
	);

	CREATE TABLE IF NOT EXISTS devices (
		id VARCHAR(50) PRIMARY KEY,
		name VARCHAR(100) NOT NULL,
		created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
	);

	CREATE TABLE IF NOT EXISTS device_history (
		device_id VARCHAR(50) PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
		total_songs_count INT DEFAULT 0,
		total_song_plays INT DEFAULT 0,
		total_listening_time_sec BIGINT DEFAULT 0,
		updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
	);

	CREATE TABLE IF NOT EXISTS songs_play_history (
		device_id VARCHAR(50) NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
		song_id VARCHAR(50) NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
		play_count INT DEFAULT 1,
		total_listen_duration_sec BIGINT NOT NULL DEFAULT 0,
		last_played_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
		PRIMARY KEY (device_id, song_id)
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
		duration_ms INTEGER DEFAULT 0
	);

	CREATE TABLE IF NOT EXISTS daily_stats (
		date TEXT,
		device_id VARCHAR(50) NOT NULL,
		total_seconds INTEGER NOT NULL DEFAULT 0,
		play_count INTEGER NOT NULL DEFAULT 0,
		PRIMARY KEY (date, device_id)
	);
	`
	_, err := db.Conn.Exec(schema)
	return err
}

// MigrateLegacyData detects old schema tables (like 'tracks') and migrates their data into the new schema.
func (db *Database) MigrateLegacyData() error {
	tx, err := db.Conn.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	// Check if legacy tracks table exists
	var count int
	_ = tx.QueryRow("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='tracks'").Scan(&count)
	if count > 0 {
		log.Printf("[DB] Legacy 'tracks' table detected. Migrating to new schema...")
		// 1. Migrate tracks to songs
		_, err = tx.Exec(`
			INSERT OR IGNORE INTO songs (id, title, artist, album, artwork_path, duration_sec)
			SELECT song_id, title, artists_json, album, COALESCE(artwork_path, ''), total_seconds
			FROM tracks
		`)
		if err != nil {
			log.Printf("[DB] Failed to migrate tracks: %v", err)
		}

		// 2. Migrate devices table structure
		_, err = tx.Exec(`
			INSERT OR IGNORE INTO device_history (device_id, total_songs_count, total_song_plays, total_listening_time_sec, updated_at)
			SELECT device_id, unique_tracks, total_plays, total_seconds, last_sync
			FROM devices
		`)
		if err != nil {
			log.Printf("[DB] Failed to migrate device_history: %v", err)
		}

		// 3. Migrate play_logs into songs_play_history
		_, err = tx.Exec(`
			INSERT INTO songs_play_history (device_id, song_id, play_count, total_listen_duration_sec, last_played_at)
			SELECT device_id, song_id, COUNT(*), SUM(duration_ms)/1000, MAX(timestamp)
			FROM play_logs
			GROUP BY device_id, song_id
			ON CONFLICT(device_id, song_id) DO UPDATE SET
				play_count = songs_play_history.play_count,
				total_listen_duration_sec = songs_play_history.total_listen_duration_sec,
				last_played_at = songs_play_history.last_played_at
		`)
		if err != nil {
			log.Printf("[DB] Failed to migrate songs_play_history: %v", err)
		}

		// Rename legacy tables out of the way or drop them
		_, _ = tx.Exec(`ALTER TABLE tracks RENAME TO legacy_tracks`)
	}

	// Check if daily_stats is the old version (has no device_id)
	var hasDeviceID int
	err = tx.QueryRow("SELECT COUNT(*) FROM pragma_table_info('daily_stats') WHERE name='device_id'").Scan(&hasDeviceID)
	if err == nil && hasDeviceID == 0 {
		log.Printf("[DB] Migrating daily_stats to include device_id...")
		_, _ = tx.Exec(`ALTER TABLE daily_stats RENAME TO legacy_daily_stats`)
		_, _ = tx.Exec(`
			CREATE TABLE daily_stats (
				date TEXT,
				device_id VARCHAR(50) NOT NULL,
				total_seconds INTEGER NOT NULL DEFAULT 0,
				play_count INTEGER NOT NULL DEFAULT 0,
				PRIMARY KEY (date, device_id)
			)
		`)

	}

	if err := tx.Commit(); err != nil {
		return err
	}

	return nil
}
