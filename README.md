# jimbo-app

Lightweight Android app that turns the phone into a telemetry source for Jimbo.

## What it does

- Collects typed telemetry events from multiple sources and syncs them to Jimbo API
- Buffers events locally in Room before sync so nothing is lost if the network is down
- Posts telemetry batches to Jimbo API (`/api/telemetry/events`)
- Shows an operational status screen and settings screen in Compose
- Syncs in the background via WorkManager with configurable network and battery constraints

## Collectors

| Collector | What it captures | Permission needed |
|---|---|---|
| **Health Connect** | Steps, distance, sleep, heart rate, calories | Health Connect (prompted on launch) |
| **Device** | Battery level/charging state, screen on/off, boot, power events | None |
| **Usage Stats** | Foreground app usage (which apps were used and for how long) | Usage Access (deep-link to settings) |
| **Notifications** | Notification posted events with package and category | Notification Listener Service (deep-link) |
| **Activity** | Movement transitions — walking, running, cycling, in vehicle, still | Activity Recognition (runtime dialog) |
| **Media** | Music/podcast session starts and stops (package + title/artist if available) | Shares notification listener permission |
| **Location** | GPS fixes with accuracy — adaptive cadence, faster when moving | Fine Location + Background Location (two dialogs) |

All collectors default to **disabled** in settings. Enable them individually as you grant each permission.

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

4. Open **Settings** in the app and enable collectors:
   - **Usage Stats** — tap "Grant usage access" to reach the system settings screen
   - **Notifications** — tap "Grant notification access" to reach the notification listener screen
   - **Activity** — tap "Grant permission" for the standard Android runtime dialog
   - **Location** — tap "Grant location access" twice: first dialog grants precise location, a second tap (after the first is approved) opens the background location prompt where you select "Allow all the time"

5. You need a health data source (e.g. Google Fit) writing to Health Connect for health events to appear.

## Observing data

```bash
# Watch logs on a connected device
adb logcat -s JimboSync

# Query the API for recent events (adjust URL/key)
curl -s "https://your-vps/api/telemetry/events?limit=20" \
  -H "Authorization: Bearer YOUR_KEY" | jq '.events[] | [.collector, .type, .ts]'
```

## Tech stack

- Kotlin, AGP 9.0.1
- Jetpack Compose + Material 3
- Health Connect API 1.1.0-alpha10
- `UsageStatsManager` + Android system broadcasts (battery, screen, power)
- `NotificationListenerService` for notification and media session events
- Google Play Services `ActivityRecognitionClient` — Transitions API (enter/exit only, no polling)
- `FusedLocationProviderClient` with `PendingIntent` + `BroadcastReceiver` for background location
- WorkManager for background sync
- Room for local event buffering and settings
- No external HTTP libraries — uses `HttpsURLConnection` with trust-all TLS (self-signed cert on VPS)

## Further development

Things that would extend coverage with relatively little effort:

- **Bluetooth context** — connected device name/class on connect/disconnect. Identifies headphone use and car connections without needing location.
- **DND / focus mode state** — `NotificationManager.getCurrentInterruptionFilter()` sampled on notification events. Flags focus-time sessions without relying on self-reporting.
- **Headphone detection** — `AudioDeviceCallback` or `ACTION_HEADSET_PLUG`. Distinguishes active listening from phone just having Spotify open.
- **Call state** — `TelephonyManager.CALL_STATE_*` via `PhoneStateListener`. Enriches media events ("was this a hands-free call or music?").
- **Wi-Fi SSID as location proxy** — `WifiManager.getConnectionInfo().ssid` on network change. Home/office/other without background GPS cost.
- **Screen-on/off cadence** — count unlock events per hour as a crude attentiveness signal.
- **Periodic health snapshots** — poll resting heart rate and HRV from Health Connect on a daily schedule rather than waiting for sync to pull everything in one batch.
