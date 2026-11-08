package dev.marvinbarretto.jimbo.telemetry

/**
 * Geofence transitions (place.enter / place.dwell / place.exit). Events come
 * from place/GeofenceReceiver, not from polling; this entry exists so the
 * collector has a setting row and can be switched off like the others.
 * On by default: it adds no sensing of its own, the fences ride on the
 * location the app already reads.
 */
class PlaceCollector : Collector {
    override val id: String = ID
    override val defaultEnabled: Boolean = true
    override val cadence: CollectorCadence = CollectorCadence.Realtime

    override suspend fun collect(window: TimeWindow): List<RawEvent> = emptyList()

    companion object {
        const val ID = "place"
    }
}
