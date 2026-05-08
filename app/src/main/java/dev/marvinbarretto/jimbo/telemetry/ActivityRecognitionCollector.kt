package dev.marvinbarretto.jimbo.telemetry

class ActivityRecognitionCollector : Collector {
    override val id: String = "activity"
    override val defaultEnabled: Boolean = false
    override val cadence: CollectorCadence = CollectorCadence.Realtime

    // Events come from ActivityTransitionReceiver via PendingIntent, not from polling.
    // Phone activity type is a useful proxy for user activity but not a perfect signal —
    // e.g. IN_VEHICLE while steps are zero could mean driving or being a passenger.
    override suspend fun collect(window: TimeWindow): List<RawEvent> = emptyList()
}
