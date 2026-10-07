package dev.marvinbarretto.jimbo.place

import dev.marvinbarretto.jimbo.BuildConfig

/**
 * The geofence set, defined here rather than in a UI. Coordinates are private,
 * so they come from local.properties (`jimbo.place.home` / `jimbo.place.gym`,
 * each `lat,lng[,radiusMetres]`) via BuildConfig. A place left unset is simply
 * not fenced.
 */
data class PlaceGeofence(
    val place: Place,
    val latitude: Double,
    val longitude: Double,
    val radiusMetres: Float,
) {
    val requestId: String get() = place.wire

    companion object {
        const val DEFAULT_RADIUS_M = 150f

        fun configured(): List<PlaceGeofence> = listOfNotNull(
            parse(Place.HOME, BuildConfig.JIMBO_PLACE_HOME),
            parse(Place.GYM, BuildConfig.JIMBO_PLACE_GYM),
        )

        fun parse(place: Place, spec: String): PlaceGeofence? {
            val parts = spec.split(',').map { it.trim() }
            if (parts.size !in 2..3) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lng = parts[1].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            val radius = parts.getOrNull(2)?.toFloatOrNull() ?: DEFAULT_RADIUS_M
            if (radius <= 0f) return null
            return PlaceGeofence(place, lat, lng, radius)
        }

        fun placeForRequestId(id: String): Place? = Place.values().firstOrNull { it.wire == id }
    }
}
