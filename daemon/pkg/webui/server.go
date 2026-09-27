package webui

import (
	"crypto/sha1"
	_ "embed"
	"encoding/base64"
	"encoding/binary"
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
	"strings"
	"sync"
	"time"

	"github.com/quazaar/synker/daemon/pkg/mpris"
	"github.com/quazaar/synker/daemon/pkg/protocol"
	"github.com/quazaar/synker/daemon/pkg/tracker"
)

//go:embed dashboard.html
var dashboardHTML []byte

type DashboardState struct {
	DeviceID     string                     `json:"device_id"`
	DeviceName   string                     `json:"device_name"`
	WebPort      int                        `json:"web_port"`
	MediaState   protocol.MediaStatePayload `json:"media_state"`
	LiveState    LiveMediaState             `json:"live_state"`
	MusicHistory tracker.StorageData        `json:"music_history"`
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
	port         int
	deviceID     string
	deviceName   string
	mprisMon     *mpris.MPRISMonitor
	tracker      *tracker.MusicTracker
	liveMu       sync.RWMutex
	liveState    LiveMediaState
	artworkMu    sync.RWMutex
	artworkData  []byte // raw JPEG bytes for current track artwork (from mobile client)
	wsClientsMu  sync.Mutex
	wsClients    map[chan []byte]bool
}

func NewWebServer(
	webPort int,
	deviceID string,
	deviceName string,
	mprisMon *mpris.MPRISMonitor,
	tracker *tracker.MusicTracker,
) *WebServer {
	ws := &WebServer{
		port:       webPort,
		deviceID:   deviceID,
		deviceName: deviceName,
		mprisMon:   mprisMon,
		tracker:    tracker,
		wsClients:  make(map[chan []byte]bool),
		liveState: LiveMediaState{
			DeviceID:       deviceID,
			DeviceName:     deviceName,
			PlaybackStatus: "Idle",
			Source:         "Daemon",
			LastUpdated:    time.Now(),
		},
	}
	return ws
}

// SetLiveMediaState updates the active playback state (from MPRIS or Client WebSocket) and broadcasts to all WebSocket subscribers
func (ws *WebServer) SetLiveMediaState(state LiveMediaState) {
	ws.liveMu.Lock()
	if len(state.Artists) > 0 && state.Artist == "" {
		state.Artist = fmt.Sprintf("%v", state.Artists[0])
	}
	state.LastUpdated = time.Now()
	ws.liveState = state
	ws.liveMu.Unlock()

	ws.broadcastLiveState(state)
}

func (ws *WebServer) GetLiveMediaState() LiveMediaState {
	ws.liveMu.RLock()
	defer ws.liveMu.RUnlock()

	// If a mobile client hasn't updated recently, fallback to MPRIS
	if ws.liveState.PlaybackStatus == "Playing" && time.Since(ws.liveState.LastUpdated) < 15*time.Second {
		return ws.liveState
	}

	if ws.mprisMon != nil {
		mState := ws.mprisMon.GetCurrentState()
		if mState.PlaybackStatus == "Playing" || mState.Title != "" {
			return LiveMediaState{
				DeviceID:       ws.deviceID,
				DeviceName:     ws.deviceName,
				Title:          mState.Title,
				Artist:         mState.Artist,
				Artists:        tracker.SplitArtists(mState.Artist),
				Album:          mState.Album,
				PlaybackStatus: mState.PlaybackStatus,
				CurrentSeconds: mState.PositionMS / 1000,
				DurationMS:     mState.DurationMS,
				ArtworkURL:     mState.ArtURL,
				Source:         mState.PlayerName,
				LastUpdated:    time.Now(),
			}
		}
	}

	return ws.liveState
}

