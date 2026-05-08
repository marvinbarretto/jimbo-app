package dev.marvinbarretto.jimbo.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant

class TelemetryStoreTest {

    @Test
    fun toEntityProducesStableIdForEquivalentPayloadOrdering() {
        val first = RawEvent(
            collector = "health_connect",
            type = "heart_rate_summary",
            ts = Instant.parse("2026-05-07T10:00:00Z"),
            tsEnd = Instant.parse("2026-05-07T12:00:00Z"),
            source = "health_connect_aggregate",
            payload = mapOf(
                "avg" to 70.0,
                "min" to 55.0,
                "max" to 120.0
            )
        )
        val second = first.copy(
            payload = linkedMapOf(
                "max" to 120.0,
                "min" to 55.0,
                "avg" to 70.0
            )
        )

        assertEquals(first.toEntity().id, second.toEntity().id)
    }

    @Test
    fun toEntityChangesIdWhenEventMeaningChanges() {
        val first = RawEvent(
            collector = "health_connect",
            type = "steps",
            ts = Instant.parse("2026-05-07T10:00:00Z"),
            tsEnd = Instant.parse("2026-05-07T12:00:00Z"),
            value = 123.0,
            unit = "count",
            source = "health_connect_aggregate"
        )
        val second = first.copy(value = 456.0)

        assertNotEquals(first.toEntity().id, second.toEntity().id)
    }
}
