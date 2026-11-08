package dev.marvinbarretto.jimbo.walk

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkAudioTest {
    private val log = mutableListOf<String>()

    private inner class FakeFocus(var grant: Boolean = true) : WalkAudioFocus {
        var onLoss: (() -> Unit)? = null
        val gains = mutableListOf<Int>()
        override fun request(gain: Int, onLoss: () -> Unit): Boolean {
            gains += gain
            log += "request"
            this.onLoss = onLoss
            return grant
        }
        override fun abandon() { log += "abandon" }
    }

    private inner class FakePlayer : WalkClipPlayer {
        var done: (() -> Unit)? = null
        var error: (() -> Unit)? = null
        override fun play(clip: String, onDone: () -> Unit, onError: () -> Unit) {
            log += "play:$clip"
            done = onDone
            error = onError
        }
        override fun stop() { log += "stop" }
    }

    private val focus = FakeFocus()
    private val player = FakePlayer()
    private val audio = WalkAudio(focus, player)

    @Test fun `a clip takes transient focus, so the podcast pauses`() {
        audio.play("story_market")
        assertEquals(listOf(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT), focus.gains)
        assertEquals(listOf("request", "play:story_market"), log)
    }

    @Test fun `focus is abandoned when the clip ends, so the podcast resumes`() {
        audio.play("story_market")
        player.done!!()
        assertEquals(listOf("request", "play:story_market", "abandon"), log)
    }

    @Test fun `a clip that fails still hands focus back`() {
        audio.play("missing")
        player.error!!()
        assertEquals("abandon", log.last())
    }

    @Test fun `refused focus plays nothing`() {
        focus.grant = false
        audio.play("story_market")
        assertEquals(listOf("request"), log)
    }

    @Test fun `stop mid-clip silences it and hands focus back`() {
        audio.play("story_market")
        audio.stop()
        assertEquals(listOf("request", "play:story_market", "stop", "abandon"), log)
    }

    @Test fun `losing focus mid-clip stops it and hands focus back`() {
        audio.play("story_market")
        focus.onLoss!!()
        assertEquals(listOf("request", "play:story_market", "stop", "abandon"), log)
    }

    @Test fun `a clip that arrives while another plays waits its turn`() {
        audio.play("a")
        audio.play("b")
        assertEquals(listOf("request", "play:a"), log)
        player.done!!()
        player.done!!()
        assertEquals(listOf("request", "play:a", "play:b", "abandon"), log)
        assertTrue(focus.gains.all { it == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT })
    }

    @Test fun `stop with nothing playing does not touch focus`() {
        audio.stop()
        assertTrue(log.isEmpty())
    }
}
