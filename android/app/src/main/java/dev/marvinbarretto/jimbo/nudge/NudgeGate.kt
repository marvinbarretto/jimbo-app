package dev.marvinbarretto.jimbo.nudge

/** Decides whether a meal-window nudge may go out. */
object NudgeGate {
    const val MAX_PER_DAY = 2

    /** A log at or after [windowStartMillis] means the window is already covered. */
    fun shouldNudge(nudgesSentToday: Int, lastLogAtMillis: Long?, windowStartMillis: Long): Boolean {
        if (nudgesSentToday >= MAX_PER_DAY) return false
        if (lastLogAtMillis != null && lastLogAtMillis >= windowStartMillis) return false
        return true
    }
}
