package dev.marvinbarretto.jimbo.walk

import dev.marvinbarretto.jimbo.telemetry.WalkCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Opt-in "More" (HW-7849): a short clip plays unasked; when it ends and a
 * `<clip>-more` file exists, the notification offers More for 60 s. Walk 1:
 * "it was just thrust upon us and didn't get a chance to opt in".
 */
class WalkChoiceTest {
    private val schedule = FakeSchedule()
    private val played = mutableListOf<String>()
    private val choices = mutableListOf<Triple<String, String, String?>>()
    private var changes = 0
    private val withMore = setOf("story_market-more", "story_church-more")
    private val choice = WalkChoice(
        hasClip = { it in withMore },
        play = { played += it },
        schedule = schedule,
        record = { what, clip, reason -> choices += Triple(what, clip, reason) },
        changed = { changes++ },
    )

    @Test fun `a short clip that has a longer telling offers More when it ends`() {
        choice.onStep("clip_start", "story_market")
        choice.onStep("clip_end", "story_market")
        assertEquals("story_market", choice.offered)
        assertEquals(listOf(Triple("offered", "story_market", null)), choices)
        assertTrue(changes > 0)
    }

    @Test fun `More plays the longer telling`() {
        choice.onStep("clip_end", "story_market")
        assertTrue(choice.take())
        assertEquals(listOf("story_market-more"), played)
        assertEquals(Triple("taken", "story_market", null), choices.last())
        assertNull(choice.offered)
    }

    @Test fun `Skip declines the offer`() {
        choice.onStep("clip_end", "story_market")
        assertTrue(choice.decline())
        assertEquals(Triple("declined", "story_market", null), choices.last())
        assertNull(choice.offered)
        assertTrue(played.isEmpty())
    }

    @Test fun `the offer lapses after 60 s`() {
        choice.onStep("clip_end", "story_market")
        schedule.advance(WalkChoice.OFFER_MS - 1)
        assertEquals("story_market", choice.offered)
        schedule.advance(1)
        assertNull(choice.offered)
        assertEquals(Triple("expired", "story_market", null), choices.last())
        assertFalse(choice.take())
        assertTrue(played.isEmpty())
    }

    @Test fun `taking the offer cancels its timer`() {
        choice.onStep("clip_end", "story_market")
        choice.take()
        schedule.advance(WalkChoice.OFFER_MS)
        assertEquals(listOf("offered", "taken"), choices.map { it.first })
    }

    @Test fun `no longer telling, no offer`() {
        choice.onStep("clip_end", "story_bridge")
        assertNull(choice.offered)
        assertTrue(choices.isEmpty())
    }

    @Test fun `a skipped or failed clip offers nothing`() {
        choice.onStep("clip_skipped", "story_market")
        choice.onStep("clip_error", "story_market")
        assertNull(choice.offered)
        assertTrue(choices.isEmpty())
    }

    @Test fun `the longer telling ending offers nothing more`() {
        choice.onStep("clip_end", "story_market-more")
        assertNull(choice.offered)
        assertTrue(choices.isEmpty())
    }

    @Test fun `a new clip starting closes an open offer`() {
        choice.onStep("clip_end", "story_market")
        choice.onStep("clip_start", "story_church")
        assertNull(choice.offered)
        assertEquals(Triple("expired", "story_market", "superseded"), choices.last())
    }

    @Test fun `the walk stopping closes an open offer`() {
        choice.onStep("clip_end", "story_market")
        choice.end()
        assertNull(choice.offered)
        assertEquals(Triple("expired", "story_market", "walk_stopped"), choices.last())
        assertEquals(0, schedule.pending)
        choice.end()
        assertEquals(2, choices.size)
    }

    @Test fun `with no offer open, More and Skip do nothing`() {
        assertFalse(choice.take())
        assertFalse(choice.decline())
        assertTrue(choices.isEmpty())
    }

    @Test fun `a choice event names the walk, the choice and the clip`() {
        val t0 = Instant.parse("2026-11-09T10:00:00Z")
        val offered = WalkCollector.choice("w1", "offered", "story_market", null, t0)
        assertEquals("walk", offered.collector)
        assertEquals("walk.choice", offered.type)
        assertEquals(mapOf("walk" to "w1", "choice" to "offered", "clip" to "story_market"), offered.payload)
        val lapsed = WalkCollector.choice("w1", "expired", "story_market", "superseded", t0)
        assertEquals("superseded", lapsed.payload?.get("reason"))
    }
}
