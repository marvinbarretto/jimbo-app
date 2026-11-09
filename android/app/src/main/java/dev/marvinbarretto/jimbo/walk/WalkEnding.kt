package dev.marvinbarretto.jimbo.walk

/**
 * The end of a walk (walk 3, HW-7849). Walk 2 ran 28 minutes past its last
 * spot because nothing said the walk was over. Once the walk has reached its
 * end ([finished]: WalkSession.finished) and the audio goes quiet (the last
 * clip ends, or its More hold starts, or its focus is refused), the running
 * notification offers "End walk?" ([prompting]) and a timer starts: untouched,
 * the walk stops itself [AUTO_STOP_MS] after that clip ended. A clip starting
 * (More taken, say) stops the clock; the next quiet starts it again. During a
 * More offer the offer keeps the notification, so [prompting] waits for it.
 * Driven from WalkAudio's steps, on the walk's thread.
 */
class WalkEnding(
    private val schedule: WalkSchedule,
    private val finished: () -> Boolean,
    /** A More offer is open: it has the notification's actions until it closes. */
    private val offerOpen: () -> Boolean,
    /** Stops the walk with a reason: [END_PROMPT] or [AUTO_AFTER_LAST]. */
    private val stop: (reason: String) -> Unit,
    /** The prompt opened or closed: the notification redraws. */
    private val changed: () -> Unit = {},
) {
    private var cancelTimer: (() -> Unit)? = null
    private var cancelled = false

    /** True while the walk is over but still running, waiting on End walk or the timer. */
    val armed: Boolean get() = cancelTimer != null

    /** True when the notification should offer "End walk?". */
    val prompting: Boolean get() = armed && !offerOpen()

    fun onStep(phase: String, @Suppress("UNUSED_PARAMETER") clip: String?) {
        if (cancelled) return
        when (phase) {
            "clip_start" -> disarm()
            "focus_abandoned", "focus_held", "focus_refused" -> if (finished()) arm()
        }
    }

    /** End walk was tapped on the prompt. */
    fun end() {
        cancel()
        stop(END_PROMPT)
    }

    /** The walk is stopping another way: no prompt, no timer, and later steps change nothing. */
    fun cancel() {
        cancelled = true
        disarm()
    }

    private fun arm() {
        // Already counting from an earlier quiet (the hold before its hand-back): keep that start.
        if (armed) return
        cancelTimer = schedule.after(AUTO_STOP_MS) {
            cancelTimer = null
            cancelled = true
            stop(AUTO_AFTER_LAST)
        }
        changed()
    }

    private fun disarm() {
        val cancel = cancelTimer ?: return
        cancelTimer = null
        cancel()
        changed()
    }

    companion object {
        /** Ten minutes after the last clip ended, an untouched walk stops itself. */
        const val AUTO_STOP_MS = 10 * 60_000L

        /** walk.stop reasons: Stop walk tapped, End walk tapped on the prompt, or the timer. */
        const val USER = "user"
        const val END_PROMPT = "end_prompt"
        const val AUTO_AFTER_LAST = "auto_after_last"
        val REASONS = listOf(USER, END_PROMPT, AUTO_AFTER_LAST)
    }
}
