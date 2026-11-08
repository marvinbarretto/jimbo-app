package dev.marvinbarretto.jimbo.walk

import android.media.AudioManager

/** Audio focus, behind an interface so the sequencing tests run without AudioManager. */
interface WalkAudioFocus {
    /** Ask for focus at [gain]; true when granted. [onLoss] fires if another app takes it back. */
    fun request(gain: Int, onLoss: () -> Unit): Boolean
    fun abandon()
}

/** Plays one clip and reports its end, or its failure, exactly once. */
interface WalkClipPlayer {
    fun play(clip: String, onDone: () -> Unit, onError: () -> Unit)
    fun stop()
}

/**
 * A clip takes AUDIOFOCUS_GAIN_TRANSIENT, which pauses a podcast (Android does
 * not duck speech, and podcast apps pause on a may-duck request anyway), and
 * abandons it when the clip ends, fails or is stopped, so the podcast resumes.
 * A clip that arrives while another plays waits, under the same focus. Since
 * Android 15 the request only succeeds from a foreground service: the walk
 * service holds it (docs/research/history-walks-market.md in jimbo).
 */
class WalkAudio(
    private val focus: WalkAudioFocus,
    private val player: WalkClipPlayer,
) : WalkPlayback {
    private val queue = ArrayDeque<String>()
    private var playing = false
    // Bumped on every start and stop, so a late callback from a stopped clip is ignored.
    private var generation = 0

    override fun play(clip: String) {
        queue.addLast(clip)
        if (!playing) startNext(needFocus = true)
    }

    override fun stop() {
        queue.clear()
        if (!playing) return
        generation++
        playing = false
        player.stop()
        focus.abandon()
    }

    private fun startNext(needFocus: Boolean) {
        val clip = queue.removeFirstOrNull() ?: return
        if (needFocus && !focus.request(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, onLoss = ::stop)) {
            // Refused (a call, say): this clip is skipped, not held for later.
            queue.clear()
            return
        }
        playing = true
        val mine = ++generation
        val finished = { if (mine == generation) onClipEnd() }
        player.play(clip, onDone = finished, onError = finished)
    }

    private fun onClipEnd() {
        if (queue.isEmpty()) {
            generation++
            playing = false
            focus.abandon()
        } else {
            startNext(needFocus = false)
        }
    }
}
