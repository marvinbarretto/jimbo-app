# HANDOFF: jimbo-app's open threads

Open threads only, one line each: `- YYYY-MM-DD · owner: <who> · <what, and the next step> [due YYYY-MM-DD] [JIM-…]`.
Edit your own lines, never rewrite the file; delete a line when it is done (git keeps the history). Live state comes
from `npm run status`; traps go in AGENTS.md (jimbo ADR-0165).
- 2026-11-08 · owner: claude · Geofence trial (home/gym/moons) live since 03:17. Battery cost now records itself: BatteryUsageCollector sends jimbo-app's and Play services' mAh-since-charge hourly (collector `battery_usage`); compare the last reading before each charge across days, then tune the 100 m radii from the `place` events
- 2026-11-09 · owner: claude · Walk round 3 (HW-7849) on the phone: More holds focus 15 s, End walk after the last spot (auto-stop 10 min), `walk.fix` next/next_m and `walk.skip`. On walk 3, check `hold_released` reasons per More, `walk.stop` reason, and that a skipped spot shows its closest `next_m` [HW-7849]
- 2026-11-18 · owner: claude · Share targets "Jimbo" and "Jimbo + note" installed on the Pixel (803e4d9); no real share from him yet. On his first ones, check /api/captures?source=url shows them and the note body starts `Why (Marvin):`, then delete this line
- 2026-11-21 · owner: claude · Every saved place is a fence (bf723d5): the phone holds home/gym/Moons plus the nearest saved places from `GET /api/entities/places/fences` (96 registered at 22:38), re-picked after 2 km or 2 h, and a dwell syncs at once. Next: check the first `place.dwell` with an `ent_` id and a `name` lands within minutes (`/api/telemetry/events?collector=place`, `received_at - ts`), and the battery_usage readings after a day of high-accuracy location
