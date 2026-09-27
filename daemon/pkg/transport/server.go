package transport

import (
	"bufio"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"sync"
	"time"

	"github.com/quazaar/synker/daemon/pkg/pairing"
	"github.com/quazaar/synker/daemon/pkg/protocol"
)

type PeerConnection struct {
	DeviceID   string
	Conn       net.Conn
	Writer     *bufio.Writer
	mu         sync.Mutex
	lastActive time.Time
}

func (p *PeerConnection) SendPacket(pkt protocol.Packet) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	data, err := json.Marshal(pkt)
	if err != nil {
		return err
	}
	data = append(data, '\n')

	p.Conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := p.Writer.Write(data); err != nil {
		return err
	}
	return p.Writer.Flush()
}

type Server struct {
	port         int
	secMgr       *pairing.SecurityManager
	activePeers  map[string]*PeerConnection
	mu           sync.RWMutex
	onPacket     func(peer *PeerConnection, pkt protocol.Packet)
	onConnect    func(peer *PeerConnection)
	onDisconnect func(deviceID string)
}

func NewServer(port int, secMgr *pairing.SecurityManager) *Server {
	return &Server{
		port:        port,
		secMgr:      secMgr,
		activePeers: make(map[string]*PeerConnection),
	}
}

func (s *Server) OnPacket(handler func(peer *PeerConnection, pkt protocol.Packet)) {
	s.onPacket = handler
}

func (s *Server) OnConnect(handler func(peer *PeerConnection)) {
	s.onConnect = handler
}

func (s *Server) OnDisconnect(handler func(deviceID string)) {
	s.onDisconnect = handler
}

func (s *Server) Start() error {
	tlsConf := s.secMgr.GetTLSConfig()
	listener, err := tls.Listen("tcp", fmt.Sprintf(":%d", s.port), tlsConf)
	if err != nil {
		return fmt.Errorf("failed to start TLS listener: %w", err)
	}

	log.Printf("[Transport] TLS Server listening on :%d", s.port)
	go s.ServeListener(listener)
	return nil
}

func (s *Server) ServeListener(listener net.Listener) {
	// Heartbeat cleaner
	go s.heartbeatLoop()

	for {
		conn, err := listener.Accept()
		if err != nil {
			return
		}

		go s.handleConnection(conn)
	}
}

func (s *Server) handleConnection(conn net.Conn) {
	reader := bufio.NewReader(conn)
	writer := bufio.NewWriter(conn)
	peer := &PeerConnection{
		Conn:       conn,
		Writer:     writer,
		lastActive: time.Now(),
	}

	defer func() {
		conn.Close()
		if peer.DeviceID != "" {
			s.mu.Lock()
			delete(s.activePeers, peer.DeviceID)
			s.mu.Unlock()
			log.Printf("[Transport] Peer disconnected: %s", peer.DeviceID)
			if s.onDisconnect != nil {
				s.onDisconnect(peer.DeviceID)
			}
		}
	}()

	for {
		conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		line, err := reader.ReadBytes('\n')
		if err != nil {
			return
		}

		peer.lastActive = time.Now()

		var pkt protocol.Packet
		if err := json.Unmarshal(line, &pkt); err != nil {
			log.Printf("[Transport] Failed to decode packet: %v", err)
			continue
		}

		// Handle pairing or check if peer is authenticated
		if pkt.Type == protocol.TypePairRequest {
			s.handlePairRequest(peer, pkt)
			continue
		}

		// Security: verify if peer is registered/paired
		if !s.secMgr.IsPaired(pkt.SenderID) {
			log.Printf("[Transport] Rejecting packet from un-paired device: %s", pkt.SenderID)
			resp := protocol.NewPacket("err", protocol.TypePairResponse, s.secMgr.GetDeviceID(), protocol.PairResponsePayload{
				Accepted: false,
				Reason:   "Device not paired",
			})
			_ = peer.SendPacket(resp)
			return
		}

		if peer.DeviceID == "" {
			peer.DeviceID = pkt.SenderID
			s.mu.Lock()
			s.activePeers[pkt.SenderID] = peer
			s.mu.Unlock()
			log.Printf("[Transport] Authenticated peer connection: %s", peer.DeviceID)
			if s.onConnect != nil {
				s.onConnect(peer)
			}
		}

		// Internal ping / pong
		if pkt.Type == protocol.TypePing {
			pong := protocol.NewPacket("pong", protocol.TypePong, s.secMgr.GetDeviceID(), nil)
			_ = peer.SendPacket(pong)
			continue
		}

		if s.onPacket != nil {
			s.onPacket(peer, pkt)
		}
	}
}

