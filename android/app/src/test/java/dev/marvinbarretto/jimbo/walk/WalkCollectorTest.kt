package dev.marvinbarretto.jimbo.walk

import dev.marvinbarretto.jimbo.telemetry.CollectorCadence
import dev.marvinbarretto.jimbo.telemetry.WalkCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WalkCollectorTest {
    private val spot = WalkSpot("market", 51.6560, -0.3960, "story_market")
    private val at = Instant.parse("2026-11-09T10:00:00Z")

    @Test fun `is the walk collector, fed by the session, on by default`() {
        val collector = WalkCollector()
        assertEquals("walk", collector.id)
        assertEquals(CollectorCadence.Realtime, collector.cadence)
        assertTrue(collector.defaultEnabled)
    }

    @Test fun `a trigger is a walk event with spot id and distance`() {
        val event = WalkCollector.trigger("w1", WalkHit(spot, 12.345), accuracyMetres = 6f, at = at)
        assertEquals("walk", event.collector)
        assertEquals("walk.trigger", event.type)
        assertEquals(at, event.ts)
        assertEquals("market", event.payload?.get("spot"))
        assertEquals(12.3, event.payload?.get("distance_m") as Double, 0.0)
        assertEquals("w1", event.payload?.get("walk"))
    }

    @Test fun `a trigger never carries coordinates`() {
        val event = WalkCollector.trigger("w1", WalkHit(spot, 12.345), accuracyMetres = 6f, at = at)
        val payload = event.payload.orEmpty()
        assertEquals(setOf("spot", "distance_m", "accuracy_m", "walk"), payload.keys)
        assertFalse(payload.keys.any { it.contains("lat") || it.contains("lng") || it.contains("lon") })
        assertFalse(payload.values.any { it == spot.latitude || it == spot.longitude })
        assertEquals(null, event.value)
    }

    @Test fun `start and stop carry counts, never coordinates`() {
        val start = WalkCollector.started("w1", spotCount = 5, at = at)
        val stop = WalkCollector.stopped("w1", triggered = 3, at = at)
        assertEquals("walk.start", start.type)
        assertEquals(mapOf("walk" to "w1", "spots" to 5), start.payload)
        assertEquals("walk.stop", stop.type)
        assertEquals(mapOf("walk" to "w1", "triggered" to 3), stop.payload)
    }
}
