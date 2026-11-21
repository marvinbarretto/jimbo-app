package dev.marvinbarretto.jimbo.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Invented coordinates throughout: never his places.
class RemoteFencesTest {

    private val json = """
        {"fences":[
          {"id":"ent_aaaa0001","name":"A Cafe","lat":51.0009,"lng":0.0,"radius_m":40,"pin_checked":true,"distance_m":100},
          {"id":"ent_aaaa0002","name":"A Park","lat":51.018,"lng":0.0,"radius_m":300,"pin_checked":false,"distance_m":2000},
          {"id":"BAD ID","lat":51.0,"lng":0.0,"radius_m":40},
          {"id":"ent_aaaa0003","lat":51.0,"radius_m":40}
        ],"active_total":4}
    """.trimIndent()

    @Test fun parsesFencesWithNamesAndSkipsMalformedOnes() {
        val fences = RemoteFences.parse(json)
        assertEquals(listOf("ent_aaaa0001", "ent_aaaa0002"), fences.map { it.id })
        assertEquals("A Cafe", fences[0].name)
        assertEquals(300f, fences[1].radiusMetres, 0f)
        assertNull(fences[0].label) // no label: a remote fence records, never notifies
        assertFalse(fences[0].notifiesOnDwell)
        assertEquals(Place.OTHER, fences[0].place)
    }

    @Test fun dropsARemoteFenceThatAConfiguredOneAlreadyCovers() {
        val gym = PlaceGeofence("gym", 51.0, 0.0, 100f, "Gym")
        val nearGym = PlaceGeofence("ent_aaaa0009", 51.0003, 0.0, 40f) // about 33 m away
        val elsewhere = PlaceGeofence("ent_aaaa0010", 51.01, 0.0, 40f)
        assertEquals(listOf("gym", "ent_aaaa0010"), RemoteFences.merge(listOf(gym), listOf(nearGym, elsewhere)).map { it.id })
    }

    @Test fun neverPassesAndroidsCap() {
        val configured = listOf(PlaceGeofence("home", 52.0, 1.0, 150f))
        val remote = (1..150).map { PlaceGeofence("ent_r%07d".format(it), 51.0 + it * 0.01, 0.0, 40f) }
        val merged = RemoteFences.merge(configured, remote)
        assertEquals(RemoteFences.ANDROID_MAX, merged.size)
        assertEquals("home", merged.first().id)
    }

    @Test fun isDueWhenNeverPickedOldOrMovedFar() {
        val pick = RemoteFences.Pick(emptyList(), atMs = 1_000_000, lat = 51.0, lng = 0.0)
        assertTrue(RemoteFences.isDue(1_000_000, null, 51.0, 0.0))
        assertFalse(RemoteFences.isDue(1_000_000 + 60_000, pick, 51.001, 0.0))
        assertTrue(RemoteFences.isDue(1_000_000 + RemoteFences.REFRESH_AFTER_MS, pick, 51.0, 0.0))
        assertTrue(RemoteFences.isDue(1_000_000 + 60_000, pick, 51.03, 0.0)) // about 3.3 km
    }
}
