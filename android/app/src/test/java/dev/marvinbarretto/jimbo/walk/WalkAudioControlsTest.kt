package dev.marvinbarretto.jimbo.walk

import dev.marvinbarretto.jimbo.telemetry.WalkCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Walk 2 (HW-7849): a brief interruption pauses the clip instead of dropping
 * it, and the walker can pause, go back 10 s or skip from the notification.
 * Walk 1 lost a story 2 s in when Spotify took focus back.
 */
class WalkAudioControlsTest {
    private val log = mutableListOf<String>()
    private val steps = mutableListOf<WalkAudioStep>()
    private val schedule = FakeSchedule()

    private inner class Focus : WalkAudioFocus {
        var onLoss: (() -> Unit)? = null
        var onChange: ((FocusChange) -> Unit)? = null
        override fun request(gain: Int, onLoss: () -> Unit): Boolean {
            log += "request"
            this.onLoss = onLoss
            return true
        }
        override fun requestWithChanges(gain: Int, onLoss: () -> Unit, onChange: (FocusChange) -> Unit): Boolean {
            this.onChange = onChange
            return request(gain, onLoss)
        }
        override fun abandon() { log += "abandon" }
    }

    private inner class Player(private val canPause: Boolean = true) : WalkClipPlayer {
        var done: (() -> Unit)? = null
        var position = 0
        override fun play(clip: String, onDone: () -> Unit, onError: () -> Unit) {
            log += "play:$clip"
            done = onDone
            position = 0
        }
        override fun stop() { log += "stop" }
        override fun pause(): Boolean { if (canPause) log += "pause"; return canPause }
        override fun resume() { log += "resume" }
        override fun seekBy(ms: Int) { log += "seek:$ms"; position = maxOf(0, position + ms) }
        override fun positionMs(): Int = position
    }

    private val focus = Focus()
    private var player = Player()
    private fun audio(p: Player = player) = WalkAudio(focus, p, schedule = schedule, step = { steps += it })

    private fun phases() = steps.map { it.phase }

    @Test fun `a brief focus loss pauses the clip and focus coming back resumes it`() {
        val audio = audio()
        audio.play("a")
        focus.onChange!!(FocusChange.LOSS_TRANSIENT)
        assertEquals(listOf("request", "play:a", "pause"), log)
        focus.onChange!!(FocusChange.GAIN)
        assertEquals(listOf("request", "play:a", "pause", "resume"), log)
        assertEquals("a", audio.nowPlaying)
        val lost = steps.single { it.phase == "focus_lost" }
        assertEquals("transient", lost.kind)
        assertEquals("a", lost.clip)
        assertTrue("focus_regained" in phases())
        assertFalse("focus_abandoned" in phases())
    }

    @Test fun `a ducking request is recorded and the clip plays on`() {
        audio().play("a")
        focus.onChange!!(FocusChange.DUCK)
        assertEquals(listOf("request", "play:a"), log)
        assertEquals("duck", steps.single { it.phase == "focus_lost" }.kind)
    }

    @Test fun `a permanent loss still stops the clip and hands focus back, and says so`() {
        audio().play("a")
        focus.onLoss!!()
        assertEquals(listOf("request", "play:a", "stop", "abandon"), log)
        assertEquals("permanent", steps.single { it.phase == "focus_lost" }.kind)
    }

    @Test fun `a clip that cannot pause is dropped on a brief loss, as before`() {
        val audio = audio(Player(canPause = false))
        audio.play("a")
        focus.onChange!!(FocusChange.LOSS_TRANSIENT)
        assertEquals(listOf("request", "play:a", "stop", "abandon"), log)
        assertNull(audio.nowPlaying)
    }

    @Test fun `focus coming back does not resume a clip the walker paused`() {
        val audio = audio()
        audio.play("a")
        audio.pause()
        focus.onChange!!(FocusChange.LOSS_TRANSIENT)
        focus.onChange!!(FocusChange.GAIN)
        assertEquals(listOf("request", "play:a", "pause"), log)
        assertTrue(audio.isPaused)
    }

