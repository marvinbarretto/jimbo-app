package dev.marvinbarretto.jimbo.sleepask

import java.time.Duration
import java.time.Instant

/**
 * Where the phone stands: unlocked now, and when it was last put down after
 * use. A screen-off only counts when the phone was unlocked, so a notification
 * lighting the screen at 05:00 does not end a night (sleep-metric, SM-7959).
 */
data class PhoneDownState(val unlocked: Boolean, val downSince: Instant?)

/** An unlock's new state, and how long the phone had been down before it (null when unknown). */
data class UnlockResult(val state: PhoneDownState, val downSince: Instant?)

object PhoneDown {
    fun onScreenOff(state: PhoneDownState, now: Instant): PhoneDownState =
        if (state.unlocked) PhoneDownState(unlocked = false, downSince = now) else state

    fun onUnlock(state: PhoneDownState, now: Instant): UnlockResult =
        UnlockResult(
            state = state.copy(unlocked = true),
            downSince = if (state.unlocked) null else state.downSince,
        )
}

/**
 * Whether the first unlock after a long phone-down stretch asks "were you
 * asleep?". Three hours catches a night without firing on a film; twelve
 * between asks keeps it to one question a day. Both are first guesses, to
 * tune from the `sleep.ask` / `sleep.label` events.
 */
object WakeAskGate {
    val MIN_DOWN: Duration = Duration.ofHours(3)
    val MIN_BETWEEN_ASKS: Duration = Duration.ofHours(12)

    fun shouldAsk(downSince: Instant?, now: Instant, lastAsked: Instant?): Boolean {
        if (downSince == null) return false
        if (Duration.between(downSince, now) < MIN_DOWN) return false
        return lastAsked == null || Duration.between(lastAsked, now) >= MIN_BETWEEN_ASKS
    }
}
