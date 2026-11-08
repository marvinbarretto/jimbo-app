package dev.marvinbarretto.jimbo.walk

import dev.marvinbarretto.jimbo.BuildConfig

/**
 * A walk spot: a point a story starts at. Coordinates are private, so the list
 * comes from local.properties via BuildConfig, never from git:
 *
 * `jimbo.walk.spots` — `id|lat,lng|clip` entries joined by `;`, the same
 * separators as `jimbo.places` (place/PlaceGeofence.kt). `clip` names the
 * audio: a file of that name, with or without its extension, in the app's
 * external files dir under `walk/`. A clip with no such file is a placeholder,
 * spoken aloud (AndroidWalkClipPlayer).
 */
data class WalkSpot(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val clip: String,
) {
    companion object {
        private val ID = Regex("^[a-z0-9_-]{1,32}$")
        private val CLIP = Regex("^[A-Za-z0-9._-]{1,64}$")

        fun configured(): List<WalkSpot> = parseList(BuildConfig.JIMBO_WALK_SPOTS)

        /** A malformed entry is skipped, never guessed at; a repeated id keeps the first. */
        fun parseList(spec: String): List<WalkSpot> = spec.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val parts = entry.split('|').map { it.trim() }
                if (parts.size != 3) return@mapNotNull null
                val id = parts[0].lowercase()
                if (!ID.matches(id)) return@mapNotNull null
                val coords = parts[1].split(',').map { it.trim() }
                if (coords.size != 2) return@mapNotNull null
                val lat = coords[0].toDoubleOrNull() ?: return@mapNotNull null
                val lng = coords[1].toDoubleOrNull() ?: return@mapNotNull null
                if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@mapNotNull null
                val clip = parts[2]
                if (!CLIP.matches(clip)) return@mapNotNull null
                WalkSpot(id, lat, lng, clip)
            }
            .distinctBy { it.id }
    }
}
