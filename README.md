# Steps

Lightweight Android app that reads health data from Health Connect and syncs it to the Jimbo API.

## What it does

- Reads steps, distance, calories, floors, elevation, and exercise sessions from Health Connect
- Displays today's stats on a simple Compose dashboard
- Posts all records to Jimbo API (`/api/fitness/sync`)
- Background sync every hour via WorkManager
- 30-day backfill on first launch

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

4. You need a health data source (e.g. Google Fit) writing to Health Connect for data to appear.

## Tech stack

- Kotlin, AGP 9.0.1
- Jetpack Compose + Material 3
- Health Connect API 1.1.0-alpha10
- WorkManager for background sync
- No external HTTP libraries — uses HttpsURLConnection with trust-all TLS (self-signed cert on VPS)
