# CLAUDE.md

## Project

Android health data app. Reads from Health Connect, displays on screen, syncs to Jimbo API.

## Stack

- Kotlin + AGP 9.0.1 (AGP bundles Kotlin plugin — don't apply `kotlin-android` explicitly in app module)
- Jetpack Compose with Material 3 dynamic theming
- Health Connect 1.1.0-alpha10
- WorkManager for background sync
- No Retrofit/OkHttp — raw HttpsURLConnection with trust-all TLS

## Build

```bash
./gradlew installDebug    # build + install on connected device
adb logcat -s StepsSync   # watch logs
```

## Architecture

- `HealthConnectReader` — reads 7 data types from Health Connect. `readAll()` returns JSONObject for API, `readToday()` returns `TodayData` for UI.
- `JimboClient` — POSTs to `/api/fitness/sync` with self-signed TLS handling. Credentials from `local.properties` via BuildConfig.
- `SyncWorker` — WorkManager hourly background sync (2-hour read window with overlap).
- `StepsViewModel` — connects reader + client, exposes `StateFlow<StepsUiState>`.
- `ui/StepsScreen.kt` — single scrollable Compose screen showing all health stats.
- `ui/theme/Theme.kt` — Material 3 with dynamic wallpaper colors.

## Conventions

- Conventional commits: `type: description`
- Secrets in `local.properties` (not committed)
- No tests yet — MVP stage
