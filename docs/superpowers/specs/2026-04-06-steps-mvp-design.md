# Steps MVP Design

## Goal

Finish the Steps Android app MVP: read all available health data from Health Connect, display it on screen, and sync it to Jimbo API. UI is functional, not polished.

## What Exists

- **HealthConnectReader.kt** — reads 7 data types (steps, distance, active calories, total calories, exercise sessions, floors, elevation) for a given time range, returns structured records
- **JimboClient.kt** — POSTs records to `/api/fitness/sync` with device_id, handles self-signed TLS
- **SyncWorker.kt** — WorkManager background sync every hour, 2-hour windows, retry on failure
- **MainActivity.kt** — permission flow, schedules background sync, currently renders a single TextView
- **Jimbo API** — `/api/fitness/sync` (POST), `/api/fitness/summary` (GET), `/api/fitness/records` (GET) all exist and work

## What We're Building

### 1. Add Jetpack Compose

- Add Compose BOM, Material 3, activity-compose, viewmodel-compose, lifecycle-runtime-compose dependencies to `app/build.gradle.kts`
- Enable Compose compiler

### 2. StepsViewModel

- On load: read today's health data via HealthConnectReader (midnight to now)
- Expose UI state: list of health records grouped by type, last sync time, sync status (idle/syncing/success/error)
- Manual sync action: read today's data, post to JimboClient, update status
- Pull-to-refresh or manual button triggers re-read + sync

### 3. StepsScreen (Compose UI)

A single scrollable column showing:
- **Header** — "Steps" + current date
- **Stats section** — one row per data type with label + value (e.g., "Steps: 4,521", "Distance: 3.2 km", "Floors: 7")
- **Exercise sessions** — listed individually with type + duration
- **Sync status** — last sync time, sync button, error message if any

No progress rings, no charts, no navigation. Just data on screen.

### 4. Minimal Theme

- `ui/theme/Theme.kt` — Material 3 dynamic color theme, dark mode support
- No custom colors or typography beyond defaults

### 5. HealthConnectReader Changes

- Add `readToday()` convenience method that calls existing read logic with today's date range
- Return a structured result the ViewModel can consume directly (not just the raw records list for API posting)

### 6. MainActivity Changes

- Replace `setContent` with Compose: `StepsTheme { StepsScreen(viewModel) }`
- Keep existing permission flow — Compose screen shows permission prompt state if not granted
- Keep existing WorkManager scheduling

## File Plan

| File | Action |
|------|--------|
| `app/build.gradle.kts` | Edit — add Compose deps |
| `app/src/main/java/dev/marvinbarretto/steps/MainActivity.kt` | Edit — swap to Compose |
| `app/src/main/java/dev/marvinbarretto/steps/HealthConnectReader.kt` | Edit — add readToday() |
| `app/src/main/java/dev/marvinbarretto/steps/StepsViewModel.kt` | New |
| `app/src/main/java/dev/marvinbarretto/steps/ui/theme/Theme.kt` | New |
| `app/src/main/java/dev/marvinbarretto/steps/ui/StepsScreen.kt` | New |

## Out of Scope

- Multi-day history view
- Charts or progress indicators
- Custom branding/theming
- Tests (will add after MVP works on device)
- Notifications
