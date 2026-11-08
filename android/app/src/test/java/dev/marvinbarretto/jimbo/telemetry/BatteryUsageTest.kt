package dev.marvinbarretto.jimbo.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryUsageTest {
    private val sample = """
    Estimated power use (mAh):
      Capacity: 4316, Computed drain: 812.5, actual drain: 950
    Global
      screen: 12.2 apps: 0
      cpu: 21.4 apps: 21.4 duration: 31m 51s 416ms
    UID u0a153: 3.26 bg: 0.0667 (1m 1s 717ms) fgs: 3.20 (30m 50s 848ms)
    UID u0a158: 0.106 bg: 0.0493 fgs: 0.0568 (17m 43s 290ms) cached: 0.0000860 (458ms)
    UID 1000: 5.32 fg: 1.0
    """.trimIndent()

    @Test fun readsDrainAndPerUidUse() {
        val u = parseBatteryUsage(sample)!!
        assertEquals(4316.0, u.capacity!!, 0.0)
        assertEquals(812.5, u.computedDrain!!, 0.0)
        assertEquals(950.0, u.actualDrain!!, 0.0)
        assertEquals(0.106, u.byUid["u0a158"]!!, 0.0)
        assertEquals(3.26, u.byUid["u0a153"]!!, 0.0)
        assertEquals(5.32, u.byUid["1000"]!!, 0.0)
        assertEquals(3, u.byUid.size)
    }

    @Test fun aPermissionDenialIsNoReading() =
        assertNull(parseBatteryUsage("Permission Denial: can't dump BatteryStatsService from pid=1, uid=10158"))

    @Test fun uidLabelsRoundTrip() {
        assertEquals("u0a158", uidLabel(10158))
        assertEquals("1000", uidLabel(1000))
        assertEquals("u10a5", uidLabel(1_010_005))
        assertEquals(10158, uidFromLabel("u0a158"))
        assertEquals(1000, uidFromLabel("1000"))
        assertNull(uidFromLabel("u0i3"))
    }
}
