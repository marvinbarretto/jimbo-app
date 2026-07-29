package dev.marvinbarretto.jimbo.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [InstagramScreenTracker].
 *
 * These run on the JVM (`./gradlew :app:testDebugUnitTest`) — no emulator, no
 * device. That is only possible because the tracker takes `nowMs` as a parameter
 * instead of reading a clock, the same discipline as fake timers in Vitest.
 */
class InstagramScreenTrackerTest {

    private val settle = 700L
    private val idle = 30_000L
    private val maxBurst = 5_000L

    private fun tracker() = InstagramScreenTracker(
        settleDelayMs = settle,
        idleTimeoutMs = idle,
        maxBurstMs = maxBurst,
    )

    private fun InstagramScreenTracker.content(nowMs: Long) =
        onEvent(INSTAGRAM_PACKAGE, windowStateChanged = false, nowMs = nowMs)

    private fun InstagramScreenTracker.window(nowMs: Long) =
        onEvent(INSTAGRAM_PACKAGE, windowStateChanged = true, nowMs = nowMs)

    @Test
    fun `first instagram event opens a session`() {
        val t = tracker()
        assertFalse(t.hasActiveSession)

        val outcome = t.window(1_000)

        assertTrue(outcome.accepted)
        assertTrue(outcome.sessionStarted)
        assertEquals(1L, outcome.sessionId)
        assertTrue(t.hasActiveSession)
    }

    @Test
    fun `subsequent events join the same session`() {
        val t = tracker()
        t.window(1_000)

        val outcome = t.content(1_100)

        assertFalse(outcome.sessionStarted)
        assertEquals(1L, outcome.sessionId)
    }

    @Test
    fun `events from other packages are rejected and change nothing`() {
        val t = tracker()

        val outcome = t.onEvent("com.example.other", windowStateChanged = true, nowMs = 1_000)

        assertFalse(outcome.accepted)
        assertNull(outcome.sessionId)
        assertFalse(t.hasActiveSession)
    }

    @Test
    fun `settle reports the burst once the stream goes quiet`() {
        val t = tracker()
        t.window(1_000)
        t.content(1_200)
        t.content(1_400)

        val report = t.onSettleDeadline(1_400 + settle)

        assertNotNull(report)
        assertEquals(1L, report!!.sessionId)
        assertEquals(1, report.windowChanges)
        assertEquals(2, report.contentChanges)
        assertEquals(400L, report.burstDurationMs)
        assertFalse(report.forced)
    }

    @Test
    fun `first event of a session opens a burst`() {
        val t = tracker()
        assertTrue(t.content(1_000).burstStarted)
        assertFalse(t.content(1_100).burstStarted)
    }

    @Test
    fun `churn ceiling harvests a burst that never goes quiet`() {
        val t = tracker()
        // Reproduces the M1 device measurement: events ~68ms apart, so the 700ms
        // settle debounce never fires. Without the ceiling this harvests nothing.
        var now = 1_000L
        repeat(100) {
            t.content(now)
            now += 68
        }

        val report = t.onChurnDeadline(1_000 + maxBurst)

        assertNotNull(report)
        assertTrue(report!!.forced)
        assertEquals(100, report.contentChanges)
        assertEquals(maxBurst, report.burstDurationMs)
    }

    @Test
    fun `churn ceiling does not fire early`() {
        val t = tracker()
        t.content(1_000)

        assertNull(t.onChurnDeadline(1_000 + maxBurst - 1))
    }

    @Test
    fun `sustained churn harvests once per ceiling rather than never`() {
        val t = tracker()
        var now = 1_000L
        val harvests = mutableListOf<SettleReport>()

        // 15 seconds of unbroken churn at 68ms spacing.
        repeat(220) {
            t.content(now)
            // The service arms the ceiling only when a burst opens; emulate that by
            // checking the deadline against whichever burst is currently open.
            t.onChurnDeadline(now)?.let { report -> harvests += report }
            now += 68
        }

        // ~15s of churn at a 5s ceiling → 2 completed harvests, third still open.
        assertEquals(2, harvests.size)
        assertTrue(harvests.all { it.forced })
    }

