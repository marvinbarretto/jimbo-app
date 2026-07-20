# Native UI Roadmap

← [Vision](vision.md)

The goal is to move from "WebView that loads the gym PWA" to "native app with WebView as one section". The wedge is a native home screen that appears before and instead of the WebView landing.

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
                ├── Tap "Gym" → WebView / native gym session screen
                ├── Tap "Focus" → Native focus timer
                ├── Tap "Capture" → Native bottom sheet
                ├── Tap "Briefing" → WebView (dashboard briefing page)
                └── Tap "Reflect" → Native reflection launcher
```

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

Keep it flat and gestural — no nested tab bars. The gym PWA already has its own bottom nav for within-gym navigation; don't fight it. 

```
Native home screen
├── → Native gym session (replaces WebView for this flow eventually)
├── → Focus timer
├── → Quick capture (bottom sheet, overlays current screen)
├── → Reflection launcher → Journal / Games (WebView or native)
├── → Settings (native)
│     └── → Developer / Telemetry status
└── → "Open dashboard" (WebView → dashboard URL)
```

The WebView stays for anything complex (tasks, briefing, journal CRUD, dashboard review) until those features get native treatments.

---

## Phased delivery

| Phase | What changes |
|-------|-------------|
| 1 | Native home screen sits in front of WebView |
| 2 | Quick capture sheet (native, always accessible) |
| 3 | Focus timer (native, calls jimbo-api) |
| 4 | Gym session screen (native, replaces WebView gym flow) |
| 5 | Reflection launcher with behavioral context injection |
| 6 | Briefing as a native rendered view |
