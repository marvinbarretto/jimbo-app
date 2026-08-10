# Plugin Roadmap

← [Vision](vision.md)

Capacitor plugins expose native data to the hosted web surface via `window.__JIMBO_BRIDGE__`. Each plugin follows the pattern in `plugins/<name>/definitions.ts`.

> The consumer is the dashboard's `/m` shell (see [Mobile Shell](../../jimbo/dashboard/docs/architecture/mobile-shell.md)); the gym PWA is a browser surface. The bridge is URL-agnostic — it injects into whatever page loads — so no plugin below changes shape.

Current plugins:
- `TelemetryPlugin` (v1) — read-only sync status (lastSyncAt, pendingCount, deadLetterCount)
- `ActivityContextPlugin` (v1)
- `HealthSnapshotPlugin` (v1)
- `AuthPlugin` (v1) — API credentials for the hosted web shell

---

## Priority 0 — `AuthPlugin` — **built**

`/api/*` and `/stream/*` are cookie-OR-`X-API-Key`, app-gated. Rather than
depending on a WebView session cookie surviving indefinitely, native hands the
web shell the credentials it already holds in BuildConfig.

```ts
interface AuthPlugin {
  getApiCredentials(): Promise<{
    apiKey: string;
    apiUrl: string;    // BuildConfig jimbo.api.url — the shell shouldn't hardcode it
    deviceId: string;  // useful for attributing writes to the phone
  }>;
}
```

**Why this over the cookie:** no expiry to handle, no login screen in the
WebView, and it reuses the plugin pattern already in place. The key is in the
APK either way — this doesn't widen the blast radius meaningfully.

**Rules for the consuming shell:** attach the key only to same-origin `/api`
and `/stream` requests, never log it, and fall back to cookie auth when
`bridge.has('auth')` is false so the same build still works in a desktop
browser. Angular side: an `HttpInterceptor` that resolves credentials once at
bootstrap.

---

## Priority 1 — Real-time context for the web shell

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

Current versions: `telemetry@1`, `activityContext@1`, `healthSnapshot@1`, `auth@1`.

---

## Adding a plugin

See [CLAUDE.md — bridge section](../CLAUDE.md#bridge-from-native-to-pwa) for the full four-step process: TS definitions → Kotlin class → register in `MainActivity` → register capability in `BridgeRegistry`.
