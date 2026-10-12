package dev.marvinbarretto.jimbo.place

import android.content.Context

/** Last named place the geofences reported, and when. Coordinates never land here. */
object PlaceStateStore {
    private const val PREFS = "place_state"
    private const val KEY_PLACE = "place"
    private const val KEY_SINCE = "since"

    fun current(context: Context): Pair<Place, Long?> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val place = Place.values().firstOrNull { it.wire == prefs.getString(KEY_PLACE, null) } ?: Place.OTHER
        val since = if (prefs.contains(KEY_SINCE)) prefs.getLong(KEY_SINCE, 0L) else null
        return place to since
    }

    fun set(context: Context, place: Place, atMillis: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PLACE, place.wire)
            .putLong(KEY_SINCE, atMillis)
            .apply()
    }
}
