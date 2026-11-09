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
 * Walk 3 (HW-7849): the walk ends after the last spot. Walk 2 ran 28 minutes
 * past the Moon Under Water, skewing the summary's fixes and battery. When the
 * walk's last clip ends (and its More, if taken), the notification offers
 * "End walk?"; untouched, the walk stops itself 10 minutes after that clip
 * ended. `walk.stop` records how it ended, and `walk.summary` how long the
 * walk ran past its last trigger.
 */
class WalkEndingTest {
    private val schedule = FakeSchedule()
    private var finished = false
    private var offerOpen = false
    private val stops = mutableListOf<String>()
    private var changes = 0
    private val ending = WalkEnding(
        schedule = schedule,
        finished = { finished },
        offerOpen = { offerOpen },
        stop = { stops += it },
        changed = { changes++ },
    )

    @Test fun `the last clip ending offers to end the walk`() {
        finished = true
        ending.onStep("clip_start", "story_moon")
        assertFalse(ending.prompting)
        ending.onStep("clip_end", "story_moon")
        ending.onStep("focus_abandoned", null)
        assertTrue(ending.prompting)
        assertTrue(changes > 0)
        assertTrue(stops.isEmpty())
    }

    @Test fun `untouched, the walk stops itself 10 minutes after the last clip`() {
        finished = true
        ending.onStep("focus_abandoned", null)
        schedule.advance(WalkEnding.AUTO_STOP_MS - 1)
        assertTrue(stops.isEmpty())
        schedule.advance(1)
        assertEquals(listOf(WalkEnding.AUTO_AFTER_LAST), stops)
        assertEquals("auto_after_last", WalkEnding.AUTO_AFTER_LAST)
    }

    @Test fun `End walk stops the walk from the prompt`() {
        finished = true
        ending.onStep("focus_abandoned", null)
        ending.end()
        assertEquals(listOf("end_prompt"), stops)
        assertFalse(ending.prompting)
        schedule.advance(WalkEnding.AUTO_STOP_MS)
        assertEquals(1, stops.size)
    }

    @Test fun `the clock starts when the last clip ends, held for a More offer`() {
        finished = true
        offerOpen = true
        ending.onStep("clip_end", "story_moon")
        ending.onStep("focus_held", "story_moon")
        // During the More offer, More and Skip keep the notification.
        assertFalse(ending.prompting)
        schedule.advance(15_000)
        ending.onStep("focus_abandoned", null)
        offerOpen = false
        assertTrue(ending.prompting)
        schedule.advance(WalkEnding.AUTO_STOP_MS - 15_000)
        assertEquals(listOf("auto_after_last"), stops)
    }

    @Test fun `taking More restarts the clock from the end of the longer clip`() {
        finished = true
        offerOpen = true
        ending.onStep("focus_held", "story_moon")
        schedule.advance(5_000)
        offerOpen = false
        ending.onStep("clip_start", "story_moon-more")
        assertFalse(ending.prompting)
        schedule.advance(100_000)
        ending.onStep("clip_end", "story_moon-more")
        ending.onStep("focus_abandoned", null)
        assertTrue(ending.prompting)
        schedule.advance(WalkEnding.AUTO_STOP_MS - 1)
        assertTrue(stops.isEmpty())
        schedule.advance(1)
        assertEquals(listOf("auto_after_last"), stops)
    }

    @Test fun `a refused last clip still offers to end the walk`() {
        finished = true
        ending.onStep("focus_refused", "story_moon")
        assertTrue(ending.prompting)
    }

    @Test fun `before the last spot nothing is offered`() {
        ending.onStep("clip_end", "story_market")
        ending.onStep("focus_abandoned", null)
        assertFalse(ending.prompting)
        schedule.advance(WalkEnding.AUTO_STOP_MS * 2)
        assertTrue(stops.isEmpty())
    }

    @Test fun `cancelled when the walk stops another way`() {
        finished = true
        ending.onStep("focus_abandoned", null)
        ending.cancel()
        assertFalse(ending.prompting)
        // The walk's own stop hands focus back; that must not arm it again.
        ending.onStep("focus_abandoned", null)
        schedule.advance(WalkEnding.AUTO_STOP_MS)
        assertTrue(stops.isEmpty())
        assertEquals(0, schedule.pending)
    }

    // The session decides when the walk has reached its end.

    private val first = WalkSpot("first", 51.6560, -0.3960, "story_first")
    private val second = WalkSpot("second", 51.6580, -0.3960, "story_second")
    private val last = WalkSpot("last", 51.6600, -0.3960, "story_last")
    private val t0 = Instant.parse("2026-11-09T10:00:00Z")
    private var now = t0

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
        listOf(first, second, last), location, Playback(), sink,
        clock = { now }, diagnostics = diagnostics,
    )

    @Test fun `the walk is finished when every spot has fired`() {
        session.start()
        assertFalse(session.finished)
        location.onFix!!(northOf(first, 10.0, at = t0))
        location.onFix!!(northOf(second, 10.0, at = t0))
        assertFalse(session.finished)
        location.onFix!!(northOf(last, 10.0, at = t0))
        assertTrue(session.finished)
    }

    @Test fun `the walk is finished when its last spot fires, even with one skipped`() {
        // Walk 2 skipped Trinity and still ended at the Moon.
        session.start()
        location.onFix!!(northOf(first, 10.0, at = t0))
        location.onFix!!(northOf(last, 10.0, at = t0))
        assertTrue(session.finished)
    }

    @Test fun `a stopped walk is not finished`() {
        session.start()
        location.onFix!!(northOf(last, 10.0, at = t0))
        session.stop()
        assertFalse(session.finished)
    }

    @Test fun `walk stop records how the walk ended`() {
        session.start()
        session.stop(WalkEnding.END_PROMPT)
        assertEquals("end_prompt", sink.events.last().payload?.get("reason"))
        session.start()
        session.stop()
        assertEquals("user", sink.events.last().payload?.get("reason"))
        assertEquals("walk.stop", sink.events.last().type)
    }

    @Test fun `the summary says how long the walk ran past its last trigger`() {
        session.start()
        location.onFix!!(northOf(first, 10.0, at = t0.plusSeconds(60)))
        location.onFix!!(northOf(last, 10.0, at = t0.plusSeconds(600)))
        now = t0.plusSeconds(600 + 1_680)
        session.stop(WalkEnding.AUTO_AFTER_LAST)
        val summary = diagnostics.events.single { it.type == "walk.summary" }.payload!!
        assertEquals(1_680.0, summary["seconds_after_last_trigger"] as Double, 0.0)
    }

    @Test fun `a walk with no trigger has no time after its last`() {
        session.start()
        session.stop()
        val summary = diagnostics.events.single { it.type == "walk.summary" }.payload!!
        assertNull(summary["seconds_after_last_trigger"])
    }

    @Test fun `stop and summary events carry the reason and the time past the last trigger`() {
        val stop = WalkCollector.stopped("w1", 5, t0, reason = "auto_after_last")
        assertEquals(mapOf("walk" to "w1", "triggered" to 5, "reason" to "auto_after_last"), stop.payload)
        val summary = WalkCollector.summary("w1", 969, 0, 6.2, 81, 75, t0, secondsAfterLastTrigger = 1_683.04)
        assertEquals(1_683.0, summary.payload?.get("seconds_after_last_trigger"))
        assertEquals(listOf("user", "end_prompt", "auto_after_last"), WalkEnding.REASONS)
    }
}
