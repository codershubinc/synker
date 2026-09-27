package mpris

import (
	"context"
	"fmt"
	"log"
	"strings"
	"sync"
	"time"

	"github.com/godbus/dbus/v5"
	"github.com/quazaar/synker/daemon/pkg/protocol"
)

type MPRISMonitor struct {
	conn       *dbus.Conn
	mu         sync.RWMutex
	currState  protocol.MediaStatePayload
	stateCh    chan protocol.MediaStatePayload
	ctx        context.Context
	cancel     context.CancelFunc
}

func NewMPRISMonitor() (*MPRISMonitor, error) {
	conn, err := dbus.ConnectSessionBus()
	if err != nil {
		return nil, fmt.Errorf("failed to connect to session bus: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	return &MPRISMonitor{
		conn:    conn,
		stateCh: make(chan protocol.MediaStatePayload, 32),
		ctx:     ctx,
		cancel:  cancel,
	}, nil
}

func (m *MPRISMonitor) StateChannel() <-chan protocol.MediaStatePayload {
	return m.stateCh
}

func (m *MPRISMonitor) DBusConn() *dbus.Conn {
	return m.conn
}

func (m *MPRISMonitor) GetCurrentState() protocol.MediaStatePayload {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.currState
}

// StartMonitoring listens for active MPRIS players and property changes
func (m *MPRISMonitor) StartMonitoring() {
	// Subscribe to PropertiesChanged signals
	rule := "type='signal',interface='org.freedesktop.DBus.Properties',member='PropertiesChanged',path='/org/mpris/MediaPlayer2'"
	m.conn.BusObject().Call("org.freedesktop.DBus.AddMatch", 0, rule)

	dbusChan := make(chan *dbus.Signal, 64)
	m.conn.Signal(dbusChan)

	// Poll periodically and also process DBus signals
	go func() {
		ticker := time.NewTicker(2 * time.Second)
		defer ticker.Stop()

		m.pollActivePlayer()

		for {
			select {
			case <-m.ctx.Done():
				return
			case sig := <-dbusChan:
				if strings.Contains(sig.Sender, "MediaPlayer2") || strings.HasPrefix(string(sig.Path), "/org/mpris/MediaPlayer2") {
					m.pollActivePlayer()
				}
			case <-ticker.C:
				m.pollActivePlayer()
			}
		}
	}()
}

// FindFirstActivePlayer returns the DBus destination for the active player
func (m *MPRISMonitor) FindFirstActivePlayer() string {
	var names []string
	err := m.conn.BusObject().Call("org.freedesktop.DBus.ListNames", 0).Store(&names)
	if err != nil {
		return ""
	}

	for _, name := range names {
		if strings.HasPrefix(name, "org.mpris.MediaPlayer2.") {
			return name
		}
	}
	return ""
}

func (m *MPRISMonitor) pollActivePlayer() {
	playerName := m.FindFirstActivePlayer()
	if playerName == "" {
		m.mu.Lock()
		if m.currState.PlaybackStatus != "Stopped" || m.currState.Title != "" {
			m.currState = protocol.MediaStatePayload{
				PlayerName:     "",
				PlaybackStatus: "Stopped",
			}
			select {
			case m.stateCh <- m.currState:
			default:
			}
		}
		m.mu.Unlock()
		return
	}

	obj := m.conn.Object(playerName, "/org/mpris/MediaPlayer2")

	// Get PlaybackStatus
	statusVal, err := obj.GetProperty("org.mpris.MediaPlayer2.Player.PlaybackStatus")
	playbackStatus := "Stopped"
	if err == nil {
		if s, ok := statusVal.Value().(string); ok {
			playbackStatus = s
		}
	}

	// Get Metadata
	metaVal, err := obj.GetProperty("org.mpris.MediaPlayer2.Player.Metadata")
	var title, artist, album, artURL string
	var durationMS int64

	if err == nil {
		if metaMap, ok := metaVal.Value().(map[string]dbus.Variant); ok {
			if t, ok := metaMap["xesam:title"].Value().(string); ok {
				title = t
			}
			if a, ok := metaMap["xesam:artist"].Value().([]string); ok && len(a) > 0 {
				artist = strings.Join(a, ", ")
			} else if aStr, ok := metaMap["xesam:artist"].Value().(string); ok {
				artist = aStr
			}
			if al, ok := metaMap["xesam:album"].Value().(string); ok {
				album = al
			}
			if art, ok := metaMap["mpris:artUrl"].Value().(string); ok {
				artURL = art
			}
			if length, ok := metaMap["mpris:length"].Value().(int64); ok {
				durationMS = length / 1000 // Microseconds to milliseconds
			}
		}
	}

	// Get Position
	posVal, err := obj.GetProperty("org.mpris.MediaPlayer2.Player.Position")
	var posMS int64
	if err == nil {
		if p, ok := posVal.Value().(int64); ok {
			posMS = p / 1000
		}
	}

	// Get Volume
	volVal, err := obj.GetProperty("org.mpris.MediaPlayer2.Player.Volume")
	var volume float64 = 1.0
	if err == nil {
		if v, ok := volVal.Value().(float64); ok {
			volume = v
		}
	}

	cleanPlayerName := strings.TrimPrefix(playerName, "org.mpris.MediaPlayer2.")

	newState := protocol.MediaStatePayload{
		PlayerName:     cleanPlayerName,
		PlaybackStatus: playbackStatus,
		Title:          title,
		Artist:         artist,
		Album:          album,
		ArtURL:         artURL,
		PositionMS:     posMS,
		DurationMS:     durationMS,
		Volume:         volume,
		CanPlay:        true,
		CanPause:       true,
		CanGoNext:      true,
		CanGoPrev:      true,
	}

	m.mu.Lock()
	changed := (m.currState.Title != newState.Title ||
		m.currState.Artist != newState.Artist ||
		m.currState.PlaybackStatus != newState.PlaybackStatus ||
		m.currState.PlayerName != newState.PlayerName)
	m.currState = newState
	m.mu.Unlock()

	if changed {
		log.Printf("[MPRIS] State updated: [%s] %s - %s (%s)", cleanPlayerName, title, artist, playbackStatus)
		select {
		case m.stateCh <- newState:
		default:
		}
	}
}

// SendCommand executes a playback action over MPRIS DBus
func (m *MPRISMonitor) SendCommand(cmd protocol.MediaCommandPayload) error {
	playerName := m.FindFirstActivePlayer()
	if playerName == "" {
		return fmt.Errorf("no active MPRIS media player found")
	}

	obj := m.conn.Object(playerName, "/org/mpris/MediaPlayer2")

	switch cmd.Action {
	case "Play":
		return obj.Call("org.mpris.MediaPlayer2.Player.Play", 0).Err
	case "Pause":
		return obj.Call("org.mpris.MediaPlayer2.Player.Pause", 0).Err
	case "PlayPause":
		return obj.Call("org.mpris.MediaPlayer2.Player.PlayPause", 0).Err
	case "Next":
		return obj.Call("org.mpris.MediaPlayer2.Player.Next", 0).Err
	case "Previous":
		return obj.Call("org.mpris.MediaPlayer2.Player.Previous", 0).Err
	case "Stop":
		return obj.Call("org.mpris.MediaPlayer2.Player.Stop", 0).Err
	case "SetVolume":
		return obj.SetProperty("org.mpris.MediaPlayer2.Player.Volume", dbus.MakeVariant(cmd.Value))
	default:
		return fmt.Errorf("unknown action: %s", cmd.Action)
	}
}

func (m *MPRISMonitor) Close() {
	m.cancel()
	if m.conn != nil {
		m.conn.Close()
	}
}
