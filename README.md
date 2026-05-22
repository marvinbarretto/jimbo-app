# jimbo-app

Android telemetry capture for Jimbo, packaged as a Capacitor shell that hosts the gym Next.js PWA. Native Kotlin code runs the collectors and background sync; the PWA provides the UI and calls into native via Capacitor plugins.

## What it does

- Collects telemetry events from multiple Android sources and POSTs them to the Jimbo API
- Buffers events locally in Room so nothing is lost across network drops
- Syncs periodically via WorkManager with configurable network + battery constraints
- Bridges Health Connect exercise sessions into the gym API as `gym_sessions` + `gym_session_cardio` rows with real start/end times
- Exposes native sync state to the PWA over a typed Capacitor bridge

## Collectors

| Collector | What it captures | How it's granted |
|---|---|---|
| Health Connect | Steps, distance, sleep, heart rate, calories, exercise sessions | HC dialog, auto-prompted |
| Device | Battery, network state, power events | No grant needed |
| Activity Recognition | Walking / running / cycling / vehicle / still transitions | Runtime dialog, auto-prompted |
| Location | GPS fixes, faster cadence while moving | Fine + Background Location dialogs |
| Usage Stats | Foreground app usage | System Settings → Special access |
| Notifications | Notification post events (package + category) | System Settings → Notification Listener |
| Media | Music / podcast session starts and stops | Shares the notification listener grant |

Defaults to off in `collector_settings`; enable as each grant lands.

## Setup

1. Add credentials to `android/local.properties`:

   ```properties
   jimbo.api.url=https://your-jimbo-api
   jimbo.api.key=your-api-key
   jimbo.device.id=this-phone-id     # optional, defaults to pixel-marvin
   ```

2. Build and install:

   ```bash
   cd android && ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. Launch the app. Capacitor loads the gym PWA in the WebView; native auto-requests Health Connect + Activity Recognition + Location on first launch. Usage Stats and Notification Listener need a trip through system Settings.

4. You need a data source writing to Health Connect (e.g. Google Fit) for HC events to appear.

## Observing data

```bash
adb logcat -s JimboSync          # native collector + sync activity
adb logcat -s JimboBridge        # native ↔ PWA bridge registration
curl -s "$JIMBO_API/api/telemetry/events?limit=20" -H "X-API-Key: $JIMBO_KEY"
```

## Bridge

The cap shell injects `window.__JIMBO_BRIDGE__` on every page load with `{ capabilities, shellVersion }`. The PWA can feature-detect:

```ts
import { bridge } from '<jimbo-app>/plugins/bridge';
import { Telemetry } from '<jimbo-app>/plugins/telemetry';

if (bridge.has('telemetry')) {
  const status = await Telemetry.getSyncStatus();
  // { lastSyncAt, pendingCount, deadLetterCount }
}
```

Plugin capabilities are versioned — when a method is added, the version is bumped in the native `MainActivity` and the PWA gates the call with `bridge.has('name', 2)`.

## Tech

Capacitor Android shell hosting the gym Next.js PWA. Native modules use Kotlin + WorkManager + Room + Health Connect + Play Services (Activity Recognition + Fused Location). No HTTP library — raw `HttpsURLConnection` because the API VPS is self-signed. See `android/app/build.gradle` and `CLAUDE.md` for the layout and entry points.

## Further development

Things that would extend coverage with relatively little effort, in roughly cheapest → richest order:

- **Bluetooth context** — connected device name/class on connect/disconnect. Identifies headphone use and car connections without GPS.
- **DND / focus mode** — `NotificationManager.getCurrentInterruptionFilter()` sampled on notification events.
- **Headphone detection** — `AudioDeviceCallback`. Distinguishes active listening from "Spotify is open in the background".
- **Call state** — `TelephonyManager.CALL_STATE_*`. Enriches media events.
- **Wi-Fi SSID as location proxy** — `WifiManager.getConnectionInfo().ssid` on network change. Home/office/other without background GPS cost.
- **Periodic health snapshots** — poll resting heart rate / HRV on a daily schedule rather than only when sync runs.
- **More plugins** — Activity (last known transition), Haptics (vibrator), Permissions (deep-link to system Settings from the PWA). The bridge pattern is in place; each plugin is small once the surface is decided.
