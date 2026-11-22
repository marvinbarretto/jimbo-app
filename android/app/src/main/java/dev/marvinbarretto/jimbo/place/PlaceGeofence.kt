package dev.marvinbarretto.jimbo.place

import dev.marvinbarretto.jimbo.BuildConfig

/**
 * A geofence. Since 22 Nov 2026 every fence comes from Jimbo (RemoteFences): the saved places, their circles,
 * roles (home, gym) and actions, all set on the dashboard's /places. The settings-file list below is a fallback
 * only, used until Jimbo's pick names a Home or Gym place; on the M4 it is empty, kept commented for reference.
 *
 * The settings-file format, for that fallback. Coordinates are private, so they come from local.properties via
 * BuildConfig, never from git:
 *
 * - `jimbo.places` — the list, `id|lat,lng[,radiusMetres]|label|quipList`
 *   joined by `;`. An entry with a label notifies once you have stayed there a
 *   few minutes (DWELL); one without only records its transitions. With a
 *   quipList (a Jimbo list id) the notification shows an unshown line from that
 *   list instead of the plain text (PlaceQuipWorker). Ids `home` and `gym`
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
    val quipList: String? = null,
    /** The place's name, for a fence from Jimbo (RemoteFences); it rides the telemetry event. */
    val name: String? = null,
    /** Home or Gym when Jimbo gives the place that role: what the /m shell is told. */
    val role: Place? = null,
    /** What runs on a visit, from Jimbo. A configured fence gets the same thing from [configuredActions]. */
    val remoteActions: List<PlaceAction>? = null,
) {
    val requestId: String get() = id
    val place: Place get() = role ?: placeForRequestId(id) ?: Place.OTHER

    /** What a visit here does: Jimbo's actions, or for a settings-file fence, what that fence always did. */
    val actions: List<PlaceAction> get() = remoteActions ?: configuredActions()

    /** The name a notification uses: the place's own, else the settings-file label, else its id. */
    val displayName: String get() = name ?: label ?: id

    /**
     * The settings-file fences' behaviour as actions, so one path runs both (and the fold into Jimbo changes
     * nothing he sees): the gym offers a session on arrival; a labelled place notifies once you have stayed,
     * with a line from its quip list when it has one; an unlabelled one only records.
     */
    private fun configuredActions(): List<PlaceAction> = when {
        place == Place.GYM -> listOf(PlaceAction(PlaceAction.When.ARRIVE, PlaceAction.Do.GYM_OFFER))
        label == null -> emptyList()
        quipList != null -> listOf(PlaceAction(PlaceAction.When.STAY, PlaceAction.Do.QUIP, title = "At $label", list = quipList))
        else -> listOf(PlaceAction(PlaceAction.When.STAY, PlaceAction.Do.NOTIFY, title = "At $label"))
    }

    companion object {
        const val DEFAULT_RADIUS_M = 150f
        private val ID = Regex("^[a-z0-9_-]{1,32}$")
        private val LIST_ID = Regex("^[a-z][a-z0-9-]{0,39}$")

        fun configured(): List<PlaceGeofence> =
            fromConfig(BuildConfig.JIMBO_PLACE_HOME, BuildConfig.JIMBO_PLACE_GYM, BuildConfig.JIMBO_PLACES)

        /** What the phone fences: the configured places, then the nearest saved places from Jimbo (RemoteFences). */
        fun active(context: android.content.Context): List<PlaceGeofence> =
            RemoteFences.merge(configured(), RemoteFences.load(context)?.fences.orEmpty())

        fun isValidId(id: String): Boolean = ID.matches(id)

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
                if (parts.size !in 2..4) return@mapNotNull null
                val id = parts[0].lowercase()
                if (!ID.matches(id)) return@mapNotNull null
                val (lat, lng, r) = parseCoords(parts[1]) ?: return@mapNotNull null
                val quipList = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
                if (quipList != null && !LIST_ID.matches(quipList)) return@mapNotNull null
                PlaceGeofence(id, lat, lng, r, parts.getOrNull(2)?.takeIf { it.isNotEmpty() }, quipList)
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
