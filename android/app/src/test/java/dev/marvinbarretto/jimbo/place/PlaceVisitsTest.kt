package dev.marvinbarretto.jimbo.place

import com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_DWELL
import com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_ENTER
import com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_EXIT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceVisitsTest {
    private val min = 60_000L

    @Test fun `first dwell is recorded and notifies`() {
        val d = PlaceVisits.decide(GEOFENCE_TRANSITION_DWELL, null, 0)
        assertTrue(d.record)
        assertTrue(d.notify)
        assertEquals(PlaceVisit(0, dwelled = true), d.next)
    }

    @Test fun `a repeated dwell in the same visit is dropped`() {
        // 8 Nov: dwell at home at 03:21, 03:37, 03:57, 04:13, 04:33 with nobody moving.
        val open = PlaceVisit(0, dwelled = true)
        val d = PlaceVisits.decide(GEOFENCE_TRANSITION_DWELL, open, 16 * min)
        assertFalse(d.record)
        assertFalse(d.notify)
        assertEquals(open, d.next)
    }

    @Test fun `enter then dwell keeps the visit start`() {
        val entered = PlaceVisits.decide(GEOFENCE_TRANSITION_ENTER, null, 0).next
        val d = PlaceVisits.decide(GEOFENCE_TRANSITION_DWELL, entered, 3 * min)
        assertTrue(d.record)
        assertEquals(PlaceVisit(0, dwelled = true), d.next)
    }

    @Test fun `a repeated enter is dropped`() {
        val d = PlaceVisits.decide(GEOFENCE_TRANSITION_ENTER, PlaceVisit(0, false), 20 * min)
        assertFalse(d.record)
    }

    @Test fun `an exit with no open visit is dropped`() {
        // 8 Nov 05:31: exits from the gym and the Moons, never entered.
        val d = PlaceVisits.decide(GEOFENCE_TRANSITION_EXIT, null, 0)
        assertFalse(d.record)
        assertNull(d.next)
    }

    @Test fun `an exit closes the visit and the next dwell notifies again`() {
        val exit = PlaceVisits.decide(GEOFENCE_TRANSITION_EXIT, PlaceVisit(0, true), 60 * min)
        assertTrue(exit.record)
        assertNull(exit.next)
        assertTrue(PlaceVisits.decide(GEOFENCE_TRANSITION_DWELL, exit.next, 120 * min).notify)
    }

    @Test fun `a visit left open past twelve hours counts as closed`() {
        val d = PlaceVisits.decide(GEOFENCE_TRANSITION_DWELL, PlaceVisit(0, true), PlaceVisits.STALE_AFTER_MS + 1)
        assertTrue(d.notify)
        assertEquals(PlaceVisit(PlaceVisits.STALE_AFTER_MS + 1, dwelled = true), d.next)
    }
}
