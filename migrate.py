import sqlite3
import json

db_path = '/home/swap/.config/synker/music_history.db'
conn = sqlite3.connect(db_path)
cur = conn.cursor()

# Get existing tracks
cur.execute("SELECT song_id, title, artists_json, album, artwork_path, total_seconds FROM tracks")
tracks = cur.fetchall()

# Get existing devices
cur.execute("SELECT device_id, device_name, last_sync, total_seconds, total_plays, unique_tracks FROM devices")
devices = cur.fetchall()

# We need to compute songs_play_history from play_logs or from tracks ?
# tracks table has play_count and total_seconds but it's merged.
# play_logs has individual device plays! 
cur.execute("SELECT device_id, song_id, COUNT(*), SUM(duration_ms)/1000, MAX(timestamp) FROM play_logs GROUP BY device_id, song_id")
play_history = cur.fetchall()

# Wait, if play_logs doesn't have all data (if it was cleared), we might miss some. But let's assume it has it.
# Now create new tables
cur.executescript("""
CREATE TABLE IF NOT EXISTS songs (
    id VARCHAR(50) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    artist VARCHAR(255) NOT NULL,
    album VARCHAR(255),
    artwork_path VARCHAR(500),
    duration_sec INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS new_devices (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS device_history (
    device_id VARCHAR(50) PRIMARY KEY REFERENCES new_devices(id) ON DELETE CASCADE,
    total_songs_count INT DEFAULT 0,
    total_song_plays INT DEFAULT 0,
    total_listening_time_sec BIGINT DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS songs_play_history (
    device_id VARCHAR(50) NOT NULL REFERENCES new_devices(id) ON DELETE CASCADE,
    song_id VARCHAR(50) NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
    play_count INT DEFAULT 1,
    total_listen_duration_sec BIGINT NOT NULL DEFAULT 0,
    last_played_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (device_id, song_id)
);
""")

# Insert songs
for t in tracks:
    cur.execute("INSERT OR IGNORE INTO songs (id, title, artist, album, artwork_path, duration_sec) VALUES (?, ?, ?, ?, ?, ?)",
                (t[0], t[1], t[2], t[3], t[4], t[5]))

# Insert devices
for d in devices:
    cur.execute("INSERT OR IGNORE INTO new_devices (id, name, created_at) VALUES (?, ?, ?)",
                (d[0], d[1], d[2]))
    cur.execute("INSERT OR IGNORE INTO device_history (device_id, total_songs_count, total_song_plays, total_listening_time_sec, updated_at) VALUES (?, ?, ?, ?, ?)",
                (d[0], d[5], d[4], d[3], d[2]))

# Insert songs_play_history
for p in play_history:
    dev_id, song_id, count, duration, last_played = p
    if duration is None: duration = 0
    cur.execute("INSERT OR IGNORE INTO songs_play_history (device_id, song_id, play_count, total_listen_duration_sec, last_played_at) VALUES (?, ?, ?, ?, ?)",
                (dev_id, song_id, count, duration, last_played))

cur.executescript("""
DROP TABLE devices;
ALTER TABLE new_devices RENAME TO devices;
""")

conn.commit()
conn.close()
print("Migration done")
