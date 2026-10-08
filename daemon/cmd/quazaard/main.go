package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/quazaar/synker/daemon/pkg/discovery"
	"github.com/quazaar/synker/daemon/pkg/mpris"
	"github.com/quazaar/synker/daemon/pkg/tracker"
	"github.com/quazaar/synker/daemon/pkg/webui"
)

var (
	version   = "0.0.1-dev"
	commit    = "none"
	buildDate = "unknown"
)

func printVersion() {
	fmt.Printf("synkerd %s (commit: %s, built: %s)\n", version, commit, buildDate)
}

func printHelp() {
	fmt.Printf("Synker Music Daemon (Apple Music & MPRIS Sync) - %s\n\n", version)
	fmt.Println(`Usage:
  synkerd [command]
  synkerd [flags]

Service Commands:
  status, --status, -s       Check systemd service status
  logs, --logs, -l           Follow live service logs (journalctl)
  restart, --restart         Restart the daemon service
  start, --start             Start the daemon service
  stop, --stop               Stop the daemon service
  uninstall, --uninstall     Stop and remove systemd service from auto-start
  version, --version, -v     Print version and build information

Daemon Execution Flags:
  -port <int>                Web dashboard & REST API port (default: 4242)
  -name <string>             Device name identifier (defaults to hostname)
  -config <dir>              Storage directory (defaults to ~/.config/synker)
  -help, -h                  Show this help menu`)
}

func handleServiceCommand(arg string) bool {
	serviceName := "synkerd.service"
	switch arg {
	case "version", "--version", "-v":
		printVersion()
		return true
	case "status", "--status", "-s":
		cmd := exec.Command("systemctl", "--user", "status", serviceName, "--no-pager")
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		_ = cmd.Run()
		return true

	case "logs", "--logs", "-l":
		fmt.Printf("[*] Streaming live logs for %s (Ctrl+C to exit):\n", serviceName)
		cmd := exec.Command("journalctl", "--user", "-u", serviceName, "-f")
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		cmd.Stdin = os.Stdin
		_ = cmd.Run()
		return true

	case "restart", "--restart":
		fmt.Printf("[*] Restarting %s...\n", serviceName)
		cmd := exec.Command("systemctl", "--user", "restart", serviceName)
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Run(); err != nil {
			fmt.Printf("[Error] Failed to restart %s: %v\n", serviceName, err)
		} else {
			fmt.Println("[✓] Service restarted successfully.")
		}
		return true

	case "start", "--start":
		fmt.Printf("[*] Starting %s...\n", serviceName)
		cmd := exec.Command("systemctl", "--user", "start", serviceName)
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Run(); err != nil {
			fmt.Printf("[Error] Failed to start %s: %v\n", serviceName, err)
		} else {
			fmt.Println("[✓] Service started successfully.")
		}
		return true

	case "stop", "--stop":
		fmt.Printf("[*] Stopping %s...\n", serviceName)
		cmd := exec.Command("systemctl", "--user", "stop", serviceName)
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Run(); err != nil {
			fmt.Printf("[Error] Failed to stop %s: %v\n", serviceName, err)
		} else {
			fmt.Println("[✓] Service stopped.")
		}
		return true

	case "uninstall", "--uninstall":
		fmt.Printf("[!] Stopping and removing %s...\n", serviceName)
		_ = exec.Command("systemctl", "--user", "stop", serviceName).Run()
		_ = exec.Command("systemctl", "--user", "disable", serviceName).Run()
		home, _ := os.UserHomeDir()
		if home != "" {
			serviceFile := filepath.Join(home, ".config", "systemd", "user", serviceName)
			_ = os.Remove(serviceFile)
		}
		_ = exec.Command("systemctl", "--user", "daemon-reload").Run()
		fmt.Println("[✓] synkerd service uninstalled from auto-start.")
		return true

	case "help", "--help", "-h":
		printHelp()
		return true
	}
	return false
}

