package webui

import (
	"bytes"
	"crypto/rand"
	"crypto/sha1"
	_ "embed"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/quazaar/synker/daemon/internal/protocol"
	"github.com/quazaar/synker/daemon/internal/tracker"
)

//go:embed dashboard.html
var dashboardHTML []byte

//go:embed docs.html
var docsHTML []byte

//go:embed device_details.html
var deviceDetailsHTML []byte

//go:embed stats.html
var statsHTML []byte

type DashboardState struct {
	DeviceID     string                    `json:"device_id"`
	DeviceName   string                    `json:"device_name"`
	WebPort      int                       `json:"web_port"`
	LiveState    LiveMediaState            `json:"live_state"`
	DeviceStates map[string]LiveMediaState `json:"device_states"`
	MusicHistory tracker.StorageData       `json:"music_history"`
}

// LiveMediaState stores live playback information reported by mobile clients or MPRIS
type LiveMediaState struct {
	DeviceID       string    `json:"device_id"`
	DeviceName     string    `json:"device_name"`
	Title          string    `json:"title"`
	Artists        []string  `json:"artists"`
	Artist         string    `json:"artist"`
	Album          string    `json:"album"`
	PlaybackStatus string    `json:"playback_status"` // "Playing", "Paused", "Idle"
	CurrentSeconds int64     `json:"current_seconds"`
	DurationMS     int64     `json:"duration_ms"`
	ArtworkURL     string    `json:"artwork_url,omitempty"`
	Source         string    `json:"source"`
	LastUpdated    time.Time `json:"last_updated"`
}

type WebServer struct {
	authToken     string
	port          int
	deviceID      string
	deviceName    string
	tracker       *tracker.MusicTracker
	liveMu        sync.RWMutex
	liveState     LiveMediaState
	devicesState  map[string]LiveMediaState // Per-device live states
	artworkMu     sync.RWMutex
	artworkSongID string // SongID that matches artworkData
	artworkData   []byte // raw JPEG bytes for current track artwork (from mobile client)
	wsClientsMu   sync.Mutex
	wsClients     map[chan []byte]bool
}

func NewWebServer(
	webPort int,
	deviceID string,
	deviceName string,
	tracker *tracker.MusicTracker,
) *WebServer {
	initialState := LiveMediaState{
		DeviceID:       deviceID,
		DeviceName:     deviceName,
		PlaybackStatus: "Idle",
		Source:         "Daemon",
		LastUpdated:    time.Now(),
	}
	// Load or generate auth token
	configDir := filepath.Join(os.Getenv("HOME"), ".config", "synker")
	tokenPath := filepath.Join(configDir, "auth.token")
	var token string
	if b, err := os.ReadFile(tokenPath); err == nil && len(bytes.TrimSpace(b)) > 0 {
		token = string(bytes.TrimSpace(b))
	} else {
		// generate 32-char token
		b := make([]byte, 16)
		rand.Read(b)
		token = hex.EncodeToString(b)
		os.MkdirAll(configDir, 0700)
		os.WriteFile(tokenPath, []byte(token), 0600)
		log.Printf("[WebUI] Generated new auth token at %s", tokenPath)
	}

	ws := &WebServer{
		port:         webPort,
		deviceID:     deviceID,
		deviceName:   deviceName,
		tracker:      tracker,
		devicesState: make(map[string]LiveMediaState),
		wsClients:    make(map[chan []byte]bool),
		liveState:    initialState,
		authToken:    token,
	}
	ws.devicesState[deviceID] = initialState
	return ws
}

// SetLiveMediaState updates the active playback state (from MPRIS or Client WebSocket) and broadcasts to all WebSocket subscribers
func (ws *WebServer) SetLiveMediaState(state LiveMediaState) {
	ws.liveMu.Lock()
	if len(state.Artists) > 0 && state.Artist == "" {
		state.Artist = fmt.Sprintf("%v", state.Artists[0])
	}
	state.LastUpdated = time.Now()
	if state.DeviceID == "" {
		state.DeviceID = "client"
	}
	if ws.devicesState == nil {
		ws.devicesState = make(map[string]LiveMediaState)
	}
	ws.devicesState[state.DeviceID] = state
	ws.liveState = state
	ws.liveMu.Unlock()

	// If track changed, clear in-memory artwork if it belonged to previous track
	songID := tracker.GenerateSongID(state.Title, state.Artists)
	ws.artworkMu.Lock()
	if state.Title != "" && ws.artworkSongID != "" && ws.artworkSongID != songID {
		ws.artworkData = nil
		ws.artworkSongID = ""
	}
	ws.artworkMu.Unlock()

	ws.broadcastLiveState(state)
}

