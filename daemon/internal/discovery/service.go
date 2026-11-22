package discovery

import (
	"context"
	"fmt"
	"log"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/grandcat/zeroconf"
)

const (
	ServiceType   = "_quazaar._tcp"
	ServiceDomain = "local."
)

type PeerInfo struct {
	ID       string    `json:"id"`
	Name     string    `json:"name"`
	IP       net.IP    `json:"ip"`
	Port     int       `json:"port"`
	LastSeen time.Time `json:"last_seen"`
}

type DiscoveryService struct {
	deviceID   string
	deviceName string
	port       int
	server     *zeroconf.Server
	peers      map[string]PeerInfo
	peerCh     chan PeerInfo
	mu         sync.RWMutex
	ctx        context.Context
	cancel     context.CancelFunc
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

// isVirtualOrContainerInterface checks for Docker, bridge, and virtual tunnel interfaces
func isVirtualOrContainerInterface(name string) bool {
	lower := strings.ToLower(name)
	virtualPrefixes := []string{
		"docker", "br-", "veth", "virbr", "vmnet", "vboxnet",
		"tun", "tap", "tailscale", "wg", "dummy", "flannel", "cni", "kube",
	}
	for _, prefix := range virtualPrefixes {
		if strings.HasPrefix(lower, prefix) {
			return true
		}
	}
	return false
}

// getFilteredLANInterfaces returns only active, physical/LAN interfaces and their IPv4 addresses,
// filtering out bridge, container, loopback, and unroutable interfaces.
func getFilteredLANInterfaces() ([]net.Interface, []string) {
	var validIfaces []net.Interface
	var validIPs []string

	ifaces, err := net.Interfaces()
	if err != nil {
		return nil, nil
	}

	for _, iface := range ifaces {
		// Must be UP, have MULTICAST, and not be LOOPBACK
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 || iface.Flags&net.FlagMulticast == 0 {
			continue
		}
		if isVirtualOrContainerInterface(iface.Name) {
			continue
		}

		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}

		hasValidIPv4 := false
		for _, addr := range addrs {
			ipNet, ok := addr.(*net.IPNet)
			if !ok || ipNet.IP == nil {
				continue
			}
			ipv4 := ipNet.IP.To4()
			if ipv4 == nil || ipv4.IsLoopback() || ipv4.IsLinkLocalUnicast() {
				continue
			}
			// Skip typical docker default subnets (172.17.x.x, 172.18.x.x) on host virtual bridges
			if ipv4[0] == 172 && (ipv4[1] == 17 || ipv4[1] == 18) {
				continue
			}
			validIPs = append(validIPs, ipv4.String())
			hasValidIPv4 = true
		}

		if hasValidIPv4 {
			validIfaces = append(validIfaces, iface)
		}
	}

	return validIfaces, validIPs
}

// StartBroadcasting advertises this node over mDNS
func (d *DiscoveryService) StartBroadcasting() error {
	validIfaces, validIPs := getFilteredLANInterfaces()
	primaryIP := ""
	if len(validIPs) > 0 {
		primaryIP = validIPs[0]
	}

	txtRecords := []string{
		fmt.Sprintf("id=%s", d.deviceID),
		fmt.Sprintf("name=%s", d.deviceName),
		"version=1.0",
		"type=desktop",
	}
	if primaryIP != "" {
		txtRecords = append(txtRecords, fmt.Sprintf("lan_ip=%s", primaryIP))
		txtRecords = append(txtRecords, fmt.Sprintf("ips=%s", strings.Join(validIPs, ",")))
	}

	var ifaceSlice []net.Interface
	if len(validIfaces) > 0 {
		ifaceSlice = validIfaces
	}

	server, err := zeroconf.Register(
		d.deviceID,
		ServiceType,
		ServiceDomain,
		d.port,
		txtRecords,
		ifaceSlice,
	)
	if err != nil {
		return fmt.Errorf("failed to register zeroconf service: %w", err)
	}

	d.server = server
	log.Printf("[Discovery] mDNS broadcast registered for device %s (%s) on port %d (LAN IPs: %v, ifaces: %d)",
		d.deviceName, d.deviceID, d.port, validIPs, len(validIfaces))
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

			var devID, devName, txtLanIP string
			for _, txt := range entry.Text {
				var k, v string
				n, _ := fmt.Sscanf(txt, "%s=%s", &k, &v)
				if n == 2 {
					if k == "id" {
						devID = v
					} else if k == "name" {
						devName = v
					} else if k == "lan_ip" {
						txtLanIP = v
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
			if txtLanIP != "" {
				if parsed := net.ParseIP(txtLanIP); parsed != nil {
					targetIP = parsed
				}
			}
			if targetIP == nil {
				for _, ip := range entry.AddrIPv4 {
					ipv4 := ip.To4()
					if ipv4 != nil && !ipv4.IsLoopback() && !(ipv4[0] == 172 && (ipv4[1] == 17 || ipv4[1] == 18)) {
						targetIP = ip
						break
					}
				}
			}
			if targetIP == nil && len(entry.AddrIPv4) > 0 {
				targetIP = entry.AddrIPv4[0]
			} else if targetIP == nil && len(entry.AddrIPv6) > 0 {
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