func main() {
	// Check if a service command was passed directly
	if len(os.Args) > 1 {
		if handleServiceCommand(os.Args[1]) {
			return
		}
	}

	port := flag.Int("port", 4242, "Port for daemon Web UI & REST API")
	name := flag.String("name", "", "Device name (defaults to hostname)")
	configDir := flag.String("config", "", "Config directory (defaults to ~/.config/synker)")
	flag.Usage = printHelp
	flag.Parse()

	if *configDir == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			log.Fatalf("Failed to get user home directory: %v", err)
		}
		synkerDir := filepath.Join(home, ".config", "synker")
		legacyDir := filepath.Join(home, ".config", "quazaar")
		if _, err := os.Stat(synkerDir); err == nil {
			*configDir = synkerDir
		} else if _, err := os.Stat(legacyDir); err == nil {
			*configDir = legacyDir
		} else {
			*configDir = synkerDir
		}
	}

	hostName, _ := os.Hostname()
	if *name == "" {
		*name = hostName
	}

	log.Printf("==============================================")
	log.Printf("  Synker Music Daemon (Apple Music Sync)      ")
	log.Printf("  Version: %s (%s)               ", version, commit)
	log.Printf("==============================================")
	log.Printf("[Init] Device Name: %s", *name)

	// 1. Initialize SQLite Music Tracker
	musicTracker, err := tracker.NewMusicTracker(*configDir)
	if err != nil {
		log.Fatalf("Failed to initialize music tracker: %v", err)
	}
	defer musicTracker.Close()

	// 2. Initialize and start Discovery Service (zeroconf mDNS advertising _quazaar._tcp)
	discoveryService := discovery.NewDiscoveryService(hostName, *name, *port)
	if err := discoveryService.StartBroadcasting(); err != nil {
		log.Printf("[Warning] Failed to start discovery broadcast: %v", err)
	} else {
		defer discoveryService.Stop()
		log.Printf("[Init] LAN Discovery advertising on _quazaar._tcp port %d", *port)
	}

	// 3. Initialize MPRIS DBus Monitor (syncs directly with KDE Connect / Linux MPRIS)
	mprisMon, err := mpris.NewMPRISMonitor()
	if err != nil {
		log.Printf("[Warning] Failed to connect to DBus MPRIS: %v. Media monitoring disabled.", err)
	} else {
		defer mprisMon.Close()
		mprisMon.StartMonitoring()
		log.Printf("[Init] MPRIS DBus monitor active (listening to KDE Connect & media players)")
	}

	// 5. Start HTTP Web UI & REST API
	webServer := webui.NewWebServer(*port, hostName, *name, mprisMon, musicTracker)

	// 4. Pipe MPRIS updates, notify WebSockets & record Apple Music plays
	if mprisMon != nil {
		go func() {
			for state := range mprisMon.StateChannel() {
				if state.PlaybackStatus == "Playing" || state.Title != "" {
					artists := tracker.SplitArtists(state.Artist)
					webServer.SetLiveMediaState(webui.LiveMediaState{
						DeviceID:       hostName,
						DeviceName:     *name,
						Title:          state.Title,
						Artist:         state.Artist,
						Artists:        artists,
						Album:          state.Album,
						PlaybackStatus: state.PlaybackStatus,
						CurrentSeconds: state.PositionMS / 1000,
						DurationMS:     state.DurationMS,
						ArtworkURL:     state.ArtURL,
						Source:         state.PlayerName,
					})

					if musicTracker != nil && state.PlaybackStatus == "Playing" && state.Title != "" {
						musicTracker.RecordPlay(
							state.PlayerName,
							state.Title,
							artists,
							state.Album,
							hostName,
							*name,
							state.DurationMS,
						)
					}
				}
			}
		}()
	}

	mux := webServer.Handler()
	httpServer := &http.Server{
		Addr:    fmt.Sprintf(":%d", *port),
		Handler: mux,
	}

	go func() {
		log.Printf("[WebUI] Dashboard available at http://localhost:%d", *port)
		if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Printf("[WebUI] Server error: %v", err)
		}
	}()

	log.Printf("[Synker] Daemon running on port :%d. Press Ctrl+C to terminate.", *port)

	// Wait for termination signal
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	<-sigCh

	log.Printf("[Synker] Shutting down daemon...")
	_ = httpServer.Close()
}
