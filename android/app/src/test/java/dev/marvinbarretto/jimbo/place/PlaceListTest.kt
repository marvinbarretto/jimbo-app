package dev.marvinbarretto.jimbo.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceListTest {

    @Test fun parsesAListWithLabelsAndRadii() {
        val fences = PlaceGeofence.parseList("home|51.1,-0.4,100|; pub|51.2,-0.41,90|The Pub ;gym|51.3,-0.42|Gym")
        assertEquals(listOf("home", "pub", "gym"), fences.map { it.id })
        assertNull(fences[0].label)
        assertEquals("The Pub", fences[1].label)
        assertEquals(90f, fences[1].radiusMetres, 0f)
        assertEquals(PlaceGeofence.DEFAULT_RADIUS_M, fences[2].radiusMetres, 0f)
    }

    @Test fun mapsHomeAndGymToTheShellsNamedPlaces() {
        val fences = PlaceGeofence.parseList("home|51.1,-0.4|;gym|51.3,-0.42|Gym;pub|51.2,-0.41|Pub")
        assertEquals(listOf(Place.HOME, Place.GYM, Place.OTHER), fences.map { it.place })
    }

    @Test fun onlyLabelledNonGymPlacesNotifyOnDwell() {
        val fences = PlaceGeofence.parseList("home|51.1,-0.4|;gym|51.3,-0.42|Gym;pub|51.2,-0.41|Pub")
        assertFalse(fences[0].notifiesOnDwell)
        assertFalse(fences[1].notifiesOnDwell)
        assertTrue(fences[2].notifiesOnDwell)
    }

    @Test fun skipsMalformedEntriesWithoutGuessing() {
        val fences = PlaceGeofence.parseList(
            "ok|51.1,-0.4|A;Bad Id|51.1,-0.4|B;nocoords||C;far|91,0|D;neg|51,0,-5|E;r|51,0,abc|F;ok|52,0|dup"
        )
        assertEquals(listOf("ok"), fences.map { it.id })
        assertEquals(51.1, fences[0].latitude, 0.0)
    }

    @Test fun blankConfigFencesNothing() {
        assertTrue(PlaceGeofence.fromConfig("", "", "").isEmpty())
    }

    @Test fun legacyKeysStillWorkAndTheListWins() {
        val fences = PlaceGeofence.fromConfig("51.1,-0.4", "51.3,-0.42,120", "gym|51.9,-0.5,80|Gym")
        assertEquals(listOf("home", "gym"), fences.map { it.id })
        assertEquals(51.9, fences.first { it.id == "gym" }.latitude, 0.0)
        assertEquals(80f, fences.first { it.id == "gym" }.radiusMetres, 0f)
    }
}
