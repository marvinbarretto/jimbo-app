package dev.marvinbarretto.jimbo.place

import android.content.Context
import com.google.android.gms.location.Geofence

/** One open visit to a place: when it started, and whether its dwell has been handled. */
data class PlaceVisit(val sinceMillis: Long, val dwelled: Boolean)

/** What to do with one transition for one place. */
data class VisitDecision(val record: Boolean, val notify: Boolean, val next: PlaceVisit?)

/**
 * Turns Android's geofence transitions into one enter, one dwell and one exit
 * per visit.
 *
 * Android re-sends transitions whenever the fences are registered again, and
 * the app registers them each time its process starts. On 8 Nov that gave five
 * "dwell at home" events between 03:21 and 04:33 with nobody moving, and an
 * exit from the gym and the Moons at 05:31 with nobody having been to either.
 * At the Moons each repeat would have shown another quip, about one every 20
 * minutes. So: a repeated enter or dwell inside an open visit is dropped, and
 * so is an exit from a place with no open visit. A visit older than
 * [STALE_AFTER_MS] counts as closed, so a missed exit can't silence a place for
 * good.
 */
object PlaceVisits {
    const val STALE_AFTER_MS = 12 * 60 * 60 * 1000L

    fun decide(transition: Int, open: PlaceVisit?, nowMillis: Long): VisitDecision {
        val live = open?.takeIf { nowMillis - it.sinceMillis < STALE_AFTER_MS }
        return when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER ->
                if (live != null) VisitDecision(record = false, notify = false, next = live)
                else VisitDecision(record = true, notify = true, next = PlaceVisit(nowMillis, dwelled = false))
            Geofence.GEOFENCE_TRANSITION_DWELL ->
                if (live?.dwelled == true) VisitDecision(record = false, notify = false, next = live)
                else VisitDecision(record = true, notify = true, next = PlaceVisit(live?.sinceMillis ?: nowMillis, dwelled = true))
            Geofence.GEOFENCE_TRANSITION_EXIT ->
                if (open != null) VisitDecision(record = true, notify = false, next = null)
                else VisitDecision(record = false, notify = false, next = null)
            else -> VisitDecision(record = false, notify = false, next = open)
        }
    }

    private const val PREFS = "jimbo_place_visits"

    fun load(context: Context, placeId: String): PlaceVisit? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("$placeId.since")) return null
        return PlaceVisit(prefs.getLong("$placeId.since", 0L), prefs.getBoolean("$placeId.dwelled", false))
    }

    fun save(context: Context, placeId: String, visit: PlaceVisit?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (visit == null) edit.remove("$placeId.since").remove("$placeId.dwelled")
        else edit.putLong("$placeId.since", visit.sinceMillis).putBoolean("$placeId.dwelled", visit.dwelled)
        edit.apply()
    }
}
