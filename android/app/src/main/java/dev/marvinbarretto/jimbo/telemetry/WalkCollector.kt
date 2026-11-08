package dev.marvinbarretto.jimbo.telemetry

import dev.marvinbarretto.jimbo.walk.WalkHit
import java.time.Instant

/**
 * Walk mode (walk.start / walk.trigger / walk.stop). Events come from the walk
 * session (walk/WalkSessionService), not from polling; this entry exists so the
 * collector has a setting row and can be switched off like the others. A
 * trigger carries the spot id and distance, never coordinates, as
 * PlaceCollector carries the place id. On by default: it senses nothing unless
 * a walk is started by hand.
 */
class WalkCollector : Collector {
    override val id: String = ID
    override val defaultEnabled: Boolean = true
    override val cadence: CollectorCadence = CollectorCadence.Realtime

    override suspend fun collect(window: TimeWindow): List<RawEvent> = emptyList()

    companion object {
        const val ID = "walk"

        /** A spot reached: its id, how far away (0.1 m), how sure the fix was, and which walk. */
        fun trigger(walkId: String, hit: WalkHit, accuracyMetres: Float?, at: Instant): RawEvent = RawEvent(
            collector = ID,
            type = "walk.trigger",
            ts = at,
            payload = buildMap {
                put("spot", hit.spot.id)
                put("distance_m", tenths(hit.distanceMetres))
                if (accuracyMetres != null) put("accuracy_m", tenths(accuracyMetres.toDouble()))
                put("walk", walkId)
            },
        )

        fun started(walkId: String, spotCount: Int, at: Instant): RawEvent = RawEvent(
            collector = ID,
            type = "walk.start",
            ts = at,
            payload = mapOf("walk" to walkId, "spots" to spotCount),
        )

        fun stopped(walkId: String, triggered: Int, at: Instant): RawEvent = RawEvent(
            collector = ID,
            type = "walk.stop",
            ts = at,
            payload = mapOf("walk" to walkId, "triggered" to triggered),
        )

        private fun tenths(value: Double): Double = Math.round(value * 10) / 10.0
    }
}
