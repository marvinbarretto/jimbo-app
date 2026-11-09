package dev.marvinbarretto.jimbo.walk

import android.media.AudioManager

/** A focus change short of a permanent loss. */
enum class FocusChange { LOSS_TRANSIENT, DUCK, GAIN }

/** Audio focus, behind an interface so the sequencing tests run without AudioManager. */
interface WalkAudioFocus {
    /** Ask for focus at [gain]; true when granted. [onLoss] fires if another app takes it back for good. */
    fun request(gain: Int, onLoss: () -> Unit): Boolean

    /**
     * The same request, also reporting the brief changes: a transient loss, a
     * duck, and focus coming back. The default reports none of them, so an
     * implementation that only knows [request] treats every loss as permanent.
     */
    fun requestWithChanges(gain: Int, onLoss: () -> Unit, onChange: (FocusChange) -> Unit): Boolean =
        request(gain, onLoss)

    fun abandon()
}

/** Plays one clip and reports its end, or its failure, exactly once. */
interface WalkClipPlayer {
    fun play(clip: String, onDone: () -> Unit, onError: () -> Unit)
    fun stop()

    /** Pauses the clip in place; false when this clip cannot pause (a spoken placeholder). */
    fun pause(): Boolean = false
    fun resume() {}
    /** Moves the clip by [ms], back when negative, never before its start. */
    fun seekBy(ms: Int) {}
    /** Where the clip is, in milliseconds, when known. */
    fun positionMs(): Int? = null
    /** True when a clip of this name exists as a file (a placeholder does not count). */
    fun hasClip(clip: String): Boolean = false
}

/** Runs [action] after [delayMs] on the walk's thread; the returned function cancels it. */
fun interface WalkSchedule {
    fun after(delayMs: Long, action: () -> Unit): () -> Unit
}

/**
 * One audio step for walk.audio; [kind] says which focus loss (transient, permanent, duck), and
 * [reason] why a More hold ended (more, next_clip, declined, timeout, walk_stopped, focus_lost).
 */
data class WalkAudioStep(val phase: String, val clip: String?, val kind: String? = null, val reason: String? = null)

/** A notification control as walk.control records it: [applied] is false when nothing was playing. */
data class WalkControl(val action: String, val clip: String?, val positionSeconds: Double?, val applied: Boolean)

/**
 * A clip takes AUDIOFOCUS_GAIN_TRANSIENT, which pauses a podcast (Android does
 * not duck speech, and podcast apps pause on a may-duck request anyway), and
 * abandons it when the clip ends, fails, is skipped or is stopped, so the
 * podcast resumes. A clip that arrives while another plays waits, under the
 * same focus. Since Android 15 the request only succeeds from a foreground
 * service: the walk service holds it (docs/research/history-walks-market.md in jimbo).
 *
 * Focus changes (walk 2): a transient loss pauses the clip and focus coming
 * back resumes it, keeping the request; a duck plays on; a permanent loss
 * stops the clip and hands focus back, as before. A clip that cannot pause
 * (the spoken placeholder) is stopped on a transient loss too.
 *
 * Controls: Pause keeps focus, so the podcast stays quiet, for
 * [PAUSE_HOLD_MS]; then the clip ends as if skipped and focus goes back, so a
 * forgotten pause never silences the podcast for the rest of the walk. Back
 * moves 10 s back. Skip ends the clip and moves on to a waiting one, or
 * hands focus back.
 *
 * The More hold (walk 3): when a clip that has a longer telling ([holdAfter])
 * ends by itself, focus is kept, in silence, for [HOLD_MS] while More is on
 * offer, so the podcast does not resume and pause again when More is tapped.
 * The next clip played (More, or another spot) takes the held focus without a
 * new request; Skip ([releaseHold]), the timeout, a stop or a focus loss hand
 * it back. Recorded as focus_held, then hold_released with its reason.
 */
