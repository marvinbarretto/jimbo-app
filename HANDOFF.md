# HANDOFF: jimbo-app's open threads

Open threads only, one line each: `- YYYY-MM-DD · owner: <who> · <what, and the next step> [due YYYY-MM-DD] [JIM-…]`.
Edit your own lines, never rewrite the file; delete a line when it is done (git keeps the history). Live state comes
from `npm run status`; traps go in AGENTS.md (jimbo ADR-0165).
- 2026-11-08 · owner: claude · Geofence trial (home/gym/moons) live since 03:17. Battery cost now records itself: BatteryUsageCollector sends jimbo-app's and Play services' mAh-since-charge hourly (collector `battery_usage`); compare the last reading before each charge across days, then tune the 100 m radii from the `place` events
