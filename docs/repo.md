---
# ── REPO MANIFEST — the HOW, for this codebase. ──
repo: jimbo-app
project: jimbo
role: Android telemetry shell — native Kotlin collectors feeding the Jimbo API, with the UI hosted in a Capacitor WebView.

# ── Judgment (hand-written; code can't infer these) ──
entry_points: CLAUDE.md, then docs/capacitor-migration-handoff.md, and android/app/src/main/java/dev/marvinbarretto/jimbo/ (collectors, plugins, SyncWorker).
autonomy_level: propose            # ships to a physical device — human in the loop

# ── Pointer ──
conventions: ./CLAUDE.md

# ── Provenance (written by the sync bot, not by hand) ──
synced_at: null
---

## Footguns
- The WebView loads the **dashboard's** phone-first Angular `/m` shell, not a UI
  in this repo — a screen change usually belongs in `dashboard`
  (`dashboard/docs/architecture/mobile-shell.md`).
- `android/` is the only Gradle build that ships. The legacy Kotlin/Compose
  `app/` module was deleted; docs predating that carry supersession notes.
- Native↔web contracts live in `plugins/<name>/` TypeScript definitions shared
  with the gym repo — changing one side without the other breaks the bridge
  silently at runtime, not at build time.
- Auth crosses the bridge as `X-API-Key` via `AuthPlugin`; the edge serves `/m`
  uncookied, so cookie-based assumptions from the desktop dashboard don't hold.
