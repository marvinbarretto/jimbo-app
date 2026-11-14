package dev.marvinbarretto.jimbo.telemetry

import android.app.usage.UsageEvents.Event
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** screen_session is a rolling window, but screen-on can start before it and end after it. */
class ScreenSessionSummaryTest {
    private val t0 = Instant.parse("2026-11-14T10:00:00Z")
    private fun at(s: Long) = t0.plusSeconds(s)
    private fun ev(s: Long, type: Int) = UsageEventRow(null, type, at(s))

    @Test fun screenOnSinceBeforeTheWindowCountsFromTheWindowStart() {
        // On at 0, off at 1000; window 300..2000 saw only the NON_INTERACTIVE.
        val out = summarizeUsageWindow(listOf(ev(0, Event.SCREEN_INTERACTIVE), ev(1000, Event.SCREEN_NON_INTERACTIVE)), at(300), at(2000))
        assertEquals(700.0, out.totalOnSeconds, 0.0)
    }

    @Test fun screenStillOnAtTheWindowEndCountsToTheWindowEnd() {
        val out = summarizeUsageWindow(listOf(ev(500, Event.SCREEN_INTERACTIVE)), at(0), at(2000))
        assertEquals(1500.0, out.totalOnSeconds, 0.0)
    }

    @Test fun aWindowInsideOneLongScreenOnIsAllOn() {
        val out = summarizeUsageWindow(listOf(ev(0, Event.SCREEN_INTERACTIVE), ev(9000, Event.SCREEN_NON_INTERACTIVE)), at(1000), at(3000))
        assertEquals(2000.0, out.totalOnSeconds, 0.0)
    }

    @Test fun unlocksOutsideTheWindowAreNotCounted() {
        val out = summarizeUsageWindow(listOf(
            ev(0, Event.SCREEN_INTERACTIVE), ev(5, Event.KEYGUARD_HIDDEN), ev(1500, Event.KEYGUARD_HIDDEN),
        ), at(1000), at(2000))
        assertEquals(1, out.unlockCount)
        assertEquals(at(1500), out.firstUnlockTs)
    }
}
