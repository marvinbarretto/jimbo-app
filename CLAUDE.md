# CLAUDE.md

## Project

Android telemetry app for Jimbo. Collects events from Health Connect, device state, and usage stats, then syncs them to Jimbo API.

## Stack

- Kotlin + AGP 9.0.1 (AGP bundles Kotlin plugin — don't apply `kotlin-android` explicitly in app module)
- Jetpack Compose with Material 3 dynamic theming
- Health Connect 1.1.0-alpha10
- WorkManager for background sync
- Room for event buffering and settings
- No Retrofit/OkHttp — raw HttpsURLConnection with trust-all TLS

## Build

```bash
./gradlew installDebug    # build + install on connected device
adb logcat -s JimboSync   # watch logs
```

## Architecture

- `HealthConnectReader` — preserves lightweight read helpers for local UI summaries.
- `JimboClient` — POSTs to `/api/telemetry/events` with self-signed TLS handling. Credentials from `local.properties` via BuildConfig.
- `SyncWorker` — periodic background collector + telemetry drain worker.
- `StatusViewModel` / `SettingsViewModel` — expose status and settings state for Compose.
- `ui/StatusScreen.kt` / `ui/JimboApp.kt` — operational status UI and settings navigation shell.
- `ui/theme/Theme.kt` — Material 3 with dynamic wallpaper colors.

## Conventions

- Conventional commits: `type: description`
- Secrets in `local.properties` (not committed)
- Unit tests live under `app/src/test`
