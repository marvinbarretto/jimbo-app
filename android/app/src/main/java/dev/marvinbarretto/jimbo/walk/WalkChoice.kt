package dev.marvinbarretto.jimbo.walk

/**
 * The opt-in "More" (walk 2, HW-7849). Short clips play unasked; when one ends
 * by itself and a longer telling exists (a clip file named `<clip>-more`), the
 * running notification offers More and Skip for [OFFER_MS]. More plays the
 * longer telling under its own transient focus; Skip declines; silence lets
 * it lapse. A new clip starting, or the walk stopping, closes an open offer
 * as expired with a reason. A skipped or failed clip offers nothing. Each
 * outcome goes to [record] as walk.choice. Driven from WalkAudio's steps, on
 * the walk's thread.
 */
class WalkChoice(
    private val hasClip: (String) -> Boolean,
    private val play: (String) -> Unit,
    private val schedule: WalkSchedule,
    /** choice (offered, taken, declined, expired), the short clip, and why it lapsed when not by time. */
    private val record: (choice: String, clip: String, reason: String?) -> Unit,
    /** The offer opened or closed: the notification redraws. */
    private val changed: () -> Unit = {},
    /** Skip was tapped: hand back the focus WalkAudio kept through the offer (walk 3). */
    private val release: () -> Unit = {},
) {
    /** The short clip whose longer telling is on offer, if any. */
    var offered: String? = null
        private set
    private var cancelTimer: (() -> Unit)? = null

    fun onStep(phase: String, clip: String?) {
        when (phase) {
            "clip_end" -> if (clip != null && !clip.endsWith(MORE_SUFFIX) && hasClip(clip + MORE_SUFFIX)) offer(clip)
            "clip_start" -> close("expired", "superseded")
        }
    }

    /** More: plays the longer telling. False when nothing is on offer. */
    fun take(): Boolean {
        val clip = close("taken", null) ?: return false
        play(clip + MORE_SUFFIX)
        return true
    }

    /** Skip: declines the offer. False when nothing is on offer. */
    fun decline(): Boolean {
        if (close("declined", null) == null) return false
        release()
        return true
    }

    /** The walk stopped. */
    fun end() {
        close("expired", "walk_stopped")
    }

    private fun offer(clip: String) {
        close("expired", "superseded")
        offered = clip
        record("offered", clip, null)
        cancelTimer = schedule.after(OFFER_MS) {
            if (offered == clip) close("expired", null)
        }
        changed()
    }

    private fun close(choice: String, reason: String?): String? {
        val clip = offered ?: return null
        offered = null
        cancelTimer?.invoke()
        cancelTimer = null
        record(choice, clip, reason)
        changed()
        return clip
    }

    companion object {
        const val MORE_SUFFIX = "-more"
        const val OFFER_MS = 60_000L
    }
}
