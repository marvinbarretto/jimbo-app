package dev.marvinbarretto.jimbo.telemetry

class NotificationCollector : Collector {
    override val id: String = "notifications"
    override val defaultEnabled: Boolean = false
    override val cadence: CollectorCadence = CollectorCadence.Realtime

    // TelemetryStore still polls Realtime collectors — harmless, but could be filtered by cadence later
    override suspend fun collect(window: TimeWindow): List<RawEvent> = emptyList()
}
