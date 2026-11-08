package dev.marvinbarretto.jimbo.walk

import dev.marvinbarretto.jimbo.telemetry.RawEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WalkSessionTest {
    private val market = WalkSpot("market", 51.6560, -0.3960, "story_market")
    private val church = WalkSpot("church", 51.6600, -0.3960, "story_church")

    private class FakeLocation : WalkLocationSource {
        var listener: ((WalkFix) -> Unit)? = null
        var starts = 0
        var stops = 0
        override fun start(onFix: (WalkFix) -> Unit) { starts++; listener = onFix }
        override fun stop() { stops++; listener = null }
        fun emit(fix: WalkFix) = listener?.invoke(fix)
    }

    private class FakePlayback : WalkPlayback {
        val played = mutableListOf<String>()
        var stopped = 0
        override fun play(clip: String) { played += clip }
        override fun stop() { stopped++ }
    }

    private class FakeSink : WalkEventSink {
        val events = mutableListOf<RawEvent>()
        override fun record(event: RawEvent) { events += event }
    }

    private val location = FakeLocation()
    private val playback = FakePlayback()
    private val sink = FakeSink()
    private val session = WalkSession(listOf(market, church), location, playback, sink, clock = { Instant.parse("2026-11-09T10:00:00Z") })

    private fun triggers() = sink.events.filter { it.type == "walk.trigger" }

    @Test fun `start streams location until stop`() {
        assertFalse(session.isRunning)
        session.start()
        assertTrue(session.isRunning)
        assertEquals(1, location.starts)
        assertNotNull(location.listener)

        session.stop()
        assertFalse(session.isRunning)
        assertEquals(1, location.stops)
        assertNull(location.listener)
    }

    @Test fun `starting twice opens one stream`() {
        session.start()
        session.start()
        assertEquals(1, location.starts)
    }

    @Test fun `a fix within 30 m plays that clip and records one event`() {
        session.start()
        location.emit(northOf(market, 12.0))
        assertEquals(listOf("story_market"), playback.played)
        assertEquals(1, triggers().size)
        assertEquals("market", triggers()[0].payload?.get("spot"))
        assertEquals(1, session.triggered)
    }

    @Test fun `a fix outside 30 m plays nothing`() {
        session.start()
        location.emit(northOf(market, 45.0))
        assertTrue(playback.played.isEmpty())
        assertTrue(triggers().isEmpty())
    }

    @Test fun `the same spot plays once per session`() {
        session.start()
        location.emit(northOf(market, 12.0))
        location.emit(northOf(market, 4.0))
        location.emit(northOf(church, 8.0))
        assertEquals(listOf("story_market", "story_church"), playback.played)
        assertEquals(2, triggers().size)
    }

    @Test fun `stop silences the clip and a late fix does nothing`() {
        session.start()
        val late = location.listener!!
        session.stop()
        assertEquals(1, playback.stopped)
        late(northOf(market, 3.0))
        assertTrue(playback.played.isEmpty())
        assertTrue(triggers().isEmpty())
    }

    @Test fun `a new session can play the spot again`() {
        session.start()
        location.emit(northOf(market, 12.0))
        session.stop()
        session.start()
        location.emit(northOf(market, 12.0))
        assertEquals(listOf("story_market", "story_market"), playback.played)
    }

    @Test fun `start and stop are recorded with the walk's tally`() {
        session.start()
        location.emit(northOf(market, 12.0))
        session.stop()
        assertEquals(listOf("walk.start", "walk.trigger", "walk.stop"), sink.events.map { it.type })
        assertEquals(1, sink.events.last().payload?.get("triggered"))
    }
}
