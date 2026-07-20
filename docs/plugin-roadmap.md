# Plugin Roadmap

← [Vision](vision.md)

Capacitor plugins expose native data to the gym PWA via `window.__JIMBO_BRIDGE__`. Each plugin follows the pattern in `plugins/<name>/definitions.ts`.

The current plugin:
- `TelemetryPlugin` (v1) — read-only sync status (lastSyncAt, pendingCount, deadLetterCount)

---

## Priority 1 — Real-time context for the PWA

### `ActivityContextPlugin`
Expose the current activity recognition state so the PWA can adapt its UI.

```ts
interface ActivityContextPlugin {
  getCurrentActivity(): Promise<{
    state: 'still' | 'walking' | 'running' | 'in_vehicle' | 'on_bicycle' | 'unknown';
    confidence: number;        // 0–100
    since: number | null;      // epoch millis when state last changed
  }>;
}
```

**Why:** The PWA can show "you're walking — want to log this as active time?" or suppress gym prompts when you're driving.

---

### `HealthSnapshotPlugin`
Today's Health Connect summary — the single most useful thing to surface to briefing and journal.

```ts
interface HealthSnapshotPlugin {
  getTodaySnapshot(): Promise<{
    steps: number | null;
    stepGoal: number | null;           // from HC goal if set
    restingHeartRate: number | null;   // bpm
    sleepDurationMs: number | null;    // last night's sleep window
    activeCalories: number | null;
    asOf: number;                      // epoch millis when data was read
  }>;
}
```

**Why:** Briefing enrichment ("you slept 5.5h"), journal context, wheel-of-life health domain pre-population.

---

## Priority 2 — Digital behaviour signals

### `AppUsageSummaryPlugin`
Today's screen time and top app categories — direct input for hedonic/eudaimonic game and daily reflection.

```ts
interface AppUsageSummaryPlugin {
  getTodaySummary(): Promise<{
    totalScreenTimeMs: number;
    unlockCount: number;
    topApps: Array<{
      packageName: string;
      label: string;
      foregroundMs: number;
      category: 'social' | 'productivity' | 'media' | 'games' | 'other';
    }>;
    asOf: number;
  }>;
}
```

**Why:** "You spent 2h on Instagram and 30min reading — where does that sit on the pleasure×meaning grid?"

---

### `LocationContextPlugin`
Named place from geofence rather than raw coordinates — safe to expose to PWA without leaking exact GPS.

```ts
interface LocationContextPlugin {
  getCurrentPlace(): Promise<{
    place: 'home' | 'work' | 'gym' | 'commuting' | 'unknown';
    since: number | null;
  }>;
}
```

**Why:** Adaptive home screen ("you're at the gym"), briefing context, location-aware task suggestions.

Requires defining geofences — initial set: home, work, gym. Configurable later.

---

## Priority 3 — Interaction primitives

### `HapticsPlugin`
Native vibration for games and focus sessions.

```ts
interface HapticsPlugin {
  impact(options: { style: 'light' | 'medium' | 'heavy' }): Promise<void>;
  notification(options: { type: 'success' | 'warning' | 'error' }): Promise<void>;
  selection(): Promise<void>;
}
```

**Why:** Wheel-of-life slider feedback, focus timer completion, quick capture confirmation.

---

### `NotificationTriggerPlugin`
Let the PWA fire native Android notifications — for focus timers, briefing reminders, Jimbo nudges.

```ts
interface NotificationTriggerPlugin {
  schedule(options: {
    id: string;
    title: string;
    body: string;
    atMillis: number;
    channelId?: string;
  }): Promise<void>;
  cancel(options: { id: string }): Promise<void>;
}
```

**Why:** Focus session "done" alert when app is backgrounded, morning briefing reminder.

---

## Versioning

Each plugin has a version integer registered in `BridgeRegistry`. Bump the version when adding methods — the PWA uses `window.__JIMBO_BRIDGE__.has(name, version)` to gate calls on the right version.

Current versions: `telemetry@1`.

---

## Adding a plugin

See [CLAUDE.md — bridge section](../CLAUDE.md#bridge-from-native-to-pwa) for the full four-step process: TS definitions → Kotlin class → register in `MainActivity` → register capability in `BridgeRegistry`.
