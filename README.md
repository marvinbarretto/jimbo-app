# Steps

Lightweight Android app that turns the phone into a telemetry source for Jimbo.

## What it does

- Collects typed telemetry events from Health Connect, device state, and usage stats
- Buffers events locally in Room before sync
- Posts telemetry batches to Jimbo API (`/api/telemetry/events`)
- Shows an operational status screen and settings screen in Compose
- Syncs in the background via WorkManager with configurable network and battery constraints

## Setup

1. Copy `local.properties.example` to `local.properties` and fill in your Jimbo API credentials:
   ```properties
   jimbo.api.url=https://your-vps-ip:port
   jimbo.api.key=your-api-key
   ```

2. Build and install:
   ```bash
   ./gradlew installDebug
   ```

3. On the phone, grant Health Connect permissions when prompted.

4. If you want usage telemetry, grant Usage Access in Android settings when prompted from the app settings screen.

5. You need a health data source (e.g. Google Fit) writing to Health Connect for health events to appear.

## Tech stack

- Kotlin, AGP 9.0.1
- Jetpack Compose + Material 3
- Health Connect API 1.1.0-alpha10
- UsageStatsManager + Android system broadcasts
- WorkManager for background sync
- Room for local event buffering and settings
- No external HTTP libraries — uses HttpsURLConnection with trust-all TLS (self-signed cert on VPS)