class WalkAudio(
    private val focus: WalkAudioFocus,
    private val player: WalkClipPlayer,
    /** Each step, for diagnostics: focus_granted, focus_refused, queued, clip_start, clip_end, clip_error, clip_skipped, focus_lost, focus_regained, pause_expired, focus_abandoned. */
    private val report: (phase: String, clip: String?) -> Unit = { _, _ -> },
    private val schedule: WalkSchedule = WalkSchedule { _, _ -> {} },
    /** The same steps with the focus-loss kind: what the service records. */
    private val step: (WalkAudioStep) -> Unit = {},
    /** True for a clip whose end opens a More offer: focus is held for it. Off by default. */
    private val holdAfter: (clip: String) -> Boolean = { false },
) : WalkPlayback {
    private val queue = ArrayDeque<String>()
    private var current: String? = null
    // Bumped on every start and stop, so a late callback from a stopped clip is ignored.
    private var generation = 0
    private var userPaused = false
    private var focusPaused = false
    private var cancelHold: (() -> Unit)? = null
    // The More hold: the short clip whose focus is kept, and its timer.
    private var heldFor: String? = null
    private var cancelFocusHold: (() -> Unit)? = null

    /** The clip playing or paused, if any. */
    val nowPlaying: String? get() = current

    /** True while the walker has the clip paused. */
    val isPaused: Boolean get() = userPaused

    /** True while focus is kept, in silence, through a More offer. */
    val isHolding: Boolean get() = heldFor != null

    override fun play(clip: String) {
        queue.addLast(clip)
        when {
            current != null -> emit("queued", clip)
            heldFor != null -> {
                val held = endHold()
                emit("hold_released", held, reason = if (clip == held + MORE_SUFFIX) "more" else "next_clip")
                startNext(needFocus = false)
            }
            else -> startNext(needFocus = true)
        }
    }

    override fun stop() = stopWith("walk_stopped")

    /** Hands held focus back at once (Skip on the More offer); nothing when no hold is on. */
    fun releaseHold(reason: String) {
        val held = endHold() ?: return
        emit("hold_released", held, reason = reason)
        focus.abandon()
        emit("focus_abandoned", null)
    }

    private fun stopWith(holdReason: String) {
        queue.clear()
        if (heldFor != null) {
            releaseHold(holdReason)
            return
        }
        if (current == null) return
        endClip()
        player.stop()
        focus.abandon()
        emit("focus_abandoned", null)
    }

    private fun endHold(): String? {
        val held = heldFor ?: return null
        heldFor = null
        cancelFocusHold?.invoke()
        cancelFocusHold = null
        return held
    }

    fun pause(): WalkControl {
        val clip = current ?: return idle("pause")
        if (userPaused) return control("pause", clip, applied = false)
        val result = control("pause", clip, applied = true)
        if (!focusPaused && !player.pause()) return result.copy(applied = false)
        userPaused = true
        val mine = generation
        cancelHold = schedule.after(PAUSE_HOLD_MS) {
            if (mine == generation && userPaused) {
                emit("pause_expired", clip)
                player.stop()
                onClipEnd()
            }
        }
        return result
    }

    fun resume(): WalkControl {
        val clip = current ?: return idle("resume")
        if (!userPaused) return control("resume", clip, applied = false)
        userPaused = false
        cancelHold?.invoke()
        cancelHold = null
        // Still waiting for focus to come back: the clip resumes then.
        if (!focusPaused) player.resume()
        return control("resume", clip, applied = true)
    }

    fun back(): WalkControl {
        val clip = current ?: return idle("back")
        val result = control("back", clip, applied = true)
        player.seekBy(-BACK_MS)
        return result
    }

    fun skip(): WalkControl {
        val clip = current ?: return idle("skip")
        val result = control("skip", clip, applied = true)
        emit("clip_skipped", clip)
        player.stop()
        onClipEnd()
        return result
    }

    private fun idle(action: String) = WalkControl(action, null, null, applied = false)

    private fun control(action: String, clip: String, applied: Boolean) =
        WalkControl(action, clip, player.positionMs()?.let { Math.round(it / 100.0) / 10.0 }, applied)

    private fun emit(phase: String, clip: String?, kind: String? = null, reason: String? = null) {
        report(phase, clip)
        step(WalkAudioStep(phase, clip, kind, reason))
    }

    private fun startNext(needFocus: Boolean) {
        val clip = queue.removeFirstOrNull() ?: return
        if (needFocus) {
            if (!focus.requestWithChanges(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, ::onFocusLost, ::onFocusChange)) {
                // Refused (a call, say): this clip is skipped, not held for later.
                emit("focus_refused", clip)
                queue.clear()
                return
            }
            emit("focus_granted", clip)
        }
        current = clip
        val mine = ++generation
        emit("clip_start", clip)
        player.play(
            clip,
            onDone = { if (mine == generation) { emit("clip_end", clip); onClipEnd(ended = clip) } },
            onError = { if (mine == generation) { emit("clip_error", clip); onClipEnd() } },
        )
    }

    private fun onFocusLost() {
        emit("focus_lost", current ?: heldFor, kind = "permanent")
        stopWith("focus_lost")
    }

    private fun onFocusChange(change: FocusChange) {
        val held = heldFor
        if (held != null) {
            // Nothing plays during the hold: a brief loss gives it up, so More asks again.
            if (change == FocusChange.LOSS_TRANSIENT) {
                emit("focus_lost", held, kind = "transient")
                releaseHold("focus_lost")
            }
            return
        }
        val clip = current ?: return
        when (change) {
            FocusChange.DUCK -> emit("focus_lost", clip, kind = "duck")
            FocusChange.LOSS_TRANSIENT -> {
                emit("focus_lost", clip, kind = "transient")
                if (focusPaused) return
                focusPaused = true
                if (!userPaused && !player.pause()) stop()
            }
            FocusChange.GAIN -> {
                emit("focus_regained", clip)
                if (!focusPaused) return
                focusPaused = false
                if (!userPaused) player.resume()
            }
        }
    }

    /** Forgets the clip that was playing: its callbacks and its pause timer go with it. */
    private fun endClip() {
        generation++
        current = null
        userPaused = false
        focusPaused = false
        cancelHold?.invoke()
        cancelHold = null
    }

    /** [ended] is the clip that finished by itself; a skipped, failed or expired clip passes null. */
    private fun onClipEnd(ended: String? = null) {
        endClip()
        when {
            queue.isNotEmpty() -> startNext(needFocus = false)
            ended != null && holdAfter(ended) -> hold(ended)
            else -> {
                focus.abandon()
                emit("focus_abandoned", null)
            }
        }
    }

    private fun hold(clip: String) {
        heldFor = clip
        emit("focus_held", clip)
        cancelFocusHold = schedule.after(HOLD_MS) {
            if (heldFor == clip) releaseHold("timeout")
        }
    }

    companion object {
        /** How long Pause holds focus before the clip ends and the podcast comes back. */
        const val PAUSE_HOLD_MS = 60_000L
        const val BACK_MS = 10_000
        /** How long focus is kept after a clip whose More is on offer: walk 2's takes came within 12 s. */
        const val HOLD_MS = 15_000L
        private const val MORE_SUFFIX = WalkChoice.MORE_SUFFIX
    }
}
