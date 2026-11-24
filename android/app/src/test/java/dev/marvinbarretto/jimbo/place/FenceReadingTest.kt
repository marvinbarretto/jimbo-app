package dev.marvinbarretto.jimbo.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FenceReadingTest {
    @Test fun measuresHowFarAReadingSatFromTheCircleCentre() {
        val fence = PlaceGeofence("ent_cccc0001", 51.0, 0.0, 40f) // invented spot
        assertEquals(0f, FenceReading.distanceFromCentre(51.0, 0.0, fence), 0.5f)
        assertEquals(33.4f, FenceReading.distanceFromCentre(51.0003, 0.0, fence), 0.5f)
    }

    // 0.0003 deg of latitude is about 33.4 m; metres per 1e-5 deg is about 1.112.
    private fun at(metres: Double) = 51.0 + metres / 111_195.0

    @Test fun insideTheCircleCounts() {
        assertTrue(FenceReading.inside(at(10.0), 0.0, PlaceGeofence("ent_cccc0001", 51.0, 0.0, 40f)))
    }

    @Test fun onTheEdgeCounts() {
        assertTrue(FenceReading.inside(at(39.9), 0.0, PlaceGeofence("ent_cccc0001", 51.0, 0.0, 40f)))
    }

    @Test fun outsideByALittleDoesNot() {
        assertFalse(FenceReading.inside(at(47.0), 0.0, PlaceGeofence("ent_cccc0001", 51.0, 0.0, 40f)))
    }

    @Test fun theSeventyOneMetrePretCaseDoesNot() {
        assertFalse(FenceReading.inside(at(71.0), 0.0, PlaceGeofence("ent_cccc0002", 51.0, 0.0, 30f)))
    }

    @Test fun noReadingPasses() {
        assertTrue(FenceReading.inside(null, null, PlaceGeofence("ent_cccc0001", 51.0, 0.0, 40f)))
    }

    private val enter = com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_ENTER
    private val dwell = com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_DWELL
    private val exit = com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_EXIT

    @Test fun enterInsideArrivesAndOutsideIsHeld() {
        assertEquals(FenceReading.Gate(true, false, false, false), FenceReading.gate(enter, true, false))
        assertEquals(FenceReading.Gate(false, false, false, true), FenceReading.gate(enter, false, false))
    }

    @Test fun dwellInsideRunsAHeldArriveWithStay() {
        assertEquals(FenceReading.Gate(true, true, false, false), FenceReading.gate(dwell, true, true))
        assertEquals(FenceReading.Gate(false, true, false, false), FenceReading.gate(dwell, true, false))
    }

    @Test fun dwellOutsideRunsNothingAndKeepsTheHold() {
        assertEquals(FenceReading.Gate(false, false, false, true), FenceReading.gate(dwell, false, true))
    }

    @Test fun exitAlwaysLeavesAndClearsTheHold() {
        assertEquals(FenceReading.Gate(false, false, true, false), FenceReading.gate(exit, false, true))
    }
}
