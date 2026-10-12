package dev.marvinbarretto.jimbo.telemetry

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BlockUsageTest {

    private fun at(second: Long) = Instant.ofEpochSecond(1_000 + second)
    private fun resumed(pkg: String, s: Long) = UsageEventRow(pkg, UsageEvents.Event.ACTIVITY_RESUMED, at(s))
    private fun paused(pkg: String, s: Long) = UsageEventRow(pkg, UsageEvents.Event.ACTIVITY_PAUSED, at(s))

    @Test
    fun countsEachReturnFromAnotherAppAsOneLaunch() {
        val events = listOf(
            resumed("ig", 0), paused("ig", 60),
            resumed("mail", 60), paused("mail", 90),
            resumed("ig", 90), paused("ig", 150),
        )
        val ig = summarizeBlockUsage(events, emptySet()).first { it.pkg == "ig" }
        assertEquals(2, ig.launches)
        assertEquals(120.0, ig.foregroundSeconds, 0.0)
    }

    @Test
    fun hoppingBetweenOneAppsOwnScreensIsOneLaunch() {
        val events = listOf(
            resumed("ig", 0), paused("ig", 10),
            resumed("ig", 10), paused("ig", 20),
        )
        val ig = summarizeBlockUsage(events, emptySet()).single()
        assertEquals(1, ig.launches)
        assertEquals(20.0, ig.foregroundSeconds, 0.0)
    }

    @Test
    fun ignoredPackagesAreDroppedAndDoNotBreakARun() {
        val events = listOf(
            resumed("ig", 0), paused("ig", 30),
            resumed("launcher", 30), paused("launcher", 31),
            resumed("ig", 31), paused("ig", 40),
        )
        val result = summarizeBlockUsage(events, setOf("launcher"))
        assertEquals(listOf("ig"), result.map { it.pkg })
        // The launcher is invisible, so ig → (launcher) → ig is one continuous run.
        assertEquals(1, result.single().launches)
    }

    @Test
    fun ordersByForegroundTimeAndEmptyWindowIsEmpty() {
        assertTrue(summarizeBlockUsage(emptyList(), emptySet()).isEmpty())
        val events = listOf(
            resumed("a", 0), paused("a", 10),
            resumed("b", 10), paused("b", 100),
        )
        assertEquals(listOf("b", "a"), summarizeBlockUsage(events, emptySet()).map { it.pkg })
    }
}
