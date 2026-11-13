package dev.marvinbarretto.jimbo.telemetry

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateRangeTest {

    @Test
    fun endsAtTheLastWholeSliceSoAFillingSliceWaits() {
        val (from, to) = heartRateRange(Instant.parse("2026-11-14T08:17:42Z"))
        assertEquals(Instant.parse("2026-11-14T08:10:00Z"), to)
        assertEquals(Instant.parse("2026-11-13T08:10:00Z"), from)
    }

    @Test
    fun aRunOnASliceBoundaryIncludesTheSliceJustClosed() {
        val (_, to) = heartRateRange(Instant.parse("2026-11-14T08:20:00Z"))
        assertEquals(Instant.parse("2026-11-14T08:20:00Z"), to)
    }

    @Test
    fun theLookbackCoversAWholeNightWithItsLateSync() {
        val (from, to) = heartRateRange(Instant.parse("2026-11-14T12:00:00Z"))
        assertEquals(Duration.ofHours(24), Duration.between(from, to))
    }
}
