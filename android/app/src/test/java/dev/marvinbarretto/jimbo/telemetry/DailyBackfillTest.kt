package dev.marvinbarretto.jimbo.telemetry

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyBackfillTest {
    private val yesterday = LocalDate.of(2026, 9, 24)

    @Test
    fun noGapPostsNoExtraDays() {
        assertEquals(emptyList<LocalDate>(), missedDays(yesterday, yesterday))
    }

    @Test
    fun oneNewClosedDayPostsOnlyThatDay() {
        assertEquals(listOf(yesterday), missedDays(yesterday.minusDays(1), yesterday))
    }

    @Test
    fun tripGapPostsEveryDaySinceLastPost() {
        assertEquals(
            (19L..24L).map { LocalDate.of(2026, 9, it.toInt()) },
            missedDays(LocalDate.of(2026, 9, 18), yesterday)
        )
    }

    @Test
    fun gapIsCappedAtFourteenDays() {
        val days = missedDays(LocalDate.of(2026, 7, 1), yesterday)
        assertEquals(14, days.size)
        assertEquals(yesterday.minusDays(13), days.first())
        assertEquals(yesterday, days.last())
    }

    @Test
    fun noHistoryBackfillsTheCap() {
        assertEquals(14, missedDays(null, yesterday).size)
    }
}
