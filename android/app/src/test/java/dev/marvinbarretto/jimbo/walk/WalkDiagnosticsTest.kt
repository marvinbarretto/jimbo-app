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
 * Walk diagnostics: every fix, every audio step and a closing summary, so a
 * walk that goes wrong can be explained afterwards (a spot that stayed silent:
 * passed at 35 m, or fixes too vague, or no fixes at all). Never coordinates.
 */
class WalkDiagnosticsTest {
    private val market = WalkSpot("market", 51.6560, -0.3960, "story_market")
    private val church = WalkSpot("church", 51.6600, -0.3960, "story_church")
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
    private var battery = 80
    private val session = WalkSession(
        listOf(market, church), location, Playback(), sink,
        clock = { t0 }, diagnostics = diagnostics, battery = { battery },
    )

    private fun fixes() = diagnostics.events.filter { it.type == "walk.fix" }

    @Test fun `every fix is recorded with the nearest unplayed spot and its distance`() {
        session.start()
        location.onFix!!(northOf(market, 100.0, accuracyMetres = 8f, at = t0.plusSeconds(3)))
        location.onFix!!(northOf(market, 10.0, accuracyMetres = 6f, at = t0.plusSeconds(6)))
        location.onFix!!(northOf(market, 12.0, accuracyMetres = 6f, at = t0.plusSeconds(9)))
        val f = fixes()
        assertEquals(3, f.size)
        assertEquals("market", f[0].payload?.get("nearest"))
        assertEquals(100.0, f[0].payload?.get("nearest_m") as Double, 0.2)
        assertEquals(8.0, f[0].payload?.get("accuracy_m") as Double, 0.0)
        // Market has played by the third fix, so church is the nearest unplayed spot.
        assertEquals("church", f[2].payload?.get("nearest"))
        assertEquals(3.0, f[1].payload?.get("gap_s") as Double, 0.0)
        assertNull(f[0].payload?.get("gap_s"))
    }

    @Test fun `a fix too vague to trigger is marked ignored`() {
        session.start()
        location.onFix!!(northOf(market, 5.0, accuracyMetres = 60f, at = t0))
        assertEquals(true, fixes().single().payload?.get("ignored"))
        assertEquals(0, session.triggered)
    }

    @Test fun `a fix never carries coordinates`() {
        session.start()
        location.onFix!!(northOf(market, 40.0, at = t0))
        val payload = fixes().single().payload.orEmpty()
        assertFalse(payload.keys.any { it.contains("lat") || it.contains("lng") || it.contains("lon") })
        assertTrue(payload.values.none { it is Double && it > 50.0 && it < 52.0 })
        assertEquals("walk", fixes().single().collector)
    }

    @Test fun `stop records a summary with fix counts, the longest gap and battery`() {
        session.start()
        battery = 80
        location.onFix!!(northOf(market, 100.0, at = t0))
        location.onFix!!(northOf(market, 90.0, at = t0.plusSeconds(3)))
        location.onFix!!(northOf(market, 80.0, accuracyMetres = 70f, at = t0.plusSeconds(20)))
        battery = 74
        session.stop()
        val summary = diagnostics.events.single { it.type == "walk.summary" }.payload!!
        assertEquals(3, summary["fixes"])
        assertEquals(1, summary["ignored"])
        assertEquals(17.0, summary["max_gap_s"] as Double, 0.0)
        assertEquals(80, summary["battery_start"])
        assertEquals(74, summary["battery_end"])
    }

    @Test fun `diagnostics stay out of the walk event stream`() {
        session.start()
        location.onFix!!(northOf(market, 5.0, at = t0))
        session.stop()
        assertEquals(listOf("walk.start", "walk.trigger", "walk.stop"), sink.events.map { it.type })
    }

    @Test fun `audio reports focus, start, end and hand-back`() {
        val steps = mutableListOf<Pair<String, String?>>()
        val focus = object : WalkAudioFocus {
            override fun request(gain: Int, onLoss: () -> Unit) = true
            override fun abandon() = Unit
        }
        var done: (() -> Unit)? = null
        val player = object : WalkClipPlayer {
            override fun play(clip: String, onDone: () -> Unit, onError: () -> Unit) { done = onDone }
            override fun stop() = Unit
        }
        val audio = WalkAudio(focus, player, report = { phase, clip -> steps += phase to clip })
        audio.play("a")
        audio.play("b")
        done!!()
        done!!()
        assertEquals(
            listOf(
                "focus_granted" to "a", "clip_start" to "a", "queued" to "b",
                "clip_end" to "a", "clip_start" to "b", "clip_end" to "b", "focus_abandoned" to null,
            ),
            steps,
        )
    }

    @Test fun `audio reports a refused request, a failed clip and a lost focus`() {
        val steps = mutableListOf<String>()
        var grant = false
        var lose: (() -> Unit)? = null
        val focus = object : WalkAudioFocus {
            override fun request(gain: Int, onLoss: () -> Unit): Boolean { lose = onLoss; return grant }
            override fun abandon() = Unit
        }
        var fail: (() -> Unit)? = null
        val player = object : WalkClipPlayer {
            override fun play(clip: String, onDone: () -> Unit, onError: () -> Unit) { fail = onError }
            override fun stop() = Unit
        }
        val audio = WalkAudio(focus, player, report = { phase, _ -> steps += phase })
        audio.play("a")
        assertEquals(listOf("focus_refused"), steps)
        grant = true
        audio.play("b")
        fail!!()
        audio.play("c")
        lose!!()
        assertEquals(
            listOf(
                "focus_refused", "focus_granted", "clip_start", "clip_error", "focus_abandoned",
                "focus_granted", "clip_start", "focus_lost", "focus_abandoned",
            ),
            steps,
        )
    }

    @Test fun `audio and summary events carry the walk id`() {
        val audio = WalkCollector.audio("w1", "clip_start", "story_market", t0)
        assertEquals("walk.audio", audio.type)
        assertEquals(mapOf("walk" to "w1", "phase" to "clip_start", "clip" to "story_market"), audio.payload)
        val orphan = WalkCollector.audio(null, "focus_abandoned", null, t0)
        assertEquals(mapOf("phase" to "focus_abandoned"), orphan.payload)
    }
}
