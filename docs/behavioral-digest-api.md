# Behavioral Digest API

← [Vision](vision.md)

## The problem

Raw telemetry events land in `/api/telemetry/events` as an append-only log. That data is rich but not directly consumable by the Jimbo suite — the dashboard, games, and journal need *aggregated signals* ("steps today: 7,204") not raw event rows ("type: health_connect_steps, value: 200, ts: ...").

Currently there's no aggregation layer. Briefing, journal, and games rely entirely on self-report.

---

## The goal

A set of read endpoints on jimbo-api that aggregate telemetry events into human-readable signals. All Jimbo surfaces (dashboard, games, journal, native app) can query these instead of parsing raw events themselves.

---

## Proposed endpoints

### `GET /api/behavioral-digest/today`

Returns today's behavioral summary for a device/user.

```json
{
  "date": "2026-06-09",
  "steps": 7204,
  "stepGoalMet": true,
  "activeMinutes": 48,
  "screenTimeMs": 12000000,
  "screenTimeFormatted": "3h 20m",
  "unlockCount": 42,
  "sleepDurationMs": 22200000,
  "sleepFormatted": "6h 10m",
  "restingHeartRate": 52,
  "dominantActivity": "still",
  "topApps": [
    { "label": "Instagram", "foregroundMs": 7200000, "category": "social" },
    { "label": "Kindle", "foregroundMs": 1800000, "category": "media" }
  ],
  "gymSessionToday": true,
  "asOf": 1749469200000
}
```

Built from: `health_connect_steps`, `screen_session`, `health_connect_sleep`, `app_usage_daily` telemetry event types.

---

### `GET /api/behavioral-digest/week`

7-day rolling summary — used by wheel-of-life and trend views.

```json
{
  "from": "2026-06-02",
  "to": "2026-06-09",
  "avgDailySteps": 6841,
  "daysStepGoalMet": 5,
  "avgSleepMs": 24120000,
  "avgRestingHR": 53,
  "gymSessionCount": 3,
  "avgScreenTimeMs": 14400000,
  "totalActiveMinutes": 312
}
```

---

### `GET /api/behavioral-digest/context`

Lightweight endpoint for real-time use (native home screen, briefing). Returns the minimum needed to pick the right card.

```json
{
  "currentActivity": "still",
  "currentPlace": "home",
  "stepsToday": 2104,
  "sleepLastNight": "6h 10m",
  "activeSessionOpen": false,
  "asOf": 1749469200000
}
```

---

## How games consume it

### Wheel of Life
Before the user rates "Health", show: "This week: 5/7 step goal days, avg sleep 6h 10m, 3 gym sessions." This is a *provocation*, not a pre-fill — the user still rates freely but has evidence in front of them.

### Hedonic vs Eudaimonic
Pre-load the placement grid suggestion from app usage categories: "You spent 3h on social media (pleasure++) and 30min reading (meaning+). That plots roughly here." Again a provocation, not a forced answer.

### Journal (reflect session)
The briefing context injected into the journal prompt includes the digest — LLM has ground truth about the user's day without the user having to type any of it.

---

## Implementation notes

- Aggregate on read (not at ingest time) — raw events stay raw, digest is computed per-request with caching
- Cache at 15-minute granularity — digest doesn't need real-time accuracy
- Device-scoped for now (device_id in query or header) — user scoping comes when multi-device is needed
- Backfill: existing telemetry events already have the right types to power this; aggregation just hasn't been built

---

## Open question

Should `behavioral-digest` be a separate service or just new routes on jimbo-api? Given the current VPS setup, new routes on jimbo-api are the obvious path. A separate service only makes sense if the aggregation logic becomes complex enough to warrant isolation (probably not for a while).