func (ws *WebServer) broadcastLiveState(state LiveMediaState) {
	data, err := json.Marshal(map[string]interface{}{
		"type": "now_playing",
		"data": state,
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
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(dashboardHTML)
	})

	// ── API v1 ──────────────────────────────────────────────────────────────

	// JSON State Endpoint
	mux.HandleFunc("/api/v1/state", func(w http.ResponseWriter, r *http.Request) {
		var mediaState protocol.MediaStatePayload
		if ws.mprisMon != nil {
			mediaState = ws.mprisMon.GetCurrentState()
		}

		var history tracker.StorageData
		if ws.tracker != nil {
			history = ws.tracker.GetData()
		}

		state := DashboardState{
			DeviceID:     ws.deviceID,
			DeviceName:   ws.deviceName,
			WebPort:      ws.port,
			MediaState:   mediaState,
			LiveState:    ws.GetLiveMediaState(),
			MusicHistory: history,
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(state)
	})

	// Dedicated Music History Endpoint
	mux.HandleFunc("/api/v1/history", func(w http.ResponseWriter, r *http.Request) {
		var history tracker.StorageData
		if ws.tracker != nil {
			history = ws.tracker.GetData()
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(history)
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

		if ws.mprisMon != nil {
			_ = ws.mprisMon.SendCommand(cmd)
		}
		w.WriteHeader(http.StatusOK)
	})

	// Mobile Batch Sync Endpoint (Local SQLite <-> Daemon Synchronization)
	mux.HandleFunc("/api/v1/sync", func(w http.ResponseWriter, r *http.Request) {
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
	})

	// Connected Devices Endpoint
	mux.HandleFunc("/api/v1/devices", func(w http.ResponseWriter, r *http.Request) {
		var devices []tracker.DeviceInfo
		if ws.tracker != nil {
			data := ws.tracker.GetData()
			devices = data.Devices
		}
		if devices == nil {
			devices = []tracker.DeviceInfo{}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(devices)
	})

	// Mobile Ingestion Endpoint (no pairing required)
	mux.HandleFunc("/api/v1/notify", func(w http.ResponseWriter, r *http.Request) {
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
	})

	// Current Playing REST Endpoint (JSON)
	mux.HandleFunc("/api/v1/current", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Content-Type", "application/json")
		state := ws.GetLiveMediaState()

		// Determine the best artwork_url to expose in the JSON response.
		// We always point to /api/v1/current/artwork when we have any artwork source,
		// so callers get a stable, always-valid URL regardless of source (mobile or MPRIS).
		ws.artworkMu.RLock()
		hasLocalArt := len(ws.artworkData) > 0
		ws.artworkMu.RUnlock()

		scheme := "http"
		selfArtURL := scheme + "://" + r.Host + "/api/v1/current/artwork"

		if hasLocalArt {
			// Mobile client sent artwork — serve from memory
			state.ArtworkURL = selfArtURL
		} else if state.ArtworkURL != "" {
			if len(state.ArtworkURL) >= 8 && (state.ArtworkURL[:7] == "http://" || state.ArtworkURL[:8] == "https://") {
				// MPRIS gave an http(s) URL — keep it directly
			} else if len(state.ArtworkURL) >= 7 && state.ArtworkURL[:7] == "file://" {
				// MPRIS gave a file:// path — proxy via our endpoint (which reads the file)
				state.ArtworkURL = selfArtURL
			} else {
				state.ArtworkURL = ""
			}
		}

		_ = json.NewEncoder(w).Encode(state)
	})

	// Current Playing Artwork Endpoint (JPEG/PNG image)
	mux.HandleFunc("/api/v1/current/artwork", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")

		// Priority 1: In-memory artwork from mobile client (Base64-decoded JPEG)
		ws.artworkMu.RLock()
		data := make([]byte, len(ws.artworkData))
		copy(data, ws.artworkData)
		ws.artworkMu.RUnlock()

		if len(data) > 0 {
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write(data)
			return
		}

		// Priority 2: MPRIS artwork — handle both http(s) redirect and file:// local read
		state := ws.GetLiveMediaState()
		artURL := state.ArtworkURL
		if artURL != "" {
			if len(artURL) >= 8 && (artURL[:7] == "http://" || artURL[:8] == "https://") {
				http.Redirect(w, r, artURL, http.StatusTemporaryRedirect)
				return
			}
			if len(artURL) >= 7 && artURL[:7] == "file://" {
				localPath := artURL[7:] // strip "file://"
				fileData, err := os.ReadFile(localPath)
				if err == nil && len(fileData) > 0 {
					// Detect content type from file extension
					ct := "image/jpeg"
					if len(localPath) > 4 && localPath[len(localPath)-4:] == ".png" {
						ct = "image/png"
					}
					w.Header().Set("Content-Type", ct)
					_, _ = w.Write(fileData)
					return
				}
			}
		}

		http.Error(w, "no artwork available", http.StatusNoContent)
	})

	// Current Playing Dynamic SVG Badge / Card Endpoint
	mux.HandleFunc("/api/v1/current.svg", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/svg+xml; charset=utf-8")
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		state := ws.GetLiveMediaState()
		// Resolve artwork URL: use in-memory art endpoint when available
		ws.artworkMu.RLock()
		hasLocalArt := len(ws.artworkData) > 0
		ws.artworkMu.RUnlock()
		artURL := state.ArtworkURL
		if hasLocalArt {
			// Build absolute URL from Host header for SVG embedding
			scheme := "http"
			artURL = scheme + "://" + r.Host + "/api/v1/current/artwork"
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
	mux.HandleFunc("/ws/v1/live", func(w http.ResponseWriter, r *http.Request) {
		ws.handleLiveWebSocket(w, r)
	})

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
	// Standard HTTP Upgrade for WebSocket RFC 6455
	if r.Header.Get("Upgrade") != "websocket" {
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
		"type": "now_playing",
		"data": ws.GetLiveMediaState(),
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
				// Decode and store artwork if provided
				if incoming.Data.ArtworkData != "" {
					jpegBytes, decErr := base64.StdEncoding.DecodeString(incoming.Data.ArtworkData)
					if decErr == nil && len(jpegBytes) > 0 {
						ws.artworkMu.Lock()
						ws.artworkData = jpegBytes
						ws.artworkMu.Unlock()
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
		source = "Quazaar Synker"
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