    @Test fun `pause keeps focus for a minute, then ends the clip and hands focus back`() {
        val audio = audio()
        audio.play("a")
        val control = audio.pause()
        assertEquals(WalkControl("pause", "a", 0.0, applied = true), control)
        schedule.advance(WalkAudio.PAUSE_HOLD_MS - 1)
        assertFalse("abandon" in log)
        schedule.advance(1)
        assertEquals(listOf("request", "play:a", "pause", "stop", "abandon"), log)
        assertTrue("pause_expired" in phases())
        assertNull(audio.nowPlaying)
    }

    @Test fun `resume within the minute carries on and cancels the hand-back`() {
        val audio = audio()
        audio.play("a")
        audio.pause()
        schedule.advance(30_000)
        assertTrue(audio.resume().applied)
        schedule.advance(WalkAudio.PAUSE_HOLD_MS)
        assertEquals(listOf("request", "play:a", "pause", "resume"), log)
        assertFalse(audio.isPaused)
    }

    @Test fun `back goes 10 s back and records where it was`() {
        val audio = audio()
        audio.play("a")
        player.position = 25_400
        val control = audio.back()
        assertEquals("seek:-10000", log.last())
        assertEquals(WalkControl("back", "a", 25.4, applied = true), control)
    }

    @Test fun `skip ends the clip and hands focus back`() {
        val audio = audio()
        audio.play("a")
        player.position = 7_000
        val control = audio.skip()
        assertEquals(WalkControl("skip", "a", 7.0, applied = true), control)
        assertEquals(listOf("request", "play:a", "stop", "abandon"), log)
        assertEquals(listOf("focus_granted", "clip_start", "clip_skipped", "focus_abandoned"), phases())
        // The skipped clip's own end, arriving late, changes nothing.
        player.done!!()
        assertEquals(listOf("request", "play:a", "stop", "abandon"), log)
    }

    @Test fun `skip moves on to a waiting clip under the same focus`() {
        val audio = audio()
        audio.play("a")
        audio.play("b")
        audio.skip()
        assertEquals(listOf("request", "play:a", "stop", "play:b"), log)
        assertEquals("b", audio.nowPlaying)
    }

    @Test fun `skip cancels a paused clip's hand-back timer`() {
        val audio = audio()
        audio.play("a")
        audio.play("b")
        audio.pause()
        audio.skip()
        schedule.advance(WalkAudio.PAUSE_HOLD_MS)
        assertEquals("b", audio.nowPlaying)
        assertFalse(audio.isPaused)
    }

    @Test fun `controls with nothing playing do nothing and say so`() {
        val audio = audio()
        listOf(audio.pause(), audio.resume(), audio.back(), audio.skip()).forEach {
            assertFalse(it.applied)
            assertNull(it.clip)
        }
        assertTrue(log.isEmpty())
    }

    @Test fun `the walk's plain report still gets every phase`() {
        val plain = mutableListOf<String>()
        val audio = WalkAudio(focus, player, report = { phase, _ -> plain += phase }, schedule = schedule)
        audio.play("a")
        focus.onChange!!(FocusChange.LOSS_TRANSIENT)
        focus.onChange!!(FocusChange.GAIN)
        assertEquals(listOf("focus_granted", "clip_start", "focus_lost", "focus_regained"), plain)
    }

    @Test fun `walk audio and control events carry kind and position`() {
        val t0 = Instant.parse("2026-11-09T10:00:00Z")
        val lost = WalkCollector.audio("w1", "focus_lost", "a", t0, kind = "transient")
        assertEquals(mapOf("walk" to "w1", "phase" to "focus_lost", "clip" to "a", "kind" to "transient"), lost.payload)
        val control = WalkCollector.control("w1", WalkControl("back", "a", 25.4, applied = true), t0)
        assertEquals("walk.control", control.type)
        assertEquals("walk", control.collector)
        assertEquals(
            mapOf("walk" to "w1", "action" to "back", "clip" to "a", "position_s" to 25.4, "applied" to true),
            control.payload,
        )
        val idle = WalkCollector.control("w1", WalkControl("skip", null, null, applied = false), t0)
        assertEquals(mapOf("walk" to "w1", "action" to "skip", "applied" to false), idle.payload)
    }
}
