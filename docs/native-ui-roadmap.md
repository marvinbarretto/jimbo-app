# Native UI Roadmap

← [Vision](vision.md)

The goal is to move from "WebView that loads the gym PWA" to "native app with WebView as one section". The wedge is a native home screen that appears before and instead of the WebView landing.

> **Updated Aug 2026.** The WebView's content is the dashboard's `/m` shell (Today / Log / Train tabs), and — a second revision — **the shell is currently the launcher, not the native home**. The Phase-1 home card duplicated `/m/today` poorly while the context layer is thin (one stat, no geofence, no adaptive behaviour), so it was demoted: `HomeActivity` stays in the codebase and reclaims the launcher slot when the adaptive context card is genuinely worth a tap before the shell. The retired native screens below (gym session, briefing view) stay retired. Native builds what only native can. See `dashboard/docs/architecture/mobile-shell.md`.

---

## Current state

```
App launch → Capacitor bridge init → WebView loads gym PWA → gym PWA is the entire experience
```

The gym PWA handles: workout logging, session history, cardio tracking, settings.

---

## Target state

```
App launch → Native home screen (adaptive context card + quick actions)
                ├── Tap "Gym" → WebView → /m/train
                ├── Tap "Food" → WebView → /m/log
                ├── Tap "Briefing" → WebView → /m/today
                ├── Tap "Focus" → Native focus timer
                ├── Tap "Capture" → Native bottom sheet
                └── Tap "Reflect" → Native reflection launcher
```

Every web destination is a deep link into one `/m` tab. The home screen picks
which one based on context; the shell renders it.

---

## The adaptive home screen

The home screen reads native context on load and shows the most relevant card. No manual mode selection — the app infers it.

### Morning card (6–9am, at home)
```
┌─────────────────────────────────────┐
│  Mon 9 Jun  ·  7:42am  ·  ⚡ 84%   │
│                                     │
│  ╔═════════════════════════════╗    │
│  ║  Good morning               ║    │
│  ║  Sleep est: 6h 10m  ·  52↓  ║    │
│  ╚═════════════════════════════╝    │
│                                     │
│  ┌─────────────────────────────┐    │
│  │  Today's briefing  ›        │    │
│  └─────────────────────────────┘    │
│                                     │
│  ┌─────────────────────────────┐    │
│  │  3 tasks in focus  ›        │    │
│  └─────────────────────────────┘    │
│                                     │
│  [ Capture ]  [ Focus ]  [ Gym ]    │
└─────────────────────────────────────┘
```

### Gym card (at gym geofence, or elevated HR)
```
┌─────────────────────────────────────┐
│  At gym  ·  11:03am                 │
│                                     │
│  ╔═════════════════════════════╗    │
│  ║  No active session          ║    │
│  ║  Last: Chest  ·  3 days ago ║    │
│  ║                             ║    │
│  ║  [ Start session ]          ║    │
│  ╚═════════════════════════════╝    │
│                                     │
│  Steps today: 2,104  ·  HR: 68 bpm │
│                                     │
│  [ Capture ]  [ Focus ]  [ Review ] │
└─────────────────────────────────────┘
```

### Evening card (9pm+, low activity, at home)
```
┌─────────────────────────────────────┐
│  Mon 9 Jun  ·  9:14pm               │
│                                     │
│  ╔═════════════════════════════╗    │
│  ║  Today at a glance          ║    │
│  ║  Steps: 7,204  ·  Screen: 3h20m  ║
│  ║  Active: 48min              ║    │
│  ╚═════════════════════════════╝    │
│                                     │
│  ┌─────────────────────────────┐    │
│  │  How was today?  ›          │    │   ← journal / wheel-of-life
│  └─────────────────────────────┘    │
│                                     │
│  [ Capture ]  [ Journal ]  [ Games ]│
└─────────────────────────────────────┘
```

---

## Native screens to build

### 1. Home screen (adaptive context card)
The architectural wedge. Reads `ActivityContextPlugin`, `HealthSnapshotPlugin`, `LocationContextPlugin` and renders the right card. Quick action strip is always present.

**Input:** native plugin data  
**Output:** navigation to other screens

---

### 2. Focus timer screen
Pomodoro-style with native haptics on completion. Pushes session to Jimbo API. Shows current task from active context.

**Input:** task name (from PWA context or typed)  
**Output:** `POST /focus-sessions` to jimbo-api, haptic on done, native notification if backgrounded

---

### 3. Quick capture bottom sheet
Accessible from any screen via FAB or widget. Single text field, optional type selector (thought/task/mood). Submits to vault.

**Input:** user text  
**Output:** `POST /vault` via jimbo-api

---

### 4. Reflection launcher
Pre-populates context before handing off to games/journal. Shows "here's what today looked like" — steps, screen time, activity summary — then offers Journal, Wheel-of-Life, or Hedonic game.

**Input:** `HealthSnapshotPlugin` + `AppUsageSummaryPlugin` data  
**Output:** navigation to game/journal with context pre-loaded

---

### 5. Telemetry status screen
Shows collectors active, last sync time, pending/dead-letter counts. Replaces the legacy Compose `StatusScreen`. Hidden behind Settings → Developer, not prominent.

**Input:** `TelemetryPlugin.getSyncStatus()` + direct Room query  
**Output:** display only

---

## Navigation model

Keep it flat and gestural — no nested tab bars *in native*. The `/m` shell has its own bottom tab bar for within-web navigation; don't fight it or mirror it.

```
Native home screen
├── → WebView /m/today   (briefing, day checks)
├── → WebView /m/log     (nutrition ledger)
├── → WebView /m/train   (gym session + ledger)
├── → Focus timer (native)
├── → Quick capture (native bottom sheet, overlays current screen)
├── → Reflection launcher → Journal / Games (WebView or native)
└── → Settings (native)
      └── → Developer / Telemetry status
```

The WebView is now the default destination for anything with a form or a list. Native owns the launcher, the focus timer, quick capture, and the reflection context injection — the things that need to be instant, backgrounded, or physical.

---

## Phased delivery

| Phase | What changes |
|-------|-------------|
| 1 | Native home screen sits in front of WebView ✅ (`4db8b54`) |
| 2 | `AuthPlugin` + `server.url` → dashboard `/m`; home tiles deep-link into tabs |
| 3 | Quick capture sheet (native, always accessible) |
| 4 | Focus timer (native, calls jimbo-api) |
| 5 | Reflection launcher with behavioral context injection |
| ~~—~~ | ~~Gym session screen (native)~~ — superseded by `/m/train` |
| ~~—~~ | ~~Briefing as a native rendered view~~ — superseded by `/m/today` |