func (ws *WebServer) GetAllDeviceStates() map[string]LiveMediaState {
	ws.liveMu.RLock()
	defer ws.liveMu.RUnlock()

	res := make(map[string]LiveMediaState)
	for k, v := range ws.devicesState {
		res[k] = v
	}

	return res
}

func (ws *WebServer) GetLiveMediaState() LiveMediaState {
	ws.liveMu.RLock()
	defer ws.liveMu.RUnlock()

	// If a mobile client is actively playing, use it
	if ws.liveState.PlaybackStatus == "Playing" && time.Since(ws.liveState.LastUpdated) < 15*time.Second {
		return ws.liveState
	}

	return ws.liveState
}

func (ws *WebServer) broadcastLiveState(state LiveMediaState) {
	devices := ws.GetAllDeviceStates()
	data, err := json.Marshal(map[string]interface{}{
		"type":          "now_playing",
		"data":          state,
		"device_id":     state.DeviceID,
		"device_states": devices,
	})
	if err != nil {
		return
	}

	ws.wsClientsMu.Lock()
	defer ws.wsClientsMu.Unlock()
	for ch := range ws.wsClients {
		select {
		case ch <- data:
		default:
		}
	}
}

func (ws *WebServer) Start() {
	mux := ws.Handler()
	addr := fmt.Sprintf(":%d", ws.port)
	log.Printf("[WebUI] Dashboard available at http://localhost:%d", ws.port)
	go func() {
		if err := http.ListenAndServe(addr, mux); err != nil && err != http.ErrServerClosed {
			log.Printf("[WebUI] Server error: %v", err)
		}
	}()
}

