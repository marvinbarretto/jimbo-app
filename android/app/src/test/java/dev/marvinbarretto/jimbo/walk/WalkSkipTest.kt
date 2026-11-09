package dev.marvinbarretto.jimbo.walk

import dev.marvinbarretto.jimbo.telemetry.RawEvent
import dev.marvinbarretto.jimbo.telemetry.WalkCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Walk 3 (HW-7849): a skipped spot leaves evidence. In walk 2 Trinity
 * Methodist never fired, and `walk.fix` could not say how close he came:
 * once spot 1 fired, the Red Lion was always the nearest unfired spot. Now
 * each fix also carries the next spot in walk order and its distance, and a
 * spot firing while an earlier one is unfired records `walk.skip`.
 */
class WalkSkipTest {
    // Walk order is the configured order. Each spot is about 220 m north of the last.
    private val first = WalkSpot("first", 51.6560, -0.3960, "story_first")
    private val second = WalkSpot("second", 51.6580, -0.3960, "story_second")
    private val third = WalkSpot("third", 51.6600, -0.3960, "story_third")
    private val t0 = Instant.parse("2026-11-09T10:00:00Z")

    private class Sink : WalkEventSink {
        val events = mutableListOf<RawEvent>()
        override fun record(event: RawEvent) { events += event }
    }

    private class Location : WalkLocationSource {
        var onFix: ((WalkFix) -> Unit)? = null
        override fun start(onFix: (WalkFix) -> Unit) { this.onFix = onFix }
        override fun stop() { onFix = null }
    }

    private class Playback : WalkPlayback {
        override fun play(clip: String) = Unit
        override fun stop() = Unit
    }

    private val location = Location()
    private val sink = Sink()
    private val diagnostics = Sink()
    private val session = WalkSession(
        listOf(first, second, third), location, Playback(), sink,
        clock = { t0 }, diagnostics = diagnostics,
    )

    private fun fixes() = diagnostics.events.filter { it.type == "walk.fix" }
    private fun skips() = diagnostics.events.filter { it.type == "walk.skip" }

    @Test fun `a fix carries the next spot in walk order and its distance`() {
        session.start()
        location.onFix!!(northOf(first, 10.0, at = t0))
        // First has fired: the next fix's next spot is second, whichever is nearest.
        location.onFix!!(northOf(third, 5.0, accuracyMetres = 70f, at = t0.plusSeconds(3)))
        val f = fixes()
        assertEquals("first", f[0].payload?.get("next"))
        assertEquals(10.0, f[0].payload?.get("next_m") as Double, 0.2)
        assertEquals("third", f[1].payload?.get("nearest"))
        assertEquals("second", f[1].payload?.get("next"))
        assertEquals(227.4, f[1].payload?.get("next_m") as Double, 0.2)
    }

    @Test fun `once every spot has fired a fix names no next spot`() {
        val one = WalkSession(listOf(first), location, Playback(), sink, clock = { t0 }, diagnostics = diagnostics)
        one.start()
        location.onFix!!(northOf(first, 5.0, at = t0))
        location.onFix!!(northOf(first, 50.0, at = t0.plusSeconds(3)))
        val last = fixes().last().payload!!
        assertNull(last["next"])
        assertNull(last["next_m"])
    }

    @Test fun `a spot firing before an earlier one records that the earlier one was skipped`() {
        session.start()
        location.onFix!!(northOf(first, 10.0, at = t0))
        location.onFix!!(northOf(third, 10.0, at = t0.plusSeconds(60)))
        val skip = skips().single()
        assertEquals("walk", skip.collector)
        assertEquals(t0.plusSeconds(60), skip.ts)
        assertEquals(mapOf("walk" to session.currentWalkId, "spot" to "second"), skip.payload)
    }

    @Test fun `each skipped spot is recorded once`() {
        session.start()
        location.onFix!!(northOf(third, 10.0, at = t0))
        assertEquals(listOf("first", "second"), skips().map { it.payload?.get("spot") })
        // Coming back to first fires it, late; no new skip, and none repeated.
        location.onFix!!(northOf(first, 10.0, at = t0.plusSeconds(60)))
        location.onFix!!(northOf(second, 10.0, at = t0.plusSeconds(120)))
        assertEquals(2, skips().size)
    }

    @Test fun `spots fired in order record no skip`() {
        session.start()
        location.onFix!!(northOf(first, 10.0, at = t0))
        location.onFix!!(northOf(second, 10.0, at = t0.plusSeconds(60)))
        location.onFix!!(northOf(third, 10.0, at = t0.plusSeconds(120)))
        assertTrue(skips().isEmpty())
    }

    @Test fun `two spots firing on one fix do not skip each other`() {
        val near = WalkSpot("near", first.latitude + 0.0001, first.longitude, "story_near")
        val pair = WalkSession(listOf(first, near), location, Playback(), sink, clock = { t0 }, diagnostics = diagnostics)
        pair.start()
        // Nearer to `near` (second in walk order): both fire on this fix, near first.
        location.onFix!!(northOf(first, 10.0, at = t0))
        assertEquals(2, pair.triggered)
        assertTrue(skips().isEmpty())
    }

    @Test fun `skips stay out of the walk's own stream`() {
        session.start()
        location.onFix!!(northOf(third, 10.0, at = t0))
        assertEquals(listOf("walk.start", "walk.trigger"), sink.events.map { it.type })
    }

    @Test fun `fix and skip events name spots and distances, never coordinates`() {
        val fix = WalkCollector.fix("w1", 6f, "third", 12.34, 3.0, false, t0, nextSpot = "second", nextMetres = 217.44)
        assertEquals(
            mapOf(
                "walk" to "w1", "accuracy_m" to 6.0, "nearest" to "third", "nearest_m" to 12.3,
                "gap_s" to 3.0, "next" to "second", "next_m" to 217.4,
            ),
            fix.payload,
        )
        val skip = WalkCollector.skip("w1", "second", t0)
        assertEquals("walk.skip", skip.type)
        assertEquals(mapOf("walk" to "w1", "spot" to "second"), skip.payload)
        val keys = fix.payload!!.keys + skip.payload!!.keys
        assertFalse(keys.any { it.contains("lat") || it.contains("lng") || it.contains("lon") })
    }
}
