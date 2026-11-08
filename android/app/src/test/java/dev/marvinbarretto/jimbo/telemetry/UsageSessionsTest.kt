package dev.marvinbarretto.jimbo.telemetry

import android.app.usage.UsageEvents.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class UsageSessionsTest {
    private val t0 = Instant.parse("2026-11-08T10:00:00Z")
    private fun at(s: Long) = t0.plusSeconds(s)
    private fun ev(s: Long, type: Int, pkg: String? = null) = UsageEventRow(pkg, type, at(s))
    private val ignore = setOf("launcher", "com.android.systemui")

    @Test fun anotherAppOrHomeEndsASession() {
        val out = buildUsageSessions(listOf(
            ev(0, Event.SCREEN_INTERACTIVE), ev(1, Event.KEYGUARD_HIDDEN),
            ev(2, Event.ACTIVITY_RESUMED, "chess"), ev(60, Event.ACTIVITY_PAUSED, "chess"),
            ev(60, Event.ACTIVITY_RESUMED, "x"), ev(100, Event.ACTIVITY_PAUSED, "x"),
            ev(100, Event.ACTIVITY_RESUMED, "launcher"),
            ev(110, Event.SCREEN_NON_INTERACTIVE),
        ), ignore)
        assertEquals(listOf(AppSession("chess", at(2), at(60)), AppSession("x", at(60), at(100))), out.sessions)
        assertEquals(listOf(at(1)), out.unlocks)
        assertEquals(listOf(ScreenSpan(at(0), at(110))), out.screenOn)
        assertNull(out.resumeFrom)
    }

    @Test fun movingBetweenAnAppsOwnScreensIsOneSession() {
        val out = buildUsageSessions(listOf(
            ev(0, Event.ACTIVITY_RESUMED, "chess"), ev(30, Event.ACTIVITY_PAUSED, "chess"),
            ev(31, Event.ACTIVITY_RESUMED, "chess"), ev(90, Event.ACTIVITY_PAUSED, "chess"),
            ev(90, Event.ACTIVITY_RESUMED, "launcher"),
        ), ignore)
        assertEquals(listOf(AppSession("chess", at(0), at(90))), out.sessions)
    }

    @Test fun screenOffEndsAnAppLeftOpen() {
        // The old pairing kept counting until the next PAUSED, hours later.
        val out = buildUsageSessions(listOf(
            ev(0, Event.SCREEN_INTERACTIVE), ev(5, Event.ACTIVITY_RESUMED, "youtube"),
            ev(65, Event.SCREEN_NON_INTERACTIVE), ev(7200, Event.ACTIVITY_PAUSED, "youtube"),
        ), ignore)
        assertEquals(listOf(AppSession("youtube", at(5), at(65))), out.sessions)
    }

    @Test fun aResumeLongAfterAPauseStartsANewSession() {
        val out = buildUsageSessions(listOf(
            ev(0, Event.ACTIVITY_RESUMED, "chess"), ev(20, Event.ACTIVITY_PAUSED, "chess"),
            ev(300, Event.ACTIVITY_RESUMED, "chess"), ev(330, Event.KEYGUARD_SHOWN),
        ), ignore)
        assertEquals(listOf(AppSession("chess", at(0), at(20)), AppSession("chess", at(300), at(330))), out.sessions)
    }

    @Test fun anOpenSessionIsHeldBackAndResumedFrom() {
        val out = buildUsageSessions(listOf(
            ev(0, Event.SCREEN_INTERACTIVE), ev(10, Event.ACTIVITY_RESUMED, "x"),
        ), ignore)
        assertEquals(emptyList<AppSession>(), out.sessions)
        assertEquals(at(0), out.resumeFrom)
    }

    @Test fun dailyTopIsClippedAndLeavesTheLauncherOut() {
        val top = topAppUsage(listOf(
            ev(0, Event.ACTIVITY_RESUMED, "launcher"), ev(10, Event.ACTIVITY_RESUMED, "x"),
            ev(70, Event.SCREEN_NON_INTERACTIVE), ev(9000, Event.ACTIVITY_PAUSED, "launcher"),
        ), ignore) { it }
        assertEquals(listOf("x" to 60.0), top.map { it.pkg to it.foregroundSeconds })
    }
}