func (ws *WebServer) Handler() http.Handler {
	mux := http.NewServeMux()

	// Dashboard UI
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(dashboardHTML)
	})

	mux.HandleFunc("/docs", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(docsHTML)
	})

	mux.HandleFunc("/device", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(deviceDetailsHTML)
	})

	mux.HandleFunc("/stats", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(statsHTML)
	})

	// ── API v1 ──────────────────────────────────────────────────────────────

	// JSON State Endpoint
	mux.HandleFunc("/api/v1/state", func(w http.ResponseWriter, r *http.Request) {
		var history tracker.StorageData
		if ws.tracker != nil {
			history = ws.tracker.GetData()
		}

		state := DashboardState{
			DeviceID:     ws.deviceID,
			DeviceName:   ws.deviceName,
			WebPort:      ws.port,
			LiveState:    ws.GetLiveMediaState(),
			DeviceStates: ws.GetAllDeviceStates(),
			MusicHistory: history,
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(state)
	})

	// JSON Summary Stats Endpoint
	mux.HandleFunc("/api/v1/summary", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Content-Type", "application/json")

		var totalSec int64
		var totalPlays int
		var uniqueSongs int
		var numDevices int
		var todaySec int64
		var todayPlays int

		if ws.tracker != nil {
			history := ws.tracker.GetHistoryStats()
			totalSec = history.TotalSeconds
			totalPlays = history.TotalPlays

			devices := ws.tracker.GetDevices()
			numDevices = len(devices)

			tracks := ws.tracker.GetTracks()
			uniqueSongs = len(tracks)

			dailyStats := ws.tracker.GetDailyStats()
			todayStr := time.Now().Format("2006-01-02")
			for _, d := range dailyStats {
				if d.Date == todayStr {
					todaySec = d.TotalSeconds
					todayPlays = d.PlayCount
					break
				}
			}
		}

		state := ws.GetLiveMediaState()
		songID := tracker.GenerateSongID(state.Title, state.Artists)

		ws.artworkMu.RLock()
		hasMemoryArt := ws.artworkSongID == songID && len(ws.artworkData) > 0
		ws.artworkMu.RUnlock()

		hasDiskArt := false
		if !hasMemoryArt && ws.tracker != nil && songID != "" {
			hasDiskArt = ws.tracker.GetArtworkPath(songID) != ""
		}

		scheme := "http"
		selfArtURL := scheme + "://" + r.Host + "/api/v1/current/artwork?id=" + songID

		if hasMemoryArt || hasDiskArt {
			state.ArtworkURL = selfArtURL
		} else if state.ArtworkURL != "" {
			if len(state.ArtworkURL) >= 8 && (state.ArtworkURL[:7] == "http://" || state.ArtworkURL[:8] == "https://") {
				// Keep as is
			} else if len(state.ArtworkURL) >= 7 && state.ArtworkURL[:7] == "file://" {
				state.ArtworkURL = selfArtURL
			} else {
				state.ArtworkURL = ""
			}
		} else {
			state.ArtworkURL = ""
		}

		summary := map[string]interface{}{
			"total_listening_time_sec": totalSec,
			"total_play_count": totalPlays,
			"all_songs": uniqueSongs,
			"device_numbers": numDevices,
			"todays_total_listening_time_sec": todaySec,
			"todays_play_count": todayPlays,
			"last_playing_song": state,
		}

		_ = json.NewEncoder(w).Encode(summary)
	})

	// Dedicated Music History Endpoint
	mux.HandleFunc("/api/v1/history", func(w http.ResponseWriter, r *http.Request) {
		var history tracker.StorageData
		if ws.tracker != nil {
			history = ws.tracker.GetHistoryStats()
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(history)
	})

	// Dedicated Tracks Endpoint
	mux.HandleFunc("/api/v1/tracks", func(w http.ResponseWriter, r *http.Request) {
		var tracks map[string]tracker.TrackStats
		if ws.tracker != nil {
			tracks = ws.tracker.GetTracks()
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(tracks)
	})

	// Dedicated Plays Endpoint
	mux.HandleFunc("/api/v1/plays", func(w http.ResponseWriter, r *http.Request) {
		var plays []tracker.PlayLogEntry
		if ws.tracker != nil {
			plays = ws.tracker.GetRecentPlays()
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(plays)
	})

	// Dedicated Daily Stats Endpoint
	mux.HandleFunc("/api/v1/daily", func(w http.ResponseWriter, r *http.Request) {
		var daily []tracker.SyncedDailyStat
		if ws.tracker != nil {
			daily = ws.tracker.GetDailyStats()
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(daily)
	})

	// Media Command Endpoint
	mux.HandleFunc("/api/v1/command", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		var cmd protocol.MediaCommandPayload
		if err := json.NewDecoder(r.Body).Decode(&cmd); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		w.WriteHeader(http.StatusOK)
	})

	// Mobile Batch Sync Endpoint (Local SQLite <-> Daemon Synchronization)

	// Data Pull Mechanism (Restore)
	mux.HandleFunc("/api/v1/sync/pull", ws.AuthMiddleware(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}
		if r.Method != http.MethodGet {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		deviceID := r.URL.Query().Get("device_id")
		if deviceID == "" {
			http.Error(w, "device_id is required", http.StatusBadRequest)
			return
		}

		if ws.tracker != nil {
			payload, err := ws.tracker.GetDeviceSyncPayload(deviceID)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(payload)
			return
		}
		http.Error(w, "tracker not ready", http.StatusInternalServerError)
	}))

	// Login Endpoint
	mux.HandleFunc("/api/v1/login", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		var creds struct {
			Password string `json:"password"`
		}
		if err := json.NewDecoder(r.Body).Decode(&creds); err != nil {
			http.Error(w, "Invalid body", http.StatusBadRequest)
			return
		}

		// Verify password (for now, simply check against a file or fallback to same token for simplicity)
		// We'll read the admin pass from ~/.config/synker/admin.pass
		if ws.tracker == nil {
			http.Error(w, "Tracker not ready", http.StatusInternalServerError)
			return
		}
		if !ws.tracker.VerifyAdmin(creds.Password) {
			http.Error(w, "Invalid password", http.StatusUnauthorized)
			return
		}

		// Password correct, return the token!
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{
			"token": ws.authToken,
		})
	})

	mux.HandleFunc("/api/v1/sync", ws.AuthMiddleware(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-Device-ID, X-Device-Name")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		var payload tracker.DeviceSyncPayload
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			http.Error(w, fmt.Sprintf("invalid payload: %v", err), http.StatusBadRequest)
			return
		}

		if payload.DeviceID == "" {
			payload.DeviceID = r.Header.Get("X-Device-ID")
		}
		if payload.DeviceName == "" {
			payload.DeviceName = r.Header.Get("X-Device-Name")
		}

		if ws.tracker != nil {
			if err := ws.tracker.SyncDeviceData(payload); err != nil {
				log.Printf("[WebUI] Sync error: %v", err)
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"status":    "synced",
			"timestamp": time.Now().UnixMilli(),
		})
	}))

	// Connected Devices Endpoint
	mux.HandleFunc("/api/v1/devices", func(w http.ResponseWriter, r *http.Request) {
		var devices []tracker.DeviceInfo
		if ws.tracker != nil {
			devices = ws.tracker.GetDevices()
		}
		if devices == nil {
			devices = []tracker.DeviceInfo{}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(devices)
	})

	// Mobile Ingestion Endpoint (no pairing required)
	mux.HandleFunc("/api/v1/notify", ws.AuthMiddleware(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		var notif protocol.NotificationPayload
		if err := json.NewDecoder(r.Body).Decode(&notif); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		if ws.tracker != nil {
			artists := tracker.SplitArtists(notif.Body)
			devName := r.Header.Get("X-Device-Name")
			if devName == "" {
				devName = "Mobile Client"
			}
			devID := r.Header.Get("X-Device-ID")
			if devID == "" {
				devID = "mobile"
			}
			ws.tracker.RecordPlay(notif.AppName, notif.Title, artists, "", devID, devName, 0)
		}

		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	}))

	// Current Playing REST Endpoint (JSON)
	mux.HandleFunc("/api/v1/current", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Content-Type", "application/json")
		state := ws.GetLiveMediaState()

		songID := tracker.GenerateSongID(state.Title, state.Artists)

		// Check if we have artwork for THIS SPECIFIC song in memory
		ws.artworkMu.RLock()
		hasMemoryArt := ws.artworkSongID == songID && len(ws.artworkData) > 0
		ws.artworkMu.RUnlock()

		// Check if we have artwork on disk for this song
		hasDiskArt := false
		if !hasMemoryArt && ws.tracker != nil && songID != "" {
			hasDiskArt = ws.tracker.GetArtworkPath(songID) != ""
		}

		scheme := "http"
		selfArtURL := scheme + "://" + r.Host + "/api/v1/current/artwork?id=" + songID

		if hasMemoryArt || hasDiskArt {
			state.ArtworkURL = selfArtURL
		} else if state.ArtworkURL != "" {
			if len(state.ArtworkURL) >= 8 && (state.ArtworkURL[:7] == "http://" || state.ArtworkURL[:8] == "https://") {
				// MPRIS gave an http(s) URL — keep it directly
			} else if len(state.ArtworkURL) >= 7 && state.ArtworkURL[:7] == "file://" {
				// MPRIS gave a file:// path — proxy via our endpoint
				state.ArtworkURL = selfArtURL
			} else {
				state.ArtworkURL = ""
			}
		} else {
			state.ArtworkURL = ""
		}

		_ = json.NewEncoder(w).Encode(state)
	})

	// Current Playing Artwork Endpoint (JPEG/PNG image)
	mux.HandleFunc("/api/v1/current/artwork", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")

		currState := ws.GetLiveMediaState()
		currSongID := tracker.GenerateSongID(currState.Title, currState.Artists)

		targetID := r.URL.Query().Get("id")
		if targetID == "" {
			targetID = currSongID
		}

		// Priority 1: In-memory artwork (MUST match the target track!)
		ws.artworkMu.RLock()
		var memData []byte
		if (targetID == "" || ws.artworkSongID == targetID) && len(ws.artworkData) > 0 {
			memData = make([]byte, len(ws.artworkData))
			copy(memData, ws.artworkData)
		}
		ws.artworkMu.RUnlock()

		if len(memData) > 0 {
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write(memData)
			return
		}

		// Priority 2: Disk cache in tracker for this song
		if ws.tracker != nil && targetID != "" {
			diskPath := ws.tracker.GetArtworkPath(targetID)
			if diskPath != "" {
				fileData, err := os.ReadFile(diskPath)
				if err == nil && len(fileData) > 0 {
					ct := "image/jpeg"
					if strings.HasSuffix(diskPath, ".png") {
						ct = "image/png"
					}
					w.Header().Set("Content-Type", ct)
					_, _ = w.Write(fileData)
					return
				}
			}
		}

		// Priority 3: MPRIS artwork if currently active track
		artURL := currState.ArtworkURL
		if artURL != "" {
			if len(artURL) >= 8 && (artURL[:7] == "http://" || artURL[:8] == "https://") {
				http.Redirect(w, r, artURL, http.StatusTemporaryRedirect)
				return
			}
			if len(artURL) >= 7 && artURL[:7] == "file://" {
				localPath := artURL[7:] // strip "file://"
				fileData, err := os.ReadFile(localPath)
				if err == nil && len(fileData) > 0 {
					ct := "image/jpeg"
					if strings.HasSuffix(localPath, ".png") {
						ct = "image/png"
					}
					w.Header().Set("Content-Type", ct)
					_, _ = w.Write(fileData)
					return
				}
			}
		}

		http.Error(w, "no artwork available for this track", http.StatusNoContent)
	})

	// Current Playing Dynamic SVG Badge / Card Endpoint
	mux.HandleFunc("/api/v1/current.svg", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/svg+xml; charset=utf-8")
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		state := ws.GetLiveMediaState()
		songID := tracker.GenerateSongID(state.Title, state.Artists)

		// Check artwork availability
		ws.artworkMu.RLock()
		hasMemoryArt := ws.artworkSongID == songID && len(ws.artworkData) > 0
		ws.artworkMu.RUnlock()

		hasDiskArt := false
		if !hasMemoryArt && ws.tracker != nil && songID != "" {
			hasDiskArt = ws.tracker.GetArtworkPath(songID) != ""
		}

		artURL := state.ArtworkURL
		if hasMemoryArt || hasDiskArt {
			scheme := "http"
			artURL = scheme + "://" + r.Host + "/api/v1/current/artwork?id=" + songID
		}
		svg := generateCurrentPlayingSVG(state, artURL)
		w.Write([]byte(svg))
	})

	// Current Playing PNG Image Card Endpoint
	mux.HandleFunc("/api/v1/current.png", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		state := ws.GetLiveMediaState()
		img := generateCurrentPlayingPNG(state)
		_ = png.Encode(w, img)
	})

	// WebSocket / Live Stream endpoint for mobile client & web dashboard
	mux.HandleFunc("/ws/v1/live", ws.AuthMiddleware(func(w http.ResponseWriter, r *http.Request) {
		ws.handleLiveWebSocket(w, r)
	}))

	// Per-Track Artwork Endpoint (served after sync from mobile client)
	// URL pattern: /api/v1/artwork/{song_id}
	mux.HandleFunc("/api/v1/artwork/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Cache-Control", "public, max-age=86400") // artwork is stable; cache for 1 day
		songID := strings.TrimPrefix(r.URL.Path, "/api/v1/artwork/")
		if songID == "" || ws.tracker == nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		artPath := ws.tracker.GetArtworkPath(songID)
		if artPath == "" {
			http.Error(w, "no artwork for this track", http.StatusNotFound)
			return
		}
		data, err := os.ReadFile(artPath)
		if err != nil {
			http.Error(w, "artwork file missing", http.StatusNotFound)
			return
		}
		ct := "image/jpeg"
		if strings.HasSuffix(artPath, ".png") {
			ct = "image/png"
		}
		w.Header().Set("Content-Type", ct)
		_, _ = w.Write(data)
	})

	// New Table-Specific Endpoints
	mux.HandleFunc("/api/v1/songs", func(w http.ResponseWriter, r *http.Request) {
		var songs []tracker.Song
		if ws.tracker != nil {
			songs = ws.tracker.GetSongs()
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(songs)
	})

	mux.HandleFunc("/api/v1/devices_raw", func(w http.ResponseWriter, r *http.Request) {
		var devices []tracker.Device
		if ws.tracker != nil {
			devices = ws.tracker.GetRawDevices()
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(devices)
	})

	mux.HandleFunc("/api/v1/device_history", func(w http.ResponseWriter, r *http.Request) {
		var histories []tracker.DeviceHistory
		if ws.tracker != nil {
			histories = ws.tracker.GetDeviceHistories()
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(histories)
	})

	mux.HandleFunc("/api/v1/songs_play_history", func(w http.ResponseWriter, r *http.Request) {
		var sph []tracker.SongPlayHistory
		if ws.tracker != nil {
			sph = ws.tracker.GetSongsPlayHistory()
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(sph)
	})

	mux.HandleFunc("/api/v1/play_logs", func(w http.ResponseWriter, r *http.Request) {
		var plays []tracker.PlayLogEntry
		if ws.tracker != nil {
			plays = ws.tracker.GetRecentPlays()
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(plays)
	})

	mux.HandleFunc("/api/v1/daily_stats", func(w http.ResponseWriter, r *http.Request) {
		var daily []tracker.DailyStatRaw
		if ws.tracker != nil {
			daily = ws.tracker.GetDailyStatsRaw()
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(daily)
	})

	// ── Backward-compat redirects for old /api/ paths ───────────────────────
	redirect := func(to string) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			http.Redirect(w, r, to, http.StatusMovedPermanently)
		}
	}
	mux.HandleFunc("/api/state", redirect("/api/v1/state"))
	mux.HandleFunc("/api/history", redirect("/api/v1/history"))
	mux.HandleFunc("/api/command", redirect("/api/v1/command"))
	mux.HandleFunc("/api/sync", redirect("/api/v1/sync"))
	mux.HandleFunc("/api/devices", redirect("/api/v1/devices"))
	mux.HandleFunc("/api/notify", redirect("/api/v1/notify"))
	mux.HandleFunc("/api/current", redirect("/api/v1/current"))
	mux.HandleFunc("/api/current.svg", redirect("/api/v1/current.svg"))
	mux.HandleFunc("/api/current.png", redirect("/api/v1/current.png"))
	mux.HandleFunc("/ws/live", func(w http.ResponseWriter, r *http.Request) {
		// WS upgrades can't do 301 — just handle it at the old path too
		ws.handleLiveWebSocket(w, r)
	})

	return mux
}

func (ws *WebServer) handleLiveWebSocket(w http.ResponseWriter, r *http.Request) {
	// Standard HTTP Upgrade for WebSocket RFC 6455 (tolerant of reverse proxy normalization)
	isWebSocket := strings.EqualFold(r.Header.Get("Upgrade"), "websocket") ||
		strings.Contains(strings.ToLower(r.Header.Get("Connection")), "upgrade") ||
		r.Header.Get("Sec-WebSocket-Key") != ""

	if !isWebSocket {
		http.Error(w, "Expected websocket upgrade", http.StatusBadRequest)
		return
	}

	hijacker, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "Websocket hijacking not supported", http.StatusInternalServerError)
		return
	}

	conn, bufrw, err := hijacker.Hijack()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	defer conn.Close()

	// Compute Sec-WebSocket-Accept
	key := r.Header.Get("Sec-WebSocket-Key")
	h := sha1Hash(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
	accept := base64Encode(h)

	// Send handshake response
	response := "HTTP/1.1 101 Switching Protocols\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Accept: " + accept + "\r\n\r\n"
	if _, err := bufrw.WriteString(response); err != nil {
		return
	}
	_ = bufrw.Flush()

	msgChan := make(chan []byte, 32)
	ws.wsClientsMu.Lock()
	ws.wsClients[msgChan] = true
	ws.wsClientsMu.Unlock()

	defer func() {
		ws.wsClientsMu.Lock()
		delete(ws.wsClients, msgChan)
		ws.wsClientsMu.Unlock()
	}()

	// Send immediate initial state
	initState, _ := json.Marshal(map[string]interface{}{
		"type":          "now_playing",
		"data":          ws.GetLiveMediaState(),
		"device_states": ws.GetAllDeviceStates(),
	})
	_ = writeWSFrame(conn, initState)

	done := make(chan struct{})

	// Read loop from client (receives live playback ticks from mobile clients)
	go func() {
		defer close(done)
		for {
			payload, err := readWSFrame(bufrw)
			if err != nil {
				return
			}
			if len(payload) == 0 {
				continue
			}

			var incoming struct {
				Type string `json:"type"`
				Data struct {
					LiveMediaState
					ArtworkData string `json:"artwork_data"` // base64-encoded JPEG from mobile client
				} `json:"data"`
			}
			if err := json.Unmarshal(payload, &incoming); err == nil && incoming.Type == "live_tick" {
				ws.SetLiveMediaState(incoming.Data.LiveMediaState)
				songID := tracker.GenerateSongID(incoming.Data.Title, incoming.Data.Artists)

				// Decode and store artwork if provided for this specific song
				if incoming.Data.ArtworkData != "" {
					jpegBytes, decErr := base64.StdEncoding.DecodeString(incoming.Data.ArtworkData)
					if decErr == nil && len(jpegBytes) > 0 {
						ws.artworkMu.Lock()
						ws.artworkData = jpegBytes
						ws.artworkSongID = songID
						ws.artworkMu.Unlock()

						if ws.tracker != nil && songID != "" {
							_ = ws.tracker.SaveArtwork(songID, jpegBytes)
						}
					}
				}
			}
		}
	}()

	// Write loop to client (pushes playback changes)
	for {
		select {
		case <-done:
			return
		case msg := <-msgChan:
			if err := writeWSFrame(conn, msg); err != nil {
				return
			}
		}
	}
}

func sha1Hash(s string) []byte {
	h := sha1.New()
	h.Write([]byte(s))
	return h.Sum(nil)
}

func base64Encode(b []byte) string {
	return base64.StdEncoding.EncodeToString(b)
}

func writeWSFrame(w io.Writer, payload []byte) error {
	length := len(payload)
	header := []byte{0x81} // FIN + Text opcode

	if length <= 125 {
		header = append(header, byte(length))
	} else if length <= 65535 {
		header = append(header, 126)
		b := make([]byte, 2)
		binary.BigEndian.PutUint16(b, uint16(length))
		header = append(header, b...)
	} else {
		header = append(header, 127)
		b := make([]byte, 8)
		binary.BigEndian.PutUint64(b, uint64(length))
		header = append(header, b...)
	}

	if _, err := w.Write(header); err != nil {
		return err
	}
	_, err := w.Write(payload)
	return err
}

func readWSFrame(r io.Reader) ([]byte, error) {
	header := make([]byte, 2)
	if _, err := io.ReadFull(r, header); err != nil {
		return nil, err
	}

	isMasked := (header[1] & 0x80) != 0
	length := int(header[1] & 0x7F)

	if length == 126 {
		b := make([]byte, 2)
		if _, err := io.ReadFull(r, b); err != nil {
			return nil, err
		}
		length = int(binary.BigEndian.Uint16(b))
	} else if length == 127 {
		b := make([]byte, 8)
		if _, err := io.ReadFull(r, b); err != nil {
			return nil, err
		}
		length = int(binary.BigEndian.Uint64(b))
	}

	var mask []byte
	if isMasked {
		mask = make([]byte, 4)
		if _, err := io.ReadFull(r, mask); err != nil {
			return nil, err
		}
	}

	payload := make([]byte, length)
	if _, err := io.ReadFull(r, payload); err != nil {
		return nil, err
	}

	if isMasked {
		for i := 0; i < length; i++ {
			payload[i] ^= mask[i%4]
		}
	}

	return payload, nil
}

func generateCurrentPlayingSVG(state LiveMediaState, artURL string) string {
	title := state.Title
	if title == "" {
		title = "Not Playing"
	}
	artist := state.Artist
	if artist == "" {
		artist = "Apple Music"
	}
	source := state.DeviceName
	if source == "" {
		source = state.Source
	}
	if source == "" {
		source = "Synker"
	}

	statusColor := "#10b981"
	statusText := "PLAYING"
	if state.PlaybackStatus != "Playing" && state.PlaybackStatus != "" {
		statusColor = "#8E8E93"
		statusText = "IDLE"
	}

	title = html.EscapeString(title)
	artist = html.EscapeString(artist)
	source = html.EscapeString(source)

	// Build artwork section: real image if URL available, gradient placeholder otherwise
	var artSection string
	if artURL != "" {
		artSection = fmt.Sprintf(`
  <!-- Artwork Image -->
  <defs>
    <clipPath id="artClip"><rect x="18" y="18" width="74" height="74" rx="12"/></clipPath>
  </defs>
  <rect x="18" y="18" width="74" height="74" rx="12" fill="url(#logoGrad)"/>
  <image href="%s" x="18" y="18" width="74" height="74" clip-path="url(#artClip)" preserveAspectRatio="xMidYMid slice"/>`, html.EscapeString(artURL))
	} else {
		artSection = `
  <!-- Logo Artwork Placeholder -->
  <rect x="18" y="18" width="74" height="74" rx="12" fill="url(#logoGrad)"/>
  <text x="55" y="65" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif" font-size="34" font-weight="bold" fill="white" text-anchor="middle"></text>`
	}

	return fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<svg width="420" height="110" viewBox="0 0 420 110" fill="none" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="420" y2="110" gradientUnits="userSpaceOnUse">
      <stop stop-color="#0E0F14"/>
      <stop offset="1" stop-color="#141824"/>
    </linearGradient>
    <linearGradient id="logoGrad" x1="0" y1="0" x2="1" y2="1">
      <stop stop-color="#FA2D48"/>
      <stop offset="1" stop-color="#FF5E3A"/>
    </linearGradient>
  </defs>

  <!-- Background Card -->
  <rect width="420" height="110" rx="16" fill="url(#bg)" stroke="#232736" stroke-width="1.5"/>
%s

  <!-- Playback Status Indicator -->
  <circle cx="108" cy="28" r="4.5" fill="%s"/>
  <text x="120" y="32" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif" font-size="10" font-weight="700" fill="%s" letter-spacing="0.5">%s</text>
  <text x="395" y="32" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif" font-size="10" font-weight="500" fill="#6B7280" text-anchor="end">%s</text>

  <!-- Track Title -->
  <text x="108" y="58" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif" font-size="16" font-weight="700" fill="#FFFFFF">%s</text>

  <!-- Artist / Album -->
  <text x="108" y="78" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif" font-size="12" font-weight="500" fill="#9CA3AF">%s</text>
</svg>`, artSection, statusColor, statusColor, statusText, source, title, artist)
}

func generateCurrentPlayingPNG(state LiveMediaState) image.Image {
	width := 420
	height := 110
	img := image.NewRGBA(image.Rect(0, 0, width, height))

	// Draw dark background (#0E0F14)
	bgColor := color.RGBA{R: 14, G: 15, B: 20, A: 255}
	draw.Draw(img, img.Bounds(), &image.Uniform{bgColor}, image.Point{}, draw.Src)

	// Draw Accent Artwork Box (left side 74x74, Apple Red #FA2D48)
	artColor := color.RGBA{R: 250, G: 45, B: 72, A: 255}
	artRect := image.Rect(18, 18, 92, 92)
	draw.Draw(img, artRect, &image.Uniform{artColor}, image.Point{}, draw.Src)

	// Status indicator bar (Green #10B981 if playing, Gray #6B7280 if stopped)
	statusCol := color.RGBA{R: 16, G: 185, B: 129, A: 255}
	if state.PlaybackStatus != "Playing" && state.PlaybackStatus != "" {
		statusCol = color.RGBA{R: 107, G: 114, B: 128, A: 255}
	}
	statusBar := image.Rect(108, 22, 116, 30)
	draw.Draw(img, statusBar, &image.Uniform{statusCol}, image.Point{}, draw.Src)

	return img
}

func (ws *WebServer) AuthMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodOptions {
			w.Header().Set("Access-Control-Allow-Origin", "*")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Sync-Token, X-Device-ID, X-Device-Name")
			w.WriteHeader(http.StatusOK)
			return
		}

		reqToken := r.Header.Get("X-Sync-Token")
		if reqToken == "" {
			authHeader := r.Header.Get("Authorization")
			if strings.HasPrefix(authHeader, "Bearer ") {
				reqToken = strings.TrimPrefix(authHeader, "Bearer ")
			}
		}

		if reqToken != ws.authToken {
			log.Printf("[Auth] Blocked unauthorized request to %s (token mismatch or missing)", r.URL.Path)
			http.Error(w, "Unauthorized - Invalid Bearer Token", http.StatusUnauthorized)
			return
		}

		next.ServeHTTP(w, r)
	}
}
