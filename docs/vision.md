# Jimbo App — Product Vision

> **North star:** the native Android app is a *context layer* — it knows what you're actually doing and feeds that ground truth into the Jimbo suite so reflection tools, briefings, and games can be honest rather than relying purely on self-report.

The app started as a telemetry shell wrapping the gym PWA in a Capacitor WebView. The vision ahead is for it to become the primary mobile surface for the whole Jimbo system — with the gym PWA eventually demoted to a browser fallback and desktop experience.

---

## Why native?

The native shell uniquely owns:

- **Passive behavioral data** — activity recognition (still/walking/running/driving), Health Connect (steps, HR, exercise sessions), app usage stats, screen time
- **Device context** — battery, charging, screen on/off, location, current media
- **Background persistence** — WorkManager collectors run whether the app is open or not
- **Native UX primitives** — haptics, push notifications, widgets, geofencing

No web surface can replicate this. The value proposition of native is *knowing things the user didn't have to tell you*.

---

## The six use cases

When someone picks up their phone and opens this app, they're in one of these modes:

| Context | Time / Signal | What they want |
|---------|---------------|----------------|
| **Morning arrival** | 6–9am, at home | Sleep est., briefing, today's tasks |
| **At the gym** | High HR, gym geofence | Log session, live HC metrics |
| **Focus mode** | Explicit tap, any time | Timer, task lock, haptic done |
| **Quick capture** | 30 seconds, any time | Log thought/task before it disappears |
| **Mid-day check-in** | Long still period / high screen time | Steps, screen time, nudge |
| **Evening reflection** | 9pm+, low activity | Journal, wheel-of-life, games |

The home screen should adapt to these contexts rather than defaulting to the gym PWA every time.

---

## Linked docs

- [Plugin Roadmap](plugin-roadmap.md) — planned Capacitor plugins exposing native data to the PWA
- [Native UI Roadmap](native-ui-roadmap.md) — screen-by-screen native experience and home screen design
- [Behavioral Digest API](behavioral-digest-api.md) — aggregation layer on jimbo-api that turns raw telemetry into actionable signals
- [Gym PWA Migration Arc](gym-pwa-migration.md) — how and when to demote the WebView from primary to fallback

---

## What we are not building

- A replacement for the dashboard (Angular) — that stays as the deep review/management surface
- A replacement for jimbo-games (SvelteKit) — those migrate to native eventually but the web versions stay
- A standalone health app — this is Jimbo context enrichment, not a fitness tracker

---

## Guiding principles

1. **Passive over explicit** — the best data collection is zero-effort from the user
2. **Context over CRUD** — the home screen shows what's relevant now, not a menu
3. **Ground truth enriches reflection** — behavioral data is a *provocation*, not an answer
4. **Degrade gracefully** — every native feature has a no-op web fallback via `window.__JIMBO_BRIDGE__`