func (s *Server) handlePairRequest(peer *PeerConnection, pkt protocol.Packet) {
	var req protocol.PairRequestPayload
	data, _ := json.Marshal(pkt.Payload)
	if err := json.Unmarshal(data, &req); err != nil {
		log.Printf("[Pairing] Invalid pair payload: %v", err)
		return
	}

	log.Printf("[Pairing] Received pair request from %s (%s, %s)", req.DeviceName, req.DeviceID, req.DeviceType)

	// In this daemon MVP, auto-accept or store pairing
	err := s.secMgr.AddPairedDevice(pairing.PairedDevice{
		ID:        req.DeviceID,
		Name:      req.DeviceName,
		Type:      req.DeviceType,
		PublicKey: req.PublicKey,
	})

	accepted := err == nil
	respPayload := protocol.PairResponsePayload{
		Accepted: accepted,
	}
	if !accepted {
		respPayload.Reason = err.Error()
	}

	resp := protocol.NewPacket("pair-ack", protocol.TypePairResponse, s.secMgr.GetDeviceID(), respPayload)
	_ = peer.SendPacket(resp)

	if accepted {
		peer.DeviceID = req.DeviceID
		s.mu.Lock()
		s.activePeers[req.DeviceID] = peer
		s.mu.Unlock()
		log.Printf("[Pairing] Successfully paired and authenticated: %s", req.DeviceID)
		if s.onConnect != nil {
			s.onConnect(peer)
		}
	}
}

func (s *Server) Broadcast(pkt protocol.Packet) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for id, peer := range s.activePeers {
		if err := peer.SendPacket(pkt); err != nil {
			log.Printf("[Transport] Failed to broadcast to %s: %v", id, err)
		}
	}
}

func (s *Server) ConnectToPeer(ip net.IP, port int) (*PeerConnection, error) {
	tlsConf := s.secMgr.GetTLSConfig()
	conn, err := tls.Dial("tcp", fmt.Sprintf("%s:%d", ip.String(), port), tlsConf)
	if err != nil {
		return nil, err
	}

	reader := bufio.NewReader(conn)
	writer := bufio.NewWriter(conn)
	peer := &PeerConnection{
		Conn:       conn,
		Writer:     writer,
		lastActive: time.Now(),
	}

	go func() {
		defer conn.Close()
		for {
			line, err := reader.ReadBytes('\n')
			if err != nil {
				return
			}
			var pkt protocol.Packet
			if err := json.Unmarshal(line, &pkt); err == nil && s.onPacket != nil {
				s.onPacket(peer, pkt)
			}
		}
	}()

	return peer, nil
}

func (s *Server) GetActivePeerIDs() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	res := make([]string, 0, len(s.activePeers))
	for id := range s.activePeers {
		res = append(res, id)
	}
	return res
}

func (s *Server) heartbeatLoop() {
	ticker := time.NewTicker(20 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		s.mu.RLock()
		for _, peer := range s.activePeers {
			ping := protocol.NewPacket("ping", protocol.TypePing, s.secMgr.GetDeviceID(), nil)
			_ = peer.SendPacket(ping)
		}
		s.mu.RUnlock()
	}
}
