# CLAUDE.md

## Project

Android telemetry shell for Jimbo. Native Kotlin code collects events from Health Connect, device broadcasts, activity recognition, location, usage stats, notifications, and media sessions, then syncs them to the Jimbo API. The UI is a web app loaded into a Capacitor WebView — native exposes typed surfaces to it via Capacitor plugins.

**The WebView loads the dashboard's phone-first Angular `/m` shell** (`jimbo/dashboard/docs/architecture/mobile-shell.md`) — the gym PWA was demoted to a browser surface in Aug 2026. The shell authenticates via `AuthPlugin` (X-API-Key over the bridge); the edge serves `/m` + assets uncookied. The bridge is URL-agnostic, so plugins work unchanged. Docs under `docs/` carry supersession notes where the older plan assumed native screens.

`docs/capacitor-migration-handoff.md` has the long-form context on how this project got here.

## Layout

- `android/` — Capacitor Android project (the only Gradle build that ships).
- `android/app/src/main/java/dev/marvinbarretto/jimbo/` — native Kotlin: collectors (`telemetry/`), Room DB (`data/`), plugins (`plugins/`), `MainActivity`, `BridgeRegistry`, `SyncWorker`, `SyncScheduler`, `JimboClient`.
- `plugins/<name>/` — TypeScript plugin definitions (`definitions.ts` / `index.ts` / `web.ts`), shared with the gym repo. Layout mirrors localshout-next.
- `capacitor.config.ts` — points the WebView at the dashboard `/m` shell (CAP_SERVER_URL overrides).
- `docs/` — handoff briefs.

The legacy Kotlin/Compose `app/` module has been deleted — the hosted web UI absorbs everything that used to live in `StatusScreen` / `SettingsScreen`, surfaced through plugins. (Post-cutover that lands in the dashboard `/m` shell, not gym.)

## Build & run

```bash
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s JimboSync          # native telemetry log tag
adb logcat -s JimboBridge        # bridge registration log tag
```

`android/local.properties` provides `jimbo.api.url`, `jimbo.api.key`, `jimbo.device.id` — read into BuildConfig from `android/app/build.gradle`. Not committed.

## Native architecture

Entry point: `MainActivity.kt` extends `BridgeActivity`. On launch it registers Capacitor plugins, attaches `BridgeRegistry`, kicks WorkManager (periodic + one manual run), then requests runtime perms.

- `SyncScheduler` / `SyncWorker` — periodic + manual sync; constraints come from the `sync_constraints` Room table.
- `telemetry/TelemetryStore` orchestrates collectors and writes raw events to Room.
- `telemetry/TelemetrySyncer` drains the Room queue to `/api/telemetry/events`, with retry + dead-letter handling.
- `telemetry/GymSessionBridge` reads HC `ExerciseSessionRecord`s and POSTs them as `gym_sessions` + `gym_session_cardio` after each successful telemetry sync; deduped locally via `gym_session_pushes`.
- `JimboClient` — raw `HttpsURLConnection` with trust-all TLS (self-signed cert on the API VPS).
- `BootReceiver` re-arms collectors and the periodic sync after reboot / package replacement.

## Bridge from native to PWA

`BridgeRegistry` injects `window.__JIMBO_BRIDGE__ = { capabilities, shellVersion }` on every page load. Plugin capabilities are versioned — bump the version in `MainActivity`'s `registerCapability` call when a plugin's API surface changes, and gate new method calls in the PWA with `bridge.has(name, n)`.

To add a new plugin:
1. Create `plugins/<name>/{definitions,index,web}.ts` — TS interface + `registerPlugin` + desktop fallback.
2. Create the Kotlin class at `android/app/src/main/java/dev/marvinbarretto/jimbo/plugins/<Name>Plugin.kt` annotated `@CapacitorPlugin(name="<Name>")`.
3. Register it in `MainActivity.onCreate` **before `super.onCreate()`** with `registerPlugin(<Name>Plugin::class.java)`.
4. Register the capability with `BridgeRegistry` so the PWA can feature-detect.

## Conventions

- Conventional commits: `type: description`.
- Secrets in `local.properties` (gitignored).
- Plugin granularity: split by domain (telemetry, activity, haptics…), not one mega-plugin.
- Don't pin tech versions in docs — read the relevant gradle file.
- Don't apply `kotlin-android` plugin twice; `android/build.gradle` already declares the classpath.
