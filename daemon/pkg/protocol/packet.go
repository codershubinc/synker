package protocol

import "time"

// Standard packet types across the Quazaar P2P bridge
const (
	TypePing             = "quazaar.ping"
	TypePong             = "quazaar.pong"
	TypePairRequest      = "quazaar.pair.request"
	TypePairResponse     = "quazaar.pair.response"
	TypeMediaState       = "quazaar.media.state"
	TypeMediaCommand     = "quazaar.media.command"
	TypeNotificationPush = "quazaar.notification.push"
)

// Packet represents the standard JSON frame for all peer communications.
type Packet struct {
	ID        string      `json:"id"`
	Type      string      `json:"type"`
	Timestamp int64       `json:"timestamp"`
	SenderID  string      `json:"sender_id"`
	Payload   interface{} `json:"payload"`
}

func NewPacket(id, pType, senderID string, payload interface{}) Packet {
	return Packet{
		ID:        id,
		Type:      pType,
		Timestamp: time.Now().UnixMilli(),
		SenderID:  senderID,
		Payload:   payload,
	}
}

// MediaStatePayload models current playback telemetry
type MediaStatePayload struct {
	PlayerName   string   `json:"player_name"`
	PlaybackStatus string `json:"playback_status"` // "Playing", "Paused", "Stopped"
	Title        string   `json:"title"`
	Artist       string   `json:"artist"`
	Album        string   `json:"album"`
	ArtURL       string   `json:"art_url,omitempty"`
	PositionMS   int64    `json:"position_ms"`
	DurationMS   int64    `json:"duration_ms"`
	Volume       float64  `json:"volume"`
	CanPlay      bool     `json:"can_play"`
	CanPause     bool     `json:"can_pause"`
	CanGoNext    bool     `json:"can_go_next"`
	CanGoPrev    bool     `json:"can_go_previous"`
}

// MediaCommandPayload models remote playback action instructions
type MediaCommandPayload struct {
	Action string  `json:"action"` // "Play", "Pause", "PlayPause", "Next", "Previous", "Stop", "SetVolume"
	Value  float64 `json:"value,omitempty"`
}

// PairRequestPayload carries pairing negotiation details
type PairRequestPayload struct {
	DeviceID   string `json:"device_id"`
	DeviceName string `json:"device_name"`
	DeviceType string `json:"device_type"` // "desktop", "mobile", "tablet"
	PublicKey  string `json:"public_key"`
}

// PairResponsePayload conveys acceptance or rejection of a pairing request
type PairResponsePayload struct {
	Accepted bool   `json:"accepted"`
	Reason   string `json:"reason,omitempty"`
}

// NotificationPayload models push notifications forwarded between devices
type NotificationPayload struct {
	ID       string `json:"id"`
	AppName  string `json:"app_name"`
	Title    string `json:"title"`
	Body     string `json:"body"`
	IconURL  string `json:"icon_url,omitempty"`
	IsSilent bool   `json:"is_silent"`
}
