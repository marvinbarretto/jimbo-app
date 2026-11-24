package dev.marvinbarretto.jimbo.place

/** Where a geofence reading sat relative to its circle, in metres: the only spatial fact a place event carries. */
object FenceReading {
    fun distanceFromCentre(lat: Double, lng: Double, fence: PlaceGeofence): Float =
        RemoteFences.metresBetween(lat, lng, fence.latitude, fence.longitude).toFloat()

    /**
     * A fence firing is only a hint: ENTER and DWELL count when the reading that fired them is also inside the circle.
     * Play services fires well outside small circles (Pret, 30 m, fired from 71 m at 18 m accuracy; 40 m fences at
     * 47-63 m; every visit he marked right had the reading within 0.53 of the radius). No reading means we cannot
     * judge, so it passes. See jimbo docs/checks/2026-11-24-fence-overlap-false-visits.md.
     */
    fun inside(lat: Double?, lng: Double?, fence: PlaceGeofence): Boolean =
        lat == null || lng == null || distanceFromCentre(lat, lng, fence) <= fence.radiusMetres

    /**
     * What a transition may act on, given whether its reading was inside the circle and whether an earlier ENTER was held
     * back for being outside. A real arrival at the door can read just outside, and Android sends ENTER once, so the
     * held ARRIVE runs at the first DWELL that reads inside. Exit is never gated and clears the hold.
     */
    data class Gate(val arrive: Boolean, val stay: Boolean, val leave: Boolean, val heldNext: Boolean)

    fun gate(transition: Int, inside: Boolean, held: Boolean): Gate = when (transition) {
        com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_ENTER ->
            if (inside) Gate(arrive = true, stay = false, leave = false, heldNext = false)
            else Gate(arrive = false, stay = false, leave = false, heldNext = true)
        com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_DWELL ->
            if (inside) Gate(arrive = held, stay = true, leave = false, heldNext = false)
            else Gate(arrive = false, stay = false, leave = false, heldNext = held)
        com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_EXIT ->
            Gate(arrive = false, stay = false, leave = true, heldNext = false)
        else -> Gate(false, false, false, held)
    }
}
