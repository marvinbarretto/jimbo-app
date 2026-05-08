package dev.marvinbarretto.jimbo.telemetry

class MediaCollector : Collector {
    override val id: String = "media"
    override val defaultEnabled: Boolean = false
    override val cadence: CollectorCadence = CollectorCadence.Realtime

    // Events come from NotificationCollectorService via MediaSessionManager.
    // Requires notification listener permission to be granted — without it
    // the service never binds and no sessions are captured.
    override suspend fun collect(window: TimeWindow): List<RawEvent> = emptyList()
}
