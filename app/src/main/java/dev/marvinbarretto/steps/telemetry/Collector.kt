package dev.marvinbarretto.steps.telemetry

import java.time.Duration
import java.time.Instant

interface Collector {
    val id: String
    val defaultEnabled: Boolean
    val cadence: CollectorCadence

    suspend fun collect(window: TimeWindow): List<RawEvent>
}

sealed interface CollectorCadence {
    data class Periodic(val interval: Duration) : CollectorCadence
    data object Realtime : CollectorCadence
    data class OnBroadcast(val actions: List<String>) : CollectorCadence
}

data class TimeWindow(
    val start: Instant,
    val end: Instant,
    val triggerAction: String? = null
)

data class RawEvent(
    val collector: String,
    val type: String,
    val ts: Instant,
    val tsEnd: Instant? = null,
    val value: Double? = null,
    val unit: String? = null,
    val source: String? = null,
    val payload: Map<String, Any?>? = null,
)
