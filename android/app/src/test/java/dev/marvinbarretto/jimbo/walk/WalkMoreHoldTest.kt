package dev.marvinbarretto.jimbo.walk

import dev.marvinbarretto.jimbo.telemetry.WalkCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Walk 3 (HW-7849): focus is kept through the More offer. Walk 2 handed focus
 * back the moment a short clip ended, so Spotify resumed and paused again
 * when More was tapped 5–12 s later, three times out of three. Now a clip
 * with a longer telling keeps its transient focus, in silence, for up to
 * 15 s: More hands it straight to the longer clip, Skip or the 15 s hand it
 * back. After that, More asks for focus again as before.
 */
class WalkMoreHoldTest {
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

    private inner class Player : WalkClipPlayer {
        var done: (() -> Unit)? = null
        var error: (() -> Unit)? = null
        override fun play(clip: String, onDone: () -> Unit, onError: () -> Unit) {
            log += "play:$clip"
            done = onDone
            error = onError
        }
        override fun stop() { log += "stop" }
        override fun pause(): Boolean { log += "pause"; return true }
        override fun resume() { log += "resume" }
    }

    private val focus = Focus()
    private val player = Player()
    private val withMore = setOf("story_market")
    private val audio = WalkAudio(
        focus, player, schedule = schedule, step = { steps += it },
        holdAfter = { it in withMore },
    )

    private fun phases() = steps.map { it.phase }
    private fun released() = steps.filter { it.phase == "hold_released" }

    @Test fun `a clip with a longer telling keeps focus when it ends`() {
        audio.play("story_market")
        player.done!!()
        assertEquals(listOf("request", "play:story_market"), log)
        assertTrue(audio.isHolding)
        assertNull(audio.nowPlaying)
        val held = steps.single { it.phase == "focus_held" }
        assertEquals("story_market", held.clip)
        assertFalse("focus_abandoned" in phases())
    }

    @Test fun `More within the hold hands focus straight to the longer clip`() {
        audio.play("story_market")
        player.done!!()
        schedule.advance(9_000)
        audio.play("story_market-more")
        // One request for both clips, and no hand-back between them.
        assertEquals(listOf("request", "play:story_market", "play:story_market-more"), log)
        assertFalse(audio.isHolding)
        assertEquals("story_market-more", audio.nowPlaying)
        val release = released().single()
        assertEquals("more", release.reason)
        assertEquals("story_market", release.clip)
        assertFalse("focus_granted" in phases().drop(phases().indexOf("focus_held")))
        // The longer clip ends: no longer telling of its own, so focus goes back.
        player.done!!()
        assertEquals("abandon", log.last())
        schedule.advance(WalkAudio.HOLD_MS)
        assertEquals(1, log.count { it == "abandon" })
    }

    @Test fun `the hold ends after 15 s and focus goes back`() {
        audio.play("story_market")
        player.done!!()
        schedule.advance(WalkAudio.HOLD_MS - 1)
        assertFalse("abandon" in log)
        schedule.advance(1)
        assertEquals(listOf("request", "play:story_market", "abandon"), log)
        assertFalse(audio.isHolding)
        assertEquals("timeout", released().single().reason)
        assertEquals("focus_abandoned", phases().last())
    }

    @Test fun `More after the hold asks for focus again, as before`() {
        audio.play("story_market")
        player.done!!()
        schedule.advance(WalkAudio.HOLD_MS + 5_000)
        audio.play("story_market-more")
        assertEquals(listOf("request", "play:story_market", "abandon", "request", "play:story_market-more"), log)
    }

    @Test fun `Skip on the offer hands focus back at once`() {
        audio.play("story_market")
        player.done!!()
        audio.releaseHold("declined")
        assertEquals(listOf("request", "play:story_market", "abandon"), log)
        assertEquals("declined", released().single().reason)
        schedule.advance(WalkAudio.HOLD_MS)
        assertEquals(1, log.count { it == "abandon" })
        assertEquals(1, released().size)
    }

    @Test fun `releasing with no hold does nothing`() {
        audio.releaseHold("declined")
        audio.play("story_bridge")
        player.done!!()
        audio.releaseHold("declined")
        assertEquals(listOf("request", "play:story_bridge", "abandon"), log)
        assertTrue(released().isEmpty())
    }

    @Test fun `stopping the walk during the hold hands focus back`() {
        audio.play("story_market")
        player.done!!()
        audio.stop()
        assertEquals(listOf("request", "play:story_market", "abandon"), log)
        assertEquals("walk_stopped", released().single().reason)
        assertFalse(audio.isHolding)
        schedule.advance(WalkAudio.HOLD_MS)
        assertEquals(1, log.count { it == "abandon" })
    }

    @Test fun `a permanent loss during the hold hands focus back`() {
        audio.play("story_market")
        player.done!!()
        focus.onLoss!!()
        assertEquals(listOf("request", "play:story_market", "abandon"), log)
        assertEquals("focus_lost", released().single().reason)
    }

    @Test fun `a brief loss during the hold gives it up, so More asks again`() {
        audio.play("story_market")
        player.done!!()
        focus.onChange!!(FocusChange.LOSS_TRANSIENT)
        assertEquals(listOf("request", "play:story_market", "abandon"), log)
        assertEquals("focus_lost", released().single().reason)
        audio.play("story_market-more")
        assertEquals("request", log[log.size - 2])
    }

    @Test fun `another spot's clip during the hold plays under the held focus`() {
        audio.play("story_market")
        player.done!!()
        audio.play("story_church")
        assertEquals(listOf("request", "play:story_market", "play:story_church"), log)
        assertEquals("next_clip", released().single().reason)
    }

    @Test fun `a skipped or failed clip, or one with no longer telling, does not hold`() {
        audio.play("story_market")
        audio.skip()
        assertFalse(audio.isHolding)
        audio.play("story_market")
        player.error!!()
        assertFalse(audio.isHolding)
        audio.play("story_bridge")
        player.done!!()
        assertFalse(audio.isHolding)
        assertEquals(3, log.count { it == "abandon" })
        assertFalse("focus_held" in phases())
    }

    @Test fun `with the offer wired up, More hands over and Skip hands back`() {
        lateinit var wired: WalkAudio
        val choice = WalkChoice(
            hasClip = { it == "story_market-more" },
            play = { wired.play(it) },
            schedule = schedule,
            record = { _, _, _ -> },
            release = { wired.releaseHold("declined") },
        )
        wired = WalkAudio(
            focus, player, schedule = schedule,
            step = { steps += it; choice.onStep(it.phase, it.clip) },
            holdAfter = { it in withMore },
        )
        wired.play("story_market")
        player.done!!()
        assertEquals("story_market", choice.offered)
        assertTrue(choice.decline())
        assertEquals(listOf("request", "play:story_market", "abandon"), log)
        assertEquals("declined", released().single().reason)
    }

    @Test fun `the hold and its release are walk audio events with a reason`() {
        val t0 = Instant.parse("2026-11-09T10:00:00Z")
        val held = WalkCollector.audio("w1", "focus_held", "story_market", t0)
        assertEquals(mapOf("walk" to "w1", "phase" to "focus_held", "clip" to "story_market"), held.payload)
        val release = WalkCollector.audio("w1", "hold_released", "story_market", t0, reason = "more")
        assertEquals(
            mapOf("walk" to "w1", "phase" to "hold_released", "clip" to "story_market", "reason" to "more"),
            release.payload,
        )
    }
}
