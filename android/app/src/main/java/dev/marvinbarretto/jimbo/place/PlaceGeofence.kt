package dev.marvinbarretto.jimbo.place

import dev.marvinbarretto.jimbo.BuildConfig

/**
 * The geofence set, defined here rather than in a UI. Coordinates are private,
 * so they come from local.properties via BuildConfig, never from git:
 *
 * - `jimbo.places` — the list, `id|lat,lng[,radiusMetres]|label` joined by `;`.
 *   An entry with a label notifies once you have stayed there a few minutes
 *   (DWELL); one without only records its transitions. Ids `home` and `gym`
 *   are the named places the /m shell knows; any other id is reported as
 *   [Place.OTHER] to the shell but keeps its own id in telemetry.
 * - `jimbo.place.home` / `jimbo.place.gym` — the older single-place keys,
 *   `lat,lng[,radiusMetres]`. Still read; an entry in the list wins.
 *
 * A place left unset is simply not fenced.
 */
data class PlaceGeofence(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMetres: Float,
    val label: String? = null,
) {
    val requestId: String get() = id
    val place: Place get() = placeForRequestId(id) ?: Place.OTHER

    /** A labelled place says so once you have stayed; the gym keeps its own arrival offer. */
    val notifiesOnDwell: Boolean get() = label != null && place != Place.GYM

    companion object {
        const val DEFAULT_RADIUS_M = 150f
        private val ID = Regex("^[a-z0-9_-]{1,32}$")

        fun configured(): List<PlaceGeofence> =
            fromConfig(BuildConfig.JIMBO_PLACE_HOME, BuildConfig.JIMBO_PLACE_GYM, BuildConfig.JIMBO_PLACES)

        fun fromConfig(home: String, gym: String, places: String): List<PlaceGeofence> {
            val listed = parseList(places)
            val legacy = listOfNotNull(parse(Place.HOME, home), parse(Place.GYM, gym))
                .filter { old -> listed.none { it.id == old.id } }
            return legacy + listed
        }

        /** One legacy `lat,lng[,radius]` spec for a named place. */
        fun parse(place: Place, spec: String): PlaceGeofence? =
            parseCoords(spec)?.let { (lat, lng, r) -> PlaceGeofence(place.wire, lat, lng, r) }

        /** The `jimbo.places` list. A malformed entry is skipped, never guessed at. */
        fun parseList(spec: String): List<PlaceGeofence> = spec.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val parts = entry.split('|').map { it.trim() }
                if (parts.size !in 2..3) return@mapNotNull null
                val id = parts[0].lowercase()
                if (!ID.matches(id)) return@mapNotNull null
                val (lat, lng, r) = parseCoords(parts[1]) ?: return@mapNotNull null
                PlaceGeofence(id, lat, lng, r, parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
            }
            .distinctBy { it.id }

        private fun parseCoords(spec: String): Triple<Double, Double, Float>? {
            val parts = spec.split(',').map { it.trim() }
            if (parts.size !in 2..3) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lng = parts[1].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            val radius = parts.getOrNull(2)?.let { it.toFloatOrNull() ?: return null } ?: DEFAULT_RADIUS_M
            if (radius <= 0f) return null
            return Triple(lat, lng, radius)
        }

        fun placeForRequestId(id: String): Place? = Place.values().firstOrNull { it.wire == id }
    }
}
