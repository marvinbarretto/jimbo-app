package dev.marvinbarretto.jimbo.nudge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// AC2: the nudge fires at most twice a day, and only when nothing has been
// logged in the window.
class NudgeGateTest {

    private val windowStart = 1_000_000L

    @Test
    fun `fires when nothing is logged and no nudge has gone out today`() {
        assertTrue(NudgeGate.shouldNudge(nudgesSentToday = 0, lastLogAtMillis = null, windowStartMillis = windowStart))
    }

    @Test
    fun `fires a second time the same day`() {
        assertTrue(NudgeGate.shouldNudge(nudgesSentToday = 1, lastLogAtMillis = null, windowStartMillis = windowStart))
    }

    @Test
    fun `never fires a third time in a day`() {
        assertFalse(NudgeGate.shouldNudge(nudgesSentToday = 2, lastLogAtMillis = null, windowStartMillis = windowStart))
        assertFalse(NudgeGate.shouldNudge(nudgesSentToday = 5, lastLogAtMillis = null, windowStartMillis = windowStart))
    }

    @Test
    fun `stays quiet when a log exists inside the window`() {
        assertFalse(NudgeGate.shouldNudge(nudgesSentToday = 0, lastLogAtMillis = windowStart + 1, windowStartMillis = windowStart))
    }

    @Test
    fun `a log exactly at the window start counts as inside it`() {
        assertFalse(NudgeGate.shouldNudge(nudgesSentToday = 0, lastLogAtMillis = windowStart, windowStartMillis = windowStart))
    }

    @Test
    fun `a log from before the window does not suppress the nudge`() {
        assertTrue(NudgeGate.shouldNudge(nudgesSentToday = 0, lastLogAtMillis = windowStart - 1, windowStartMillis = windowStart))
    }
}
