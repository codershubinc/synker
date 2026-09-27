package discovery

import (
	"context"
	"fmt"
	"log"
	"net"
	"sync"
	"time"

	"github.com/grandcat/zeroconf"
)

const (
	ServiceType   = "_quazaar._tcp"
	ServiceDomain = "local."
)

type PeerInfo struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	IP        net.IP    `json:"ip"`
	Port      int       `json:"port"`
	LastSeen  time.Time `json:"last_seen"`
}

type DiscoveryService struct {
	deviceID     string
	deviceName   string
	port         int
	server       *zeroconf.Server
	peers        map[string]PeerInfo
	peerCh       chan PeerInfo
	mu           sync.RWMutex
	ctx          context.Context
	cancel       context.CancelFunc
}

func NewDiscoveryService(deviceID, deviceName string, port int) *DiscoveryService {
	ctx, cancel := context.WithCancel(context.Background())
	return &DiscoveryService{
		deviceID:   deviceID,
		deviceName: deviceName,
		port:       port,
		peers:      make(map[string]PeerInfo),
		peerCh:     make(chan PeerInfo, 16),
		ctx:        ctx,
		cancel:     cancel,
	}
}

// PeerChannel returns a channel that yields newly discovered or updated peers
func (d *DiscoveryService) PeerChannel() <-chan PeerInfo {
	return d.peerCh
}

// StartBroadcasting advertises this node over mDNS
func (d *DiscoveryService) StartBroadcasting() error {
	txtRecords := []string{
		fmt.Sprintf("id=%s", d.deviceID),
		fmt.Sprintf("name=%s", d.deviceName),
		"version=1.0",
		"type=desktop",
	}

	server, err := zeroconf.Register(
		d.deviceID,
		ServiceType,
		ServiceDomain,
		d.port,
		txtRecords,
		nil,
	)
	if err != nil {
		return fmt.Errorf("failed to register zeroconf service: %w", err)
	}

	d.server = server
	log.Printf("[Discovery] mDNS broadcast registered for device %s (%s) on port %d", d.deviceName, d.deviceID, d.port)
	return nil
}

// StartBrowsing continuously scans the local network for peers
func (d *DiscoveryService) StartBrowsing() {
	resolver, err := zeroconf.NewResolver(nil)
	if err != nil {
		log.Printf("[Discovery] Failed to create resolver: %v", err)
		return
	}

	entries := make(chan *zeroconf.ServiceEntry)
	go func() {
		for entry := range entries {
			// Ignore self
			if entry.Instance == d.deviceID {
				continue
			}

			var devID, devName string
			for _, txt := range entry.Text {
				var k, v string
				n, _ := fmt.Sscanf(txt, "%s=%s", &k, &v)
				if n == 2 {
					if k == "id" {
						devID = v
					} else if k == "name" {
						devName = v
					}
				}
			}

			if devID == "" {
				devID = entry.Instance
			}
			if devName == "" {
				devName = entry.Instance
			}

			var targetIP net.IP
			if len(entry.AddrIPv4) > 0 {
				targetIP = entry.AddrIPv4[0]
			} else if len(entry.AddrIPv6) > 0 {
				targetIP = entry.AddrIPv6[0]
			}

			if targetIP == nil {
				continue
			}

			peer := PeerInfo{
				ID:       devID,
				Name:     devName,
				IP:       targetIP,
				Port:     entry.Port,
				LastSeen: time.Now(),
			}

			d.mu.Lock()
			d.peers[devID] = peer
			d.mu.Unlock()

			select {
			case d.peerCh <- peer:
			default:
			}
		}
	}()

	go func() {
		ticker := time.NewTicker(15 * time.Second)
		defer ticker.Stop()

		// Initial scan
		if err := resolver.Browse(d.ctx, ServiceType, ServiceDomain, entries); err != nil {
			log.Printf("[Discovery] Browse error: %v", err)
		}

		for {
			select {
			case <-d.ctx.Done():
				return
			case <-ticker.C:
				_ = resolver.Browse(d.ctx, ServiceType, ServiceDomain, entries)
			}
		}
	}()
}

func (d *DiscoveryService) GetPeers() []PeerInfo {
	d.mu.RLock()
	defer d.mu.RUnlock()
	res := make([]PeerInfo, 0, len(d.peers))
	for _, p := range d.peers {
		res = append(res, p)
	}
	return res
}

func (d *DiscoveryService) Stop() {
	d.cancel()
	if d.server != nil {
		d.server.Shutdown()
	}
}
