# Gym PWA Migration Arc

← [Vision](vision.md)

## Current state

The entire native app experience is the gym Next.js PWA loaded in a Capacitor WebView. The native shell collects telemetry in the background but contributes nothing to the UI.

`capacitor.config.ts` points the WebView at `https://gym-kohl-theta.vercel.app`.

The gym PWA's current features:
- Workout session logging (exercises, sets, reps, cardio)
- Session history and review
- Coach / AI-assisted workout suggestions
- Settings
- Health Connect read (partially — the native `GymSessionBridge` handles the serious HC sync)

---

## The migration principle

**Don't sunset the gym PWA — demote it.**

The gym PWA is a CRUD surface that works fine in a browser. The native app's value is context and immediacy. These aren't the same job. The path forward is to move the *native-only* features out of the WebView and keep the gym PWA as the depth layer for complex workout management.

---

## Phases

### Phase 1 — Native home screen (current next step)
The gym PWA stops being the landing screen. A native home screen loads first and the gym PWA becomes one destination reachable from a "Gym" tap.

**What the gym PWA still owns:** all workout CRUD  
**What native owns:** home screen, context card, quick actions

Nothing in the gym PWA changes. The Capacitor `server.url` still points there; we just add a native Activity that renders before the WebView loads.

---

### Phase 2 — Native gym session screen
Replace the WebView flow for starting/logging a gym session with a native screen. This is the highest-value native replacement because:
- Health Connect reads are faster without the bridge roundtrip
- Haptics on set completion feel right
- Session data can be written directly without a WebView network call

The gym PWA keeps the session *history/review* view. Native owns the *active session* flow.

**Migration trigger:** when `ActivityContextPlugin` is built — the native session screen uses it to auto-detect gym arrival.

---

### Phase 3 — Briefing as native view
The daily briefing is currently in the Angular dashboard (separate URL). Surfacing it natively means: fetch the briefing payload from jimbo-api, render it in a native view. No Angular, no WebView.

This is worth doing because:
- Briefing is the primary morning touch-point — should load instantly
- It can consume `HealthSnapshotPlugin` data directly without a bridge call
- It becomes a notification action destination ("tap to see today's briefing")

---

### Phase 4 — Journal / reflection native
The reflect/journal flow gets a native shell that pre-populates context from `behavioral-digest/today`, then hands off to the LLM prompt. The resulting journal entry still saves via jimbo-api REST.

The games (wheel-of-life, hedonic, etc.) are simple enough to port to native Kotlin/Compose. The SvelteKit versions stay alive as browser-accessible fallbacks.

---

### Phase 5 — Gym PWA becomes browser-only
At this point, the gym PWA still exists but isn't loaded in the WebView. The WebView may be used for the dashboard or removed entirely. The gym app becomes what it originally was: a browser-accessible workout tracker.

The Capacitor `server.url` config either points to the dashboard or is removed.

---

## What the gym PWA should NOT do going forward

As native features get built, avoid adding new functionality to the gym PWA that:
- Requires native context (location, activity, health snapshot) — these belong to native screens
- Is better served by haptics or widgets
- Is a "glance" feature (current activity, today's steps, sync status)

The gym PWA is best suited to: complex data entry, session history, settings, anything that benefits from a larger screen or keyboard.

---

## Risks

- **Double-maintenance during transition** — some features will live in both places temporarily. Accept this; it's cheaper than a big-bang rewrite.
- **WebView caching** — the gym PWA at `gym-kohl-theta.vercel.app` deploys independently. Pin to a specific deployment URL during native rollouts to avoid chasing a moving target. See `capacitor.config.ts` comment in [migration handoff](capacitor-migration-handoff.md).
- **NotificationListenerService re-grant** — already flagged in migration handoff; plan a one-time onboarding screen.
