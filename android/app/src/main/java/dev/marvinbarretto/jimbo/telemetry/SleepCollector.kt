package dev.marvinbarretto.jimbo.telemetry

import com.google.android.gms.location.SleepSegmentEvent

/**
 * Google Sleep API readings. Events come from SleepReceiver, not polling;
 * this entry gives the collector a setting row so it can be switched off.
 */
class SleepCollector : Collector {
    override val id: String = ID
    override val defaultEnabled: Boolean = true
    override val cadence: CollectorCadence = CollectorCadence.Realtime
    override suspend fun collect(window: TimeWindow): List<RawEvent> = emptyList()

    companion object {
        const val ID = "sleep"
    }
}

/** The segment's status, spelled for the API. */
internal fun sleepStatusName(status: Int): String = when (status) {
    SleepSegmentEvent.STATUS_SUCCESSFUL -> "successful"
    SleepSegmentEvent.STATUS_MISSING_DATA -> "missing_data"
    SleepSegmentEvent.STATUS_NOT_DETECTED -> "not_detected"
    else -> "unknown_$status"
}
