package dev.marvinbarretto.jimbo.telemetry

import dev.marvinbarretto.jimbo.proposals.Verdict
import java.time.Instant

/**
 * Jimbo proposals (jimbo.poll / jimbo.notified / jimbo.verdict). Events come from the poller and the verdict
 * worker (proposals/), not from polling; this entry gives the collector a setting row. Never the proposal text.
 */
class JimboCollector : Collector {
    override val id: String = ID
    override val defaultEnabled: Boolean = true
    override val cadence: CollectorCadence = CollectorCadence.Realtime
    override suspend fun collect(window: TimeWindow): List<RawEvent> = emptyList()

    companion object {
        const val ID = "jimbo"
        private const val SOURCE = "jimbo_proposals"

        /** A poll: how many came back, and the HTTP status, or the error when there was none. */
        fun poll(count: Int, httpStatus: Int?, error: String?, at: Instant) = RawEvent(
            collector = ID, type = "jimbo.poll", ts = at, value = count.toDouble(), unit = "entries", source = SOURCE,
            payload = buildMap { put("count", count); httpStatus?.let { put("http_status", it) }; error?.let { put("error", it) } },
        )

        fun notified(proposalId: String, summary: Boolean, at: Instant) = RawEvent(
            collector = ID, type = "jimbo.notified", ts = at, source = SOURCE,
            payload = mapOf("proposal" to proposalId, "summary" to summary),
        )

        fun verdict(proposalId: String, verdict: Verdict, ok: Boolean, hasNote: Boolean, httpStatus: Int?, at: Instant) = RawEvent(
            collector = ID, type = "jimbo.verdict", ts = at, source = SOURCE,
            payload = buildMap {
                put("proposal", proposalId); put("verdict", verdict.wire); put("ok", ok); put("has_note", hasNote)
                httpStatus?.let { put("http_status", it) }
            },
        )
    }
}
