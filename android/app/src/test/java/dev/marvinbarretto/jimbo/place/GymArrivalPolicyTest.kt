package dev.marvinbarretto.jimbo.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class GymArrivalPolicyTest {
    private val now = Instant.parse("2026-10-07T18:00:00Z")

    @Test fun offersWhenNothingActiveAndNothingRecent() =
        assertTrue(GymArrivalPolicy.shouldOfferStart(false, null, now))

    @Test fun offersWhenLastSessionEndedLongAgo() =
        assertTrue(GymArrivalPolicy.shouldOfferStart(false, now.minus(Duration.ofHours(2)), now))

    @Test fun silentWhenSessionActive() =
        assertFalse(GymArrivalPolicy.shouldOfferStart(true, null, now))

    @Test fun silentWhenSessionFinishedWithinTheHour() =
        assertFalse(GymArrivalPolicy.shouldOfferStart(false, now.minus(Duration.ofMinutes(30)), now))

    @Test fun offersAtExactlyOneHour() =
        assertTrue(GymArrivalPolicy.shouldOfferStart(false, now.minus(Duration.ofHours(1)), now))
}

class PlaceGeofenceTest {
    @Test fun parsesLatLngWithDefaultRadius() {
        val g = PlaceGeofence.parse(Place.GYM, "51.5,-0.12")!!
        assertEquals(PlaceGeofence.DEFAULT_RADIUS_M, g.radiusMetres, 0f)
        assertEquals("gym", g.requestId)
    }

    @Test fun parsesExplicitRadius() =
        assertEquals(80f, PlaceGeofence.parse(Place.HOME, "51.5, -0.12, 80")!!.radiusMetres, 0f)

    @Test fun blankOrBadSpecIsNotFenced() {
        assertNull(PlaceGeofence.parse(Place.HOME, ""))
        assertNull(PlaceGeofence.parse(Place.HOME, "abc,def"))
        assertNull(PlaceGeofence.parse(Place.HOME, "95,0"))
        assertNull(PlaceGeofence.parse(Place.HOME, "51,0,-5"))
    }
}
