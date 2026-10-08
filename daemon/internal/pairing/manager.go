package pairing

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type PairedDevice struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Type      string    `json:"type"`
	PublicKey string    `json:"public_key"`
	PairedAt  time.Time `json:"paired_at"`
}

type DeviceConfig struct {
	DeviceID      string                  `json:"device_id"`
	DeviceName    string                  `json:"device_name"`
	PairedDevices map[string]PairedDevice `json:"paired_devices"`
}

type SecurityManager struct {
	configDir  string
	configPath string
	certPath   string
	keyPath    string
	cfg        DeviceConfig
	tlsConfig  *tls.Config
	mu         sync.RWMutex
}

func NewSecurityManager(configDir string, deviceName string) (*SecurityManager, error) {
	if err := os.MkdirAll(configDir, 0700); err != nil {
		return nil, fmt.Errorf("failed to create config dir: %w", err)
	}

	sm := &SecurityManager{
		configDir:  configDir,
		configPath: filepath.Join(configDir, "config.json"),
		certPath:   filepath.Join(configDir, "cert.pem"),
		keyPath:    filepath.Join(configDir, "key.pem"),
		cfg: DeviceConfig{
			PairedDevices: make(map[string]PairedDevice),
		},
	}

	if err := sm.loadOrCreateConfig(deviceName); err != nil {
		return nil, err
	}

	if err := sm.ensureCertificate(); err != nil {
		return nil, err
	}

	return sm, nil
}

func (sm *SecurityManager) GetDeviceID() string {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.cfg.DeviceID
}

func (sm *SecurityManager) GetDeviceName() string {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.cfg.DeviceName
}

func (sm *SecurityManager) IsPaired(deviceID string) bool {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	_, exists := sm.cfg.PairedDevices[deviceID]
	return exists
}

func (sm *SecurityManager) AddPairedDevice(dev PairedDevice) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	dev.PairedAt = time.Now()
	sm.cfg.PairedDevices[dev.ID] = dev
	return sm.saveConfigLocked()
}

func (sm *SecurityManager) RemovePairedDevice(deviceID string) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	delete(sm.cfg.PairedDevices, deviceID)
	return sm.saveConfigLocked()
}

func (sm *SecurityManager) GetPairedDevices() []PairedDevice {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	res := make([]PairedDevice, 0, len(sm.cfg.PairedDevices))
	for _, dev := range sm.cfg.PairedDevices {
		res = append(res, dev)
	}
	return res
}

func (sm *SecurityManager) GetTLSConfig() *tls.Config {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.tlsConfig
}

func (sm *SecurityManager) loadOrCreateConfig(defaultName string) error {
	data, err := os.ReadFile(sm.configPath)
	if err == nil {
		if err := json.Unmarshal(data, &sm.cfg); err == nil {
			if sm.cfg.PairedDevices == nil {
				sm.cfg.PairedDevices = make(map[string]PairedDevice)
			}
			return nil
		}
	}

	// Create fresh config
	devID := fmt.Sprintf("quazaar-node-%x", time.Now().UnixNano())
	if defaultName == "" {
		host, _ := os.Hostname()
		defaultName = host
	}
	sm.cfg = DeviceConfig{
		DeviceID:      devID,
		DeviceName:    defaultName,
		PairedDevices: make(map[string]PairedDevice),
	}
	return sm.saveConfigLocked()
}

func (sm *SecurityManager) saveConfigLocked() error {
	data, err := json.MarshalIndent(sm.cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(sm.configPath, data, 0600)
}

func (sm *SecurityManager) ensureCertificate() error {
	certExists := fileExists(sm.certPath)
	keyExists := fileExists(sm.keyPath)

	if !certExists || !keyExists {
		if err := generateSelfSignedCert(sm.certPath, sm.keyPath, sm.cfg.DeviceID); err != nil {
			return fmt.Errorf("failed to generate TLS certificate: %w", err)
		}
	}

	cert, err := tls.LoadX509KeyPair(sm.certPath, sm.keyPath)
	if err != nil {
		return fmt.Errorf("failed to load TLS keypair: %w", err)
	}

	sm.tlsConfig = &tls.Config{
		Certificates: []tls.Certificate{cert},
		// For P2P zero-trust exchange, we do peer verification at application/protocol level
		InsecureSkipVerify: true,
	}

	return nil
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func generateSelfSignedCert(certPath, keyPath, commonName string) error {
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return err
	}

	template := x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject: pkix.Name{
			Organization: []string{"Quazaar P2P Bridge"},
			CommonName:   commonName,
		},
		NotBefore:             time.Now().Add(-1 * time.Hour),
		NotAfter:              time.Now().Add(10 * 365 * 24 * time.Hour), // 10 years
		KeyUsage:              x509.KeyUsageKeyEncipherment | x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth},
		BasicConstraintsValid: true,
		IPAddresses:           []net.IP{net.ParseIP("127.0.0.1")},
	}

	derBytes, err := x509.CreateCertificate(rand.Reader, &template, &template, &priv.PublicKey, priv)
	if err != nil {
		return err
	}

	certOut, err := os.Create(certPath)
	if err != nil {
		return err
	}
	defer certOut.Close()
	if err := pem.Encode(certOut, &pem.Block{Type: "CERTIFICATE", Bytes: derBytes}); err != nil {
		return err
	}

	keyOut, err := os.OpenFile(keyPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0600)
	if err != nil {
		return err
	}
	defer keyOut.Close()
	privBytes := x509.MarshalPKCS1PrivateKey(priv)
	return pem.Encode(keyOut, &pem.Block{Type: "RSA PRIVATE KEY", Bytes: privBytes})
}
