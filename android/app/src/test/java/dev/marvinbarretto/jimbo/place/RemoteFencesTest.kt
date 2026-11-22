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
        assertNull(fences[0].label)
        assertEquals(emptyList<PlaceAction>(), fences[0].actions) // no actions: it only records
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

    @Test fun readsRolesAndActionsAndSkipsActionsItCannotRun() {
        val fences = RemoteFences.parse("""
            {"fences":[
              {"id":"ent_bbbb0001","name":"Home","lat":51.0,"lng":0.0,"radius_m":75,"role":"home","actions":[]},
              {"id":"ent_bbbb0002","name":"Moon Under Water","lat":51.01,"lng":0.0,"radius_m":40,"role":null,
               "actions":[{"when":"stay","do":"quip","title":"At The Office","list":"pub-quips"},{"when":"stay","do":"quip"},{"when":"soon","do":"notify"}]},
              {"id":"ent_bbbb0003","name":"Gym","lat":51.02,"lng":0.0,"radius_m":40,"role":"gym","actions":[{"when":"arrive","do":"gym_offer"}]}
            ]}
        """.trimIndent())
        assertEquals(listOf(Place.HOME, Place.OTHER, Place.GYM), fences.map { it.place })
        assertEquals(listOf(PlaceAction(PlaceAction.When.STAY, PlaceAction.Do.QUIP, title = "At The Office", list = "pub-quips")), fences[1].actions)
        assertEquals(listOf(PlaceAction(PlaceAction.When.ARRIVE, PlaceAction.Do.GYM_OFFER)), fences[2].actions)
    }

    @Test fun onceJimboNamesAHomeOrGymItOwnsEveryFence() {
        val configured = PlaceGeofence.parseList("home|51.0,0.0,150|;gym|51.02,0.0,100|Gym;moons|51.01,0.0,100|The Office|pub-quips")
        val jimboHome = PlaceGeofence("ent_bbbb0001", 51.0, 0.0, 75f, name = "Home", role = Place.HOME, remoteActions = emptyList())
        val cafe = PlaceGeofence("ent_bbbb0004", 51.0102, 0.0, 30f, name = "Cafe", remoteActions = emptyList()) // 20 m from the Moons
        assertEquals(listOf("ent_bbbb0001", "ent_bbbb0004"), RemoteFences.merge(configured, listOf(jimboHome, cafe)).map { it.id })
        // Before Jimbo names one, the settings file stays, and a remote fence on top of it is dropped as before.
        assertEquals(listOf("home", "gym", "moons"), RemoteFences.merge(configured, listOf(cafe)).map { it.id })
    }
}
