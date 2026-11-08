# HANDOFF: jimbo-app's open threads

Open threads only, one line each: `- YYYY-MM-DD · owner: <who> · <what, and the next step> [due YYYY-MM-DD] [JIM-…]`.
Edit your own lines, never rewrite the file; delete a line when it is done (git keeps the history). Live state comes
from `npm run status`; traps go in AGENTS.md (jimbo ADR-0165).
- 2026-11-08 · owner: marvin · Geofence trial (home/gym/moon) installed 03:17, battery stats reset then. Stats reset on every full-charge unplug, so on 3+ evenings, before plugging in, read `adb shell dumpsys batterystats dev.marvinbarretto.jimbo` and `adb shell dumpsys batterystats com.google.android.gms` (Play services runs the fences, so their cost lands there), or Settings → Battery → Battery usage for both apps. Then check the `place` collector events, and tune the 100 m radii
