package dev.marvinbarretto.jimbo.sleepask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class WakeAskTest {
    private val t0 = Instant.parse("2026-11-13T03:00:00Z")
    private fun at(h: Long, m: Long = 0) = t0.plus(Duration.ofHours(h).plusMinutes(m))

    // ── PhoneDown: when the phone was last put down after use ────────────────

    @Test fun screenOffAfterUnlockMarksThePhoneDown() {
        val unlocked = PhoneDown.onUnlock(PhoneDownState(unlocked = false, downSince = null), at(0)).state
        val down = PhoneDown.onScreenOff(unlocked, at(0, 20))
        assertEquals(PhoneDownState(unlocked = false, downSince = at(0, 20)), down)
    }

    @Test fun screenOffWithoutUnlockLeavesTheDownTimeAlone() {
        // A notification lights the screen and it goes off again: he never used the phone.
        val down = PhoneDownState(unlocked = false, downSince = at(0))
        assertEquals(down, PhoneDown.onScreenOff(down, at(2)))
    }

    @Test fun unlockReportsHowLongThePhoneWasDown() {
        val result = PhoneDown.onUnlock(PhoneDownState(unlocked = false, downSince = at(0)), at(6, 30))
        assertEquals(at(0), result.downSince)
        assertEquals(PhoneDownState(unlocked = true, downSince = at(0)), result.state)
    }

    @Test fun unlockWithNoKnownDownTimeReportsNone() {
        assertNull(PhoneDown.onUnlock(PhoneDownState(unlocked = false, downSince = null), at(1)).downSince)
        // Already unlocked (a screen-off was missed): no gap is claimed.
        assertNull(PhoneDown.onUnlock(PhoneDownState(unlocked = true, downSince = at(0)), at(5)).downSince)
    }

    // ── WakeAskGate: whether this unlock gets the question ───────────────────

    @Test fun asksAfterThreeHoursDown() {
        assertTrue(WakeAskGate.shouldAsk(downSince = at(0), now = at(3), lastAsked = null))
    }

    @Test fun doesNotAskAfterAShorterGap() {
        assertFalse(WakeAskGate.shouldAsk(downSince = at(0), now = at(2, 59), lastAsked = null))
    }

    @Test fun doesNotAskWithoutAKnownDownTime() {
        assertFalse(WakeAskGate.shouldAsk(downSince = null, now = at(9), lastAsked = null))
    }

    @Test fun asksAtMostOnceEveryTwelveHours() {
        assertFalse(WakeAskGate.shouldAsk(downSince = at(0), now = at(11, 59), lastAsked = at(0)))
        assertTrue(WakeAskGate.shouldAsk(downSince = at(8), now = at(12), lastAsked = at(0)))
    }
}
