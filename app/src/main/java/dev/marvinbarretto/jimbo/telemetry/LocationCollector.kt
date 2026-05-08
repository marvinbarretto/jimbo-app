package dev.marvinbarretto.jimbo.telemetry

class LocationCollector : Collector {
    override val id: String = "location"
    override val defaultEnabled: Boolean = false
    override val cadence: CollectorCadence = CollectorCadence.Realtime

    // Events come from LocationUpdateReceiver via FusedLocationProviderClient PendingIntent.
    // Cadence is adaptive: 5min when moving (WALKING/RUNNING/etc), 30min when STILL.
    // Requires ACCESS_FINE_LOCATION + ACCESS_BACKGROUND_LOCATION — Android 11+ enforces
    // these as two separate permission grants.
    override suspend fun collect(window: TimeWindow): List<RawEvent> = emptyList()
}