    @Test
    fun `a quiet settle after a forced harvest reports only the new burst`() {
        val t = tracker()
        t.content(1_000)
        t.content(3_000)
        val forced = t.onChurnDeadline(1_000 + maxBurst)!!

        // Churn stopped; one last event, then quiet.
        t.content(7_000)
        val quiet = t.onSettleDeadline(7_000 + settle)!!

        assertEquals(2, forced.contentChanges)
        assertFalse(quiet.forced)
        assertEquals(1, quiet.contentChanges)
    }

    @Test
    fun `churn deadline with no open burst reports nothing`() {
        val t = tracker()
        t.content(1_000)
        t.onSettleDeadline(1_000 + settle)

        // The burst already closed quietly — a trailing ceiling must not double-harvest.
        assertNull(t.onChurnDeadline(1_000 + maxBurst))
    }

    @Test
    fun `forced harvests count toward the session settle total`() {
        val t = tracker()
        t.content(1_000)
        t.onChurnDeadline(1_000 + maxBurst)
        t.content(7_000)
        t.onSettleDeadline(7_000 + settle)

        val report = t.onIdleDeadline(7_000 + idle)!!
        assertEquals(2, report.settles)
    }

    @Test
    fun `a settle deadline that a newer event outran reports nothing`() {
        val t = tracker()
        t.content(1_000)
        // User kept scrolling: another event landed before the timer fired.
        t.content(1_500)

        // The stale timer from the first event fires here.
        assertNull(t.onSettleDeadline(1_700))
        // The re-armed one fires later and does report.
        assertNotNull(t.onSettleDeadline(1_500 + settle))
    }

    @Test
    fun `settling twice does not double-count the first burst`() {
        val t = tracker()
        t.content(1_000)
        t.content(1_100)
        val first = t.onSettleDeadline(1_100 + settle)!!

        // Second burst: user scrolled on, then stopped again.
        t.content(5_000)
        t.content(5_050)
        val second = t.onSettleDeadline(5_050 + settle)!!

        assertEquals(2, first.contentChanges)
        assertEquals(2, second.contentChanges)
    }

    @Test
    fun `a settle deadline with no open burst reports nothing`() {
        val t = tracker()
        t.content(1_000)
        assertNotNull(t.onSettleDeadline(1_000 + settle))

        // Nothing has happened since — a spurious second deadline is a no-op.
        assertNull(t.onSettleDeadline(1_000 + settle + 5_000))
    }

    @Test
    fun `idle deadline closes the session and summarises it`() {
        val t = tracker()
        t.window(1_000)
        t.content(1_400)
        t.onSettleDeadline(1_400 + settle)

        val report = t.onIdleDeadline(1_400 + idle)

        assertNotNull(report)
        assertEquals(1L, report!!.sessionId)
        assertEquals(400L, report.durationMs)
        assertEquals(1, report.windowChanges)
        assertEquals(1, report.contentChanges)
        assertEquals(1, report.settles)
        assertFalse(t.hasActiveSession)
    }

    @Test
    fun `idle deadline that a newer event outran keeps the session open`() {
        val t = tracker()
        t.content(1_000)
        t.content(20_000)

        assertNull(t.onIdleDeadline(1_000 + idle))
        assertTrue(t.hasActiveSession)
    }

    @Test
    fun `returning to instagram after an idle close opens a fresh session`() {
        val t = tracker()
        t.content(1_000)
        t.onIdleDeadline(1_000 + idle)

        val outcome = t.content(100_000)

        assertTrue(outcome.sessionStarted)
        assertEquals(2L, outcome.sessionId)
    }

    @Test
    fun `reset drops the session without reporting it`() {
        val t = tracker()
        t.content(1_000)

        t.reset()

        assertFalse(t.hasActiveSession)
        assertNull(t.onIdleDeadline(1_000 + idle))
    }
}
