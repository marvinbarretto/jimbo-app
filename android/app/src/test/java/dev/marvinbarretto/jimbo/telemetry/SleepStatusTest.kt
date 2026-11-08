package dev.marvinbarretto.jimbo.telemetry

import com.google.android.gms.location.SleepSegmentEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepStatusTest {
    @Test fun namesEveryStatusAndKeepsUnknownOnes() {
        assertEquals("successful", sleepStatusName(SleepSegmentEvent.STATUS_SUCCESSFUL))
        assertEquals("missing_data", sleepStatusName(SleepSegmentEvent.STATUS_MISSING_DATA))
        assertEquals("not_detected", sleepStatusName(SleepSegmentEvent.STATUS_NOT_DETECTED))
        assertEquals("unknown_99", sleepStatusName(99))
    }
}
