package dev.marvinbarretto.jimbo.sleepask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class WakeAskTest {
    private val t0 = Instant.parse("2026-11-13T01:00:00Z")
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

    // ── Night: one question per night, covering every stretch ────────────────

    @Test fun threeHoursDownOpensANightAndAsks() {
        val r = Night.onUnlock(NightState(), downSince = at(0), now = at(3))
        assertTrue(r.post)
        assertEquals(listOf(Stretch(at(0), at(3))), r.state.stretches)
    }

    @Test fun aShorterGapOpensNothing() {
        val r = Night.onUnlock(NightState(), downSince = at(0), now = at(2, 59))
        assertFalse(r.post)
        assertTrue(r.state.stretches.isEmpty())
    }

    @Test fun anUnknownGapOpensNothing() {
        assertFalse(Night.onUnlock(NightState(), downSince = null, now = at(9)).post)
    }

    @Test fun aMidNightPickupThenMoreSleepAddsAStretchAndRefreshes() {
        // Down 01:00, picks up at 04:25 for five minutes, down again until 11:01.
        val first = Night.onUnlock(NightState(), downSince = at(0), now = at(3, 25)).state
        val r = Night.onUnlock(first, downSince = at(3, 30), now = at(10, 1))
        assertTrue(r.post)
        assertEquals(listOf(Stretch(at(0), at(3, 25)), Stretch(at(3, 30), at(10, 1))), r.state.stretches)
    }

    @Test fun shortPickupsDuringAnOpenNightChangeNothing() {
        val open = Night.onUnlock(NightState(), downSince = at(0), now = at(4)).state
        val r = Night.onUnlock(open, downSince = at(4, 10), now = at(4, 50))
        assertFalse(r.post)
        assertEquals(open, r.state)
    }

    @Test fun laterHidesItAndItReturnsHalfAnHourOnAtTheNextUnlock() {
        val open = Night.onUnlock(NightState(), downSince = at(0), now = at(4)).state
        val hidden = Night.onHidden(open, at(4, 1))
        assertFalse(Night.onUnlock(hidden, downSince = at(4, 5), now = at(4, 20)).post)
        val back = Night.onUnlock(hidden, downSince = at(4, 25), now = at(4, 40))
        assertTrue(back.post)
        assertNull(back.state.hiddenAt)
    }

    @Test fun answeringSleptClosesTheNightForTwelveHours() {
        val open = Night.onUnlock(NightState(), downSince = at(0), now = at(8)).state
        val closed = Night.onAnsweredSlept(open, at(8, 5))
        assertTrue(closed.stretches.isEmpty())
        assertFalse(Night.onUnlock(closed, downSince = at(12), now = at(19, 59)).post)
        assertTrue(Night.onUnlock(closed, downSince = at(17), now = at(20, 5)).post)
    }

    @Test fun wasntAsleepDoesNotBlockTheRealNightAfter() {
        // An evening at the Mac with the phone down, then the real night.
        val evening = Night.onUnlock(NightState(), downSince = at(0), now = at(3)).state
        val cleared = Night.onAnsweredNotAsleep(evening)
        assertTrue(cleared.stretches.isEmpty())
        assertTrue(Night.onUnlock(cleared, downSince = at(4), now = at(10)).post)
    }

    @Test fun anUnansweredNightExpiresAfterSixteenHours() {
        val open = Night.onUnlock(NightState(), downSince = at(0), now = at(4)).state
        val r = Night.onUnlock(open, downSince = at(16, 5), now = at(16, 30))
        assertEquals(listOf(Stretch(at(0), at(4))), r.expired)
        assertFalse(r.post)
        assertTrue(r.state.stretches.isEmpty())
    }
}
