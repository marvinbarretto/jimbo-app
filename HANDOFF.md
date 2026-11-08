# HANDOFF: jimbo-app's open threads

Open threads only, one line each: `- YYYY-MM-DD · owner: <who> · <what, and the next step> [due YYYY-MM-DD] [JIM-…]`.
Edit your own lines, never rewrite the file; delete a line when it is done (git keeps the history). Live state comes
from `npm run status`; traps go in AGENTS.md (jimbo ADR-0165).
- 2026-11-08 · owner: marvin · Geofence trial (home/gym/moon in local.properties on the M4) is built, not installed: plug the Pixel in, run `scripts/install-phone.sh --live`, then `adb shell dumpsys batterystats --reset`; after a week read `adb shell dumpsys batterystats dev.marvinbarretto.jimbo` and the `place` collector events
