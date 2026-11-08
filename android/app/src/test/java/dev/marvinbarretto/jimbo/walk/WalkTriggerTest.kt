package dev.marvinbarretto.jimbo.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkTriggerTest {
    private val market = WalkSpot("market", 51.6560, -0.3960, "story_market")
    private val church = WalkSpot("church", 51.6600, -0.3960, "story_church")

    @Test fun `distance is plain haversine metres`() {
        // One degree of latitude on the mean-radius sphere.
        assertEquals(111_195.0, WalkGeo.distanceMetres(51.0, 0.0, 52.0, 0.0), 1.0)
        assertEquals(0.0, WalkGeo.distanceMetres(51.0, -0.4, 51.0, -0.4), 0.0)
    }

    @Test fun `fires inside 30 m, at 29_9 m`() {
        val hits = WalkTrigger(listOf(market)).onFix(northOf(market, 29.9))
        assertEquals(listOf("market"), hits.map { it.spot.id })
        assertEquals(29.9, hits[0].distanceMetres, 0.01)
    }

    @Test fun `does not fire at 35 m or just past 30 m`() {
        val trigger = WalkTrigger(listOf(market))
        assertTrue(trigger.onFix(northOf(market, 35.0)).isEmpty())
        assertTrue(trigger.onFix(northOf(market, 30.5)).isEmpty())
    }

    @Test fun `the radius is 30 m`() {
        assertEquals(30.0, WalkTrigger.RADIUS_M, 0.0)
    }

    @Test fun `fires once per spot`() {
        val trigger = WalkTrigger(listOf(market, church))
        assertEquals(1, trigger.onFix(northOf(market, 10.0)).size)
        assertTrue(trigger.onFix(northOf(market, 2.0)).isEmpty())
        assertTrue(trigger.onFix(northOf(market, 40.0)).isEmpty())
        assertTrue(trigger.onFix(northOf(market, 5.0)).isEmpty())
        assertEquals(listOf("church"), trigger.onFix(northOf(church, 12.0)).map { it.spot.id })
        assertEquals(setOf("market", "church"), trigger.fired)
    }

    @Test fun `a fix too vague to place you within 30 m fires nothing`() {
        val trigger = WalkTrigger(listOf(market))
        assertTrue(trigger.onFix(northOf(market, 5.0, accuracyMetres = 80f)).isEmpty())
        assertEquals(1, trigger.onFix(northOf(market, 5.0, accuracyMetres = 12f)).size)
    }

    @Test fun `two spots in range fire nearest first`() {
        val near = WalkSpot("near", 51.6560, -0.3960, "a")
        val far = WalkSpot("far", 51.6560 + 20.0 / 111_195.0, -0.3960, "b")
        val hits = WalkTrigger(listOf(far, near)).onFix(northOf(near, 3.0))
        assertEquals(listOf("near", "far"), hits.map { it.spot.id })
    }
}
