package dev.marvinbarretto.jimbo.place

import java.time.Duration
import java.time.Instant

/**
 * Whether arriving at the gym should offer to start a session. The
 * notification asks, the human commits — so the only job here is not nagging:
 * nothing if a session is already running, or one just finished (leaving the
 * gym and re-entering within the hour is not a new workout).
 */
object GymArrivalPolicy {
    val RECENT_SESSION_WINDOW: Duration = Duration.ofHours(1)

    fun shouldOfferStart(hasActiveSession: Boolean, lastSessionEndedAt: Instant?, now: Instant): Boolean {
        if (hasActiveSession) return false
        if (lastSessionEndedAt != null &&
            Duration.between(lastSessionEndedAt, now) < RECENT_SESSION_WINDOW
        ) return false
        return true
    }
}
