package dev.marvinbarretto.jimbo.place

/** Where a geofence reading sat relative to its circle, in metres: the only spatial fact a place event carries. */
object FenceReading {
    fun distanceFromCentre(lat: Double, lng: Double, fence: PlaceGeofence): Float =
        RemoteFences.metresBetween(lat, lng, fence.latitude, fence.longitude).toFloat()
}
