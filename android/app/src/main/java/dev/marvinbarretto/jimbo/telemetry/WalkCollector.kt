package dev.marvinbarretto.jimbo.telemetry

import dev.marvinbarretto.jimbo.walk.WalkControl
import dev.marvinbarretto.jimbo.walk.WalkHit
import dev.marvinbarretto.jimbo.walk.WalkMediaSnapshot
import java.time.Instant

/**
 * Walk mode (walk.start / walk.trigger / walk.stop, plus the diagnostics below). Events come from the walk
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

        // Diagnostics (walk.fix / walk.audio / walk.summary, and since walk 2 walk.choice /
        // walk.control / walk.media): enough to explain a
        // walk afterwards, such as a spot that stayed silent. Still never coordinates.

        /** One location fix: the nearest spot not yet played, how far, how sure, the gap since the last fix. */
        fun fix(
            walkId: String, accuracyMetres: Float?, nearestSpot: String?, nearestMetres: Double?,
            gapSeconds: Double?, ignored: Boolean, at: Instant,
        ): RawEvent = RawEvent(
            collector = ID,
            type = "walk.fix",
            ts = at,
            payload = buildMap {
                put("walk", walkId)
                if (accuracyMetres != null) put("accuracy_m", tenths(accuracyMetres.toDouble()))
                if (nearestSpot != null) put("nearest", nearestSpot)
                if (nearestMetres != null) put("nearest_m", tenths(nearestMetres))
                if (gapSeconds != null) put("gap_s", tenths(gapSeconds))
                if (ignored) put("ignored", true)
            },
        )

        /**
         * One audio step (WalkAudio's phases: focus_granted, clip_start, clip_end, focus_abandoned, ...).
         * [kind] on focus_lost says which loss: transient (paused), permanent (stopped) or duck (played on).
         */
        fun audio(walkId: String?, phase: String, clip: String?, at: Instant, kind: String? = null): RawEvent = RawEvent(
            collector = ID,
            type = "walk.audio",
            ts = at,
            payload = buildMap {
                if (walkId != null) put("walk", walkId)
                put("phase", phase)
                if (clip != null) put("clip", clip)
                if (kind != null) put("kind", kind)
            },
        )

        /** The "More" offer after a short clip (WalkChoice): offered, taken, declined or expired, and why it lapsed. */
        fun choice(walkId: String?, choice: String, clip: String, reason: String?, at: Instant): RawEvent = RawEvent(
            collector = ID,
            type = "walk.choice",
            ts = at,
            payload = buildMap {
                if (walkId != null) put("walk", walkId)
                put("choice", choice)
                put("clip", clip)
                if (reason != null) put("reason", reason)
            },
        )

        /** A notification control (pause, resume, back, skip): the clip and where it was; applied false when nothing played. */
        fun control(walkId: String?, control: WalkControl, at: Instant): RawEvent = RawEvent(
            collector = ID,
            type = "walk.control",
            ts = at,
            payload = buildMap {
                if (walkId != null) put("walk", walkId)
                put("action", control.action)
                if (control.clip != null) put("clip", control.clip)
                if (control.positionSeconds != null) put("position_s", control.positionSeconds)
                put("applied", control.applied)
            },
        )

        /** Other apps' media sessions at one point around a clip (WalkMediaWatch), or why they could not be read. */
        fun media(walkId: String?, point: String, clip: String?, snapshot: WalkMediaSnapshot, at: Instant): RawEvent = RawEvent(
            collector = ID,
            type = "walk.media",
            ts = at,
            payload = buildMap {
                if (walkId != null) put("walk", walkId)
                put("point", point)
                if (clip != null) put("clip", clip)
                if (snapshot.error != null) {
                    put("error", snapshot.error)
                } else {
                    put("sessions", snapshot.sessions.map { mapOf("package" to it.pkg, "state" to it.state) })
                }
            },
        )

        /** At stop: how many fixes came, how many were too vague, the longest silence between fixes, battery. */
        fun summary(
            walkId: String, fixes: Int, ignored: Int, maxGapSeconds: Double?,
            batteryStart: Int?, batteryEnd: Int?, at: Instant,
        ): RawEvent = RawEvent(
            collector = ID,
            type = "walk.summary",
            ts = at,
            payload = buildMap {
                put("walk", walkId)
                put("fixes", fixes)
                put("ignored", ignored)
                if (maxGapSeconds != null) put("max_gap_s", tenths(maxGapSeconds))
                if (batteryStart != null) put("battery_start", batteryStart)
                if (batteryEnd != null) put("battery_end", batteryEnd)
            },
        )

        private fun tenths(value: Double): Double = Math.round(value * 10) / 10.0
    }
}
