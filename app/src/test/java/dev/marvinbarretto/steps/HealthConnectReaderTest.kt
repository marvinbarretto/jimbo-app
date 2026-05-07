package dev.marvinbarretto.steps

import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectReaderTest {

    @Test
    fun stageTypeNameMapsToFriendlyLabels() {
        assertEquals("awake", stageTypeName(SleepSessionRecord.STAGE_TYPE_AWAKE))
        assertEquals("awake_in_bed", stageTypeName(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED))
        assertEquals("awake_out_of_bed", stageTypeName(SleepSessionRecord.STAGE_TYPE_OUT_OF_BED))
        assertEquals("sleeping", stageTypeName(SleepSessionRecord.STAGE_TYPE_SLEEPING))
        assertEquals("light", stageTypeName(SleepSessionRecord.STAGE_TYPE_LIGHT))
        assertEquals("deep", stageTypeName(SleepSessionRecord.STAGE_TYPE_DEEP))
        assertEquals("rem", stageTypeName(SleepSessionRecord.STAGE_TYPE_REM))
        assertEquals("unknown", stageTypeName(SleepSessionRecord.STAGE_TYPE_UNKNOWN))
    }

    @Test
    fun sleepSessionSummaryIncludesStagesAndTitle() {
        val metadata = Metadata()
        val start = Instant.parse("2026-04-07T22:00:00Z")
        val stage = SleepSessionRecord.Stage(
            start,
            start.plus(Duration.ofMinutes(90)),
            SleepSessionRecord.STAGE_TYPE_LIGHT
        )
        val session = SleepSessionRecord(
            start,
            ZoneOffset.UTC,
            start.plus(Duration.ofHours(8)),
            ZoneOffset.UTC,
            "Nightly",
            null,
            listOf(stage),
            metadata
        )

        val summary = session.toSummary()

        assertEquals("Nightly", summary.title)
        assertEquals(1, summary.stages.size)
        assertEquals("light", summary.stages.first().type)
        assertEquals(90.0, summary.stages.first().durationMinutes, 0.5)
    }
}
