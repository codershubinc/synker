# Quazaar Klient (Mobile Client)

Android mobile client written in Kotlin + Jetpack Compose for the Quazaar decentralized P2P bridge.

## Features

- **Zero-Config Network Discovery (mDNS):**
  Uses Android's `NsdManager` in [`NsdDiscoveryManager.kt`](file:///home/swap/Github/Quazaar-synker/klient/app/src/main/java/com/quazaar/synker/klient/network/NsdDiscoveryManager.kt) to discover nearby Quazaar PC nodes broadcasting over `_quazaar._tcp`.
- **Encrypted Peer Socket & Handshake:**
  Maintains an asynchronous TLS socket connection in [`PeerClientManager.kt`](file:///home/swap/Github/Quazaar-synker/klient/app/src/main/java/com/quazaar/synker/klient/network/PeerClientManager.kt), handling pairing negotiation and 20s heartbeat keep-alives.
- **Glassmorphism Compose UI:**
  Built with Jetpack Compose in [`MainMediaDashboard.kt`](file:///home/swap/Github/Quazaar-synker/klient/app/src/main/java/com/quazaar/synker/klient/ui/MainMediaDashboard.kt) featuring dynamic blurred album art backdrops, ambient gradient glows, and frosted panels with 1px border highlights via [`FrostedGlassCard.kt`](file:///home/swap/Github/Quazaar-synker/klient/app/src/main/java/com/quazaar/synker/klient/ui/components/FrostedGlassCard.kt).
- **Background Headless Services:**
  - [`QuazaarForegroundService.kt`](file:///home/swap/Github/Quazaar-synker/klient/app/src/main/java/com/quazaar/synker/klient/service/QuazaarForegroundService.kt): Keeps the TLS socket and telemetry active when the app is minimized.
  - [`QuazaarNotificationListener.kt`](file:///home/swap/Github/Quazaar-synker/klient/app/src/main/java/com/quazaar/synker/klient/service/QuazaarNotificationListener.kt): Intercepts device notifications and pushes them directly across the P2P socket to the desktop daemon.

## Project Structure

```
klient/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/quazaar/synker/klient/
│           ├── QuazaarApplication.kt
│           ├── MainActivity.kt
│           ├── data/
│           │   └── Models.kt
│           ├── network/
│           │   ├── NsdDiscoveryManager.kt
│           │   └── PeerClientManager.kt
│           ├── service/
│           │   ├── QuazaarForegroundService.kt
│           │   └── QuazaarNotificationListener.kt
│           └── ui/
│               ├── MainMediaDashboard.kt
│               └── components/
│                   └── FrostedGlassCard.kt
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
    └── libs.versions.toml
```
