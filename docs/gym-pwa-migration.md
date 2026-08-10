# Gym PWA Migration Arc

← [Vision](vision.md)

> **Partly superseded, Aug 2026.** The destination changed: the WebView moves to
> a phone-first Angular `/m` shell in the dashboard repo, not to native screens.
> Phases 1 and 4 stand. **Phase 2** (native gym session) and **Phase 3**
> (briefing as native view) are superseded — both live in `/m` instead.
> **Phase 5** is brought forward and is now the point of the arc.
> See `dashboard/docs/architecture/mobile-shell.md`.

## Starting state (pre-migration, for context)

The entire native app experience was the gym Next.js PWA loaded in a Capacitor WebView. The native shell collected telemetry in the background but contributed nothing to the UI.

`capacitor.config.ts` pointed the WebView at `https://gym-kohl-theta.vercel.app`.

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

### Phase 2 — Native gym session screen — **superseded**
The active-session flow lives in the `/m` shell's **Train** tab, not a native screen. The reasons for going native still hold in part — haptics matter, and gym arrival should auto-detect — but they're satisfied through the bridge (`HapticsPlugin`, `ActivityContextPlugin`) rather than by reimplementing the set/rep UI in Compose. The deciding factor: the tracker primitives already exist in Angular and generalise across nutrition, gym and project-work; a third implementation earns nothing.

`/api/gym/sessions/active` and `POST /api/gym/sessions/{id}/sets` already exist, so there's no backend work gating this.

---

### Phase 3 — Briefing as native view — **superseded**
Briefing lives in the `/m` shell's **Today** tab. It's still the primary morning touch-point and still a notification-action destination — it deep-links into `/m` rather than rendering natively. Instant load comes from the service worker, and `HealthSnapshotPlugin` data reaches it over the bridge.

One implementation, reachable from the native home, a notification tap, or a browser.

---

### Phase 4 — Journal / reflection native
The reflect/journal flow gets a native shell that pre-populates context from `behavioral-digest/today`, then hands off to the LLM prompt. The resulting journal entry still saves via jimbo-api REST.

The games (wheel-of-life, hedonic, etc.) are simple enough to port to native Kotlin/Compose. The SvelteKit versions stay alive as browser-accessible fallbacks.

---

### Phase 5 — Gym PWA becomes browser-only — **✅ done (Aug 2026)**
`server.url` defaults to the dashboard's `/m` shell (`capacitor.config.ts`). The gym PWA still exists but isn't loaded in the WebView; it keeps coach chat, voice logging and session history as a browser surface.

Write parity was verified before flipping: gym's `jimbo-client.ts` posts to the
identical endpoints with a subset of the dashboard's fields (it never sends the
aggregated `sets` count; the server defaults it to 1), so the `/m` Train flow —
which shares its writers with the desktop exercise page — is a strict superset.

---

## What the gym PWA should NOT do going forward

Avoid adding new functionality to the gym PWA that:
- Requires native context (location, activity, health snapshot) — these reach `/m` over the bridge
- Is better served by haptics or widgets
- Is a "glance" feature (current activity, today's steps, sync status)
- **Is day-ledger logging of any kind** — that's `/m`'s job now, on the shared tracker primitives

The gym PWA is best suited to: coach chat, voice logging, session history and review — anything that benefits from a larger screen or keyboard.

---

## Risks

- **Double-maintenance during transition** — some features will live in both places temporarily. Accept this; it's cheaper than a big-bang rewrite.
- **WebView caching** — while `server.url` points at Vercel, pin a specific deployment during native rollouts to avoid chasing a moving target (see `capacitor.config.ts`). Once it points at the dashboard this changes shape: assets are hash-named and immutable, but an Angular service worker now sits in front of them, so a bad deploy is sticky until the SW updates. Keep a way to force-refresh from the native side.
- **The phone now rides dashboard deploys** — `npm run release`, `git push --follow-tags`, rsync to the VPS. A broken dashboard deploy is a broken phone app.
- **NotificationListenerService re-grant** — already flagged in migration handoff; plan a one-time onboarding screen.
