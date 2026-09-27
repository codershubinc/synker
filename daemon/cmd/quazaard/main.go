package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/quazaar/synker/daemon/pkg/discovery"
	"github.com/quazaar/synker/daemon/pkg/mpris"
	"github.com/quazaar/synker/daemon/pkg/tracker"
	"github.com/quazaar/synker/daemon/pkg/webui"
)

func main() {
	port := flag.Int("port", 4242, "Port for daemon Web UI & REST API")
	name := flag.String("name", "", "Device name (defaults to hostname)")
	configDir := flag.String("config", "", "Config directory (defaults to ~/.config/quazaar)")
	flag.Parse()

	if *configDir == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			log.Fatalf("Failed to get user home directory: %v", err)
		}
		*configDir = filepath.Join(home, ".config", "quazaar")
	}

	hostName, _ := os.Hostname()
	if *name == "" {
		*name = hostName
	}

	log.Printf("==============================================")
	log.Printf("  Quazaar Music Synker Daemon (Apple Music)   ")
	log.Printf("  Version: v0.0.1                             ")
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

	log.Printf("[Quazaar] Daemon running on port :%d. Press Ctrl+C to terminate.", *port)

	// Wait for termination signal
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	<-sigCh

	log.Printf("[Quazaar] Shutting down daemon...")
	_ = httpServer.Close()
}
