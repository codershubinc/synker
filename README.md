# Quazaar Synker Daemon

Lightweight, decentralized, peer-to-peer background daemon for Linux/PC that bridges media telemetry and notifications across local devices without cloud dependencies.

## Architecture

The daemon is implemented under `daemon/` and structured as follows:

* [`daemon/pkg/protocol/`](file:///home/swap/Github/Quazaar-synker/daemon/pkg/protocol/packet.go): Standard JSON packet specifications (`quazaar.media.state`, `quazaar.media.command`, `quazaar.pair.request`, `quazaar.notification.push`, ping/pong heartbeats).
* [`daemon/pkg/discovery/`](file:///home/swap/Github/Quazaar-synker/daemon/pkg/discovery/service.go): Zero-configuration local network mDNS discovery (`_quazaar._tcp`) powered by Zeroconf.
* [`daemon/pkg/pairing/`](file:///home/swap/Github/Quazaar-synker/daemon/pkg/pairing/manager.go): TLS certificate generation and local device pair authorization store.
* [`daemon/pkg/mpris/`](file:///home/swap/Github/Quazaar-synker/daemon/pkg/mpris/mpris.go): Native Linux DBus MPRIS telemetry hook listening to player property changes and dispatching remote playback actions (`Play`, `Pause`, `Next`, etc.).
* [`daemon/pkg/transport/`](file:///home/swap/Github/Quazaar-synker/daemon/pkg/transport/server.go): Encrypted TLS server handling streaming JSON packets, pairing handshakes, and peer heartbeats.
* [`daemon/cmd/quazaard/`](file:///home/swap/Github/Quazaar-synker/daemon/cmd/quazaard/main.go): Daemon entry point.

---

## Building & Running

### Build the binary:
```bash
cd daemon
go build -o bin/quazaard ./cmd/quazaard
```

### Run the daemon:
```bash
./daemon/bin/quazaard -port 4242
```

Flags:
- `-port`: Port for TLS peer communication (default: `4242`).
- `-web`: Port for the web UI dashboard (default: `8080`, accessible at `http://localhost:8080`).
- `-name`: Custom device name (defaults to system hostname).
- `-config`: Directory for storing TLS certs and paired peers list (defaults to `~/.config/quazaar`).
