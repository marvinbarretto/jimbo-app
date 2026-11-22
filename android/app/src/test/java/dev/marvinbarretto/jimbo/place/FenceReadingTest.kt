package dev.marvinbarretto.jimbo.place

import org.junit.Assert.assertEquals
import org.junit.Test

class FenceReadingTest {
    @Test fun measuresHowFarAReadingSatFromTheCircleCentre() {
        val fence = PlaceGeofence("ent_cccc0001", 51.0, 0.0, 40f) // invented spot
        assertEquals(0f, FenceReading.distanceFromCentre(51.0, 0.0, fence), 0.5f)
        assertEquals(33.4f, FenceReading.distanceFromCentre(51.0003, 0.0, fence), 0.5f)
    }
}
