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

/** One phone-down stretch, from the screen-off after use to the next unlock. */
data class Stretch(val from: Instant, val until: Instant)

/**
 * The open night: every phone-down stretch since the question was first
 * asked, whether Later hid it, and when he last answered "slept".
 */
data class NightState(
    val stretches: List<Stretch> = emptyList(),
    val hiddenAt: Instant? = null,
    val lastSlept: Instant? = null,
)

/** What an unlock does: the new state, whether to post (or refresh) the question, and a night given up on. */
data class NightResult(val state: NightState, val post: Boolean, val expired: List<Stretch>? = null)

/**
 * One question per night, covering the whole night. Marvin's sleep is often
 * broken (13 Nov 2026), so the question opens at the first pickup after a
 * long stretch, silently, and each later stretch of an hour or more is added
 * to it: whenever he answers, he answers for the night, breaks included.
 * Thresholds are first guesses, to tune from the sleep.ask / sleep.label events.
 */
object Night {
    val OPENS_AFTER: Duration = Duration.ofHours(3)
    val ADDS_AFTER: Duration = Duration.ofHours(1)
    val RETURNS_AFTER: Duration = Duration.ofMinutes(30)
    val AFTER_SLEPT: Duration = Duration.ofHours(12)
    val EXPIRES_AFTER: Duration = Duration.ofHours(16)

    fun onUnlock(state: NightState, downSince: Instant?, now: Instant): NightResult {
        var s = state
        var expired: List<Stretch>? = null
        if (s.stretches.isNotEmpty() && Duration.between(s.stretches.first().from, now) > EXPIRES_AFTER) {
            expired = s.stretches
            s = s.copy(stretches = emptyList(), hiddenAt = null)
        }
        val gap = downSince?.let { Duration.between(it, now) }

        if (s.stretches.isEmpty()) {
            val opens = downSince != null && gap!! >= OPENS_AFTER &&
                (s.lastSlept == null || Duration.between(s.lastSlept, now) >= AFTER_SLEPT)
            if (!opens) return NightResult(s, post = false, expired = expired)
            return NightResult(s.copy(stretches = listOf(Stretch(downSince!!, now)), hiddenAt = null), true, expired)
        }
        if (downSince != null && gap!! >= ADDS_AFTER) {
            return NightResult(s.copy(stretches = s.stretches + Stretch(downSince, now), hiddenAt = null), true, expired)
        }
        val hidden = s.hiddenAt
        if (hidden != null && Duration.between(hidden, now) >= RETURNS_AFTER) {
            return NightResult(s.copy(hiddenAt = null), true, expired)
        }
        return NightResult(s, post = false, expired = expired)
    }

    fun onHidden(state: NightState, now: Instant): NightState = state.copy(hiddenAt = now)

    fun onAnsweredSlept(state: NightState, now: Instant): NightState = NightState(lastSlept = now)

    /** "Wasn't asleep" clears the stretch without starting the 12-hour wait: the real night may follow. */
    fun onAnsweredNotAsleep(state: NightState): NightState = NightState(lastSlept = state.lastSlept)
}
