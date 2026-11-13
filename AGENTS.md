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

Walk mode (`walk/`, HW-7852): `jimbo.walk.spots=id|lat,lng|clip;id|lat,lng|clip;…` in `android/local.properties` (same `;` and `|` as `jimbo.places`). A debug build with spots set posts a silent "Walk mode" notification when the app opens; its **Start walk** action starts `WalkSessionService` (location foreground service, fused fixes every 3 s), and the running notification's **Stop walk** ends it. Within 30 m each spot plays once per walk under transient audio focus (your podcast pauses, then resumes). A clip is a file named `clip` (any extension) pushed to `/sdcard/Android/data/dev.marvinbarretto.jimbo/files/walk/`; with no file it speaks a placeholder. Events: collector `walk` (`walk.start`, `walk.trigger` with spot + distance, `walk.stop`), plus diagnostics so a walk can be explained afterwards: `walk.fix` for every fix (nearest unplayed spot, its distance, accuracy, gap since the last fix, `ignored` when too vague to trigger), `walk.audio` for each focus and clip step, and `walk.summary` at stop (fix counts, longest gap, battery at start and end). Walk 2 (HW-7849): while a clip plays the running notification offers **Pause/Resume**, **Back 10 s** and **Skip** (Android shows three actions, so Stop walk returns when the clip ends); Pause keeps focus for 60 s, then the clip ends and the podcast comes back. A brief focus loss (AUDIOFOCUS_LOSS_TRANSIENT) pauses the clip and AUDIOFOCUS_GAIN resumes it; a duck plays on; a permanent loss stops it and hands focus back, as before. A spoken placeholder cannot pause, so it is dropped on a brief loss. When a clip ends by itself and a file `<clip>-more` (any extension) is in the walk folder, the notification offers **More** / **Skip** for 60 s; More plays the long clip under its own transient focus. Their events: `walk.audio` `focus_lost` carries `kind` (transient, permanent, duck), plus phases `focus_regained`, `clip_skipped`, `pause_expired`; `walk.control` (action pause/resume/back/skip, clip, `position_s`, `applied` false when nothing played); `walk.choice` (choice offered/taken/declined/expired, clip, `reason` superseded or walk_stopped when it lapsed early); `walk.media` (every other app's media session as `{package, state}` with state playing/paused/stopped/other, at `point` clip_start, after_start (+2 s), clip_end, skip and after_abandon (+3 s after focus goes back), or `error` such as no_notification_access; read through `NotificationCollectorService`'s listener access). Walk 3 (HW-7849): after a clip with a `-more` file ends by itself, its transient focus is kept, in silence, for up to 15 s, so the podcast does not resume and pause again when More is tapped (walk 2's takes came within 5–12 s); More hands the held focus straight to the long clip, and Skip, the 15 s, Stop walk or a focus loss hand it back (after 15 s, More asks for focus again). The offer itself still lasts 60 s. Once the walk is finished (every spot fired, or the last spot in walk order: a skipped spot does not keep it open) and its last clip, or that clip's More, has ended, the notification offers **End walk**; untouched, the walk stops itself 10 minutes after that clip ended (`WalkEnding`). Their events: `walk.audio` phases `focus_held` (clip) and `hold_released` with `reason` (more, next_clip, declined, timeout, walk_stopped, focus_lost); `walk.stop` gains `reason` (user, end_prompt, auto_after_last; absent when the system destroyed the service without a stop); `walk.summary` gains `seconds_after_last_trigger`; `walk.fix` gains `next`/`next_m`, the first unfired spot in walk order (the configured order) and its distance, beside `nearest`/`nearest_m`; `walk.skip {walk, spot}` once per spot passed over, when a later spot in walk order fires first. Read them at `/api/telemetry/events?collector=walk`. Never coordinates. Log tag `JimboWalk`. Walk mode is an experiment: build the diagnostics with each feature, so every assumption it tests leaves evidence.

Sleep question (`sleepask/`, sleep-metric SM-7959): the first unlock (USER_PRESENT) after 3+ hours phone-down, at most once per 12 h, posts "Were you asleep?" on the `sleep` channel with a **Slept…** reply box and **Wasn't asleep**. Phone-down starts at a SCREEN_OFF that follows an unlock, so a notification lighting the screen does not end a night. Both broadcasts reach runtime receivers only, registered in `JimboApplication` like DeviceBroadcasts; state lives in shared prefs `sleep_ask`. Events, collector `sleep`, source `sleep_ask`: `sleep.ask`, `sleep.label` (`kind` slept/not_asleep, `answer` verbatim) and `sleep.ask_dismissed`, each with `phone_down_from`/`phone_down_until`. The answers are the project's ground truth: never parse or rewrite them on the phone. Log tag `JimboSleepAsk`.

## Native architecture

Entry point: `MainActivity.kt` extends `BridgeActivity`. On launch it registers Capacitor plugins, attaches `BridgeRegistry`, kicks WorkManager (periodic + one manual run), then requests runtime perms.

- `SyncScheduler` / `SyncWorker` — periodic + manual sync; constraints come from the `sync_constraints` Room table.
- `telemetry/TelemetryStore` orchestrates collectors and writes raw events to Room.
- `telemetry/TelemetrySyncer` drains the Room queue to `/api/telemetry/events`, with retry + dead-letter handling.
- `telemetry/GymSessionBridge` reads HC `ExerciseSessionRecord`s and POSTs them as `gym_sessions` + `gym_session_cardio` after each successful telemetry sync; deduped locally via `gym_session_pushes`.
- `JimboClient` — raw `HttpsURLConnection` with normal certificate checks against `jimbo.fourfoldmedia.uk` (TlsVerificationTest; trust-all went 2026-10-13).
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
