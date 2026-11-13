# HANDOFF: jimbo-app's open threads

Open threads only, one line each: `- YYYY-MM-DD · owner: <who> · <what, and the next step> [due YYYY-MM-DD] [JIM-…]`.
Edit your own lines, never rewrite the file; delete a line when it is done (git keeps the history). Live state comes
from `npm run status`; traps go in AGENTS.md (jimbo ADR-0165).
- 2026-11-08 · owner: claude · Geofence trial (home/gym/moons) live since 03:17. Battery cost now records itself: BatteryUsageCollector sends jimbo-app's and Play services' mAh-since-charge hourly (collector `battery_usage`); compare the last reading before each charge across days, then tune the 100 m radii from the `place` events
- 2026-11-09 · owner: claude · Walk round 3 (HW-7849) on the phone: More holds focus 15 s, End walk after the last spot (auto-stop 10 min), `walk.fix` next/next_m and `walk.skip`. On walk 3, check `hold_released` reasons per More, `walk.stop` reason, and that a skipped spot shows its closest `next_m` [HW-7849]
- 2026-11-13 · owner: claude · Sleep night question live (4e0c66f): silent, gathers every phone-down stretch of the night. Check the first nights' `sleep.ask`/`sleep.label`/`sleep.ask_hidden` events (collector sleep, source sleep_ask): answered or ignored, fired at a 4am pickup, fired after an evening at the Mac; tune the thresholds in Night from them [SM-7959]
