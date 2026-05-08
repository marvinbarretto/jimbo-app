package dev.marvinbarretto.jimbo.telemetry

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class UsageCollectorTest {

    @Test
    fun summarizeUsageWindowAggregatesScreenAndUnlocks() {
        val summary = summarizeUsageWindow(
            listOf(
                usageEvent(UsageEvents.Event.SCREEN_INTERACTIVE, "2026-05-07T10:00:00Z"),
                usageEvent(UsageEvents.Event.KEYGUARD_HIDDEN, "2026-05-07T10:00:05Z"),
                usageEvent(UsageEvents.Event.SCREEN_NON_INTERACTIVE, "2026-05-07T10:05:00Z"),
                usageEvent(UsageEvents.Event.SCREEN_INTERACTIVE, "2026-05-07T11:00:00Z"),
                usageEvent(UsageEvents.Event.KEYGUARD_HIDDEN, "2026-05-07T11:00:03Z"),
                usageEvent(UsageEvents.Event.SCREEN_NON_INTERACTIVE, "2026-05-07T11:10:00Z"),
            )
        )

        assertEquals(900.0, summary.totalOnSeconds, 0.01)
        assertEquals(2, summary.unlockCount)
        assertEquals(Instant.parse("2026-05-07T10:00:05Z"), summary.firstUnlockTs)
        assertEquals(Instant.parse("2026-05-07T11:00:03Z"), summary.lastUnlockTs)
    }

    @Test
    fun topAppUsageRollsForegroundDurationsAndLimitsOrdering() {
        val summaries = topAppUsage(
            listOf(
                usageEvent(UsageEvents.Event.ACTIVITY_RESUMED, "2026-05-07T10:00:00Z", "a"),
                usageEvent(UsageEvents.Event.ACTIVITY_PAUSED, "2026-05-07T10:05:00Z", "a"),
                usageEvent(UsageEvents.Event.ACTIVITY_RESUMED, "2026-05-07T10:06:00Z", "b"),
                usageEvent(UsageEvents.Event.ACTIVITY_PAUSED, "2026-05-07T10:20:00Z", "b"),
                usageEvent(UsageEvents.Event.ACTIVITY_RESUMED, "2026-05-07T10:21:00Z", "a"),
                usageEvent(UsageEvents.Event.ACTIVITY_PAUSED, "2026-05-07T10:23:00Z", "a"),
            ),
        ) { pkg -> "label:$pkg" }

        assertEquals(listOf("b", "a"), summaries.map { it.pkg })
        assertEquals(840.0, summaries.first().foregroundSeconds, 0.01)
        assertEquals("label:b", summaries.first().label)
        assertEquals(420.0, summaries.last().foregroundSeconds, 0.01)
    }

    @Test
    fun summarizeUsageWindowHandlesNoUnlocks() {
        val summary = summarizeUsageWindow(emptyList())

        assertEquals(0.0, summary.totalOnSeconds, 0.01)
        assertEquals(0, summary.unlockCount)
        assertNull(summary.firstUnlockTs)
        assertNull(summary.lastUnlockTs)
    }

    private fun usageEvent(type: Int, ts: String, pkg: String? = null) = UsageEventRow(
        packageName = pkg,
        eventType = type,
        timestamp = Instant.parse(ts)
    )
}
