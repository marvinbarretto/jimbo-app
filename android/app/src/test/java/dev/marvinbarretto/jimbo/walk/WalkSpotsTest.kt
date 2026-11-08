package dev.marvinbarretto.jimbo.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSpotsTest {

    @Test fun `parses several spots joined by semicolons`() {
        val spots = WalkSpot.parseList("market|51.6560,-0.3960|story_market; Church | 51.6570,-0.3970 | story-church.mp3 ;park|51.66,-0.41|park")
        assertEquals(listOf("market", "church", "park"), spots.map { it.id })
        assertEquals(51.6560, spots[0].latitude, 0.0)
        assertEquals(-0.3960, spots[0].longitude, 0.0)
        assertEquals("story_market", spots[0].clip)
        assertEquals("story-church.mp3", spots[1].clip)
    }

    @Test fun `skips malformed entries without guessing`() {
        val spots = WalkSpot.parseList(
            "ok|51.1,-0.4|a;Bad Id|51.1,-0.4|b;noclip|51.1,-0.4|;nocoords||c;far|91,0|d;" +
                "wide|51,-181|e;three|51,0,30|f;extra|51,0|g|h;badclip|51,0|no spaces;ok|52,0|dup"
        )
        assertEquals(listOf("ok"), spots.map { it.id })
        assertEquals(51.1, spots[0].latitude, 0.0)
    }

    @Test fun `blank config gives no spots`() {
        assertTrue(WalkSpot.parseList("").isEmpty())
        assertTrue(WalkSpot.parseList(" ; ").isEmpty())
    }
}
