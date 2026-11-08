package dev.marvinbarretto.jimbo.walk

import android.media.session.PlaybackState
import dev.marvinbarretto.jimbo.telemetry.WalkCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

/**
 * Other apps' media state around each clip (HW-7849): did the podcast pause
 * when the clip started, and play again after focus went back? Walk 1 could
 * only guess from Spotify's notification updates.
 */
class WalkMediaWatchTest {
    private val schedule = FakeSchedule()
    private var snapshot = WalkMediaSnapshot(listOf(WalkMediaSession("com.spotify.music", "playing")))
    private var walkId: String? = "w1"
    private val samples = mutableListOf<Sample>()

    private data class Sample(val walk: String?, val point: String, val clip: String?, val snapshot: WalkMediaSnapshot, val at: Long)

    private val watch = WalkMediaWatch(
        probe = { snapshot },
        schedule = schedule,
        walkId = { walkId },
        record = { walk, point, clip, snap -> samples += Sample(walk, point, clip, snap, schedule.now) },
    )

    @Test fun `a clip start is sampled at once and 2 s later`() {
        watch.onStep("clip_start", "a")
        snapshot = WalkMediaSnapshot(listOf(WalkMediaSession("com.spotify.music", "paused")))
        schedule.advance(2_000)
        assertEquals(listOf("clip_start" to 0L, "after_start" to 2_000L), samples.map { it.point to it.at })
        assertEquals("playing", samples[0].snapshot.sessions.single().state)
        assertEquals("paused", samples[1].snapshot.sessions.single().state)
        assertEquals("a", samples[1].clip)
    }

    @Test fun `a clip end, failure or skip is sampled at once`() {
        watch.onStep("clip_end", "a")
        watch.onStep("clip_error", "b")
        watch.onStep("clip_skipped", "c")
        assertEquals(listOf("clip_end", "clip_end", "skip"), samples.map { it.point })
        assertEquals(listOf("a", "b", "c"), samples.map { it.clip })
    }

    @Test fun `handing focus back is sampled 3 s later, naming the last clip`() {
        watch.onStep("clip_start", "a")
        schedule.advance(2_000)
        samples.clear()
        watch.onStep("clip_end", "a")
        watch.onStep("focus_abandoned", null)
        schedule.advance(2_999)
        assertEquals(listOf("clip_end"), samples.map { it.point })
        schedule.advance(1)
        assertEquals("after_abandon" to "a", samples.last().point to samples.last().clip)
    }

    @Test fun `a late sample keeps the walk id it was scheduled under`() {
        watch.onStep("focus_abandoned", null)
        walkId = null
        schedule.advance(3_000)
        assertEquals("w1", samples.single().walk)
    }

    @Test fun `other steps take no sample`() {
        listOf("focus_granted", "queued", "focus_lost", "focus_regained", "pause_expired").forEach { watch.onStep(it, "a") }
        schedule.advance(10_000)
        assertEquals(emptyList<Sample>(), samples)
    }

    @Test fun `playback states are named`() {
        assertEquals("playing", WalkMediaWatch.playbackState(PlaybackState.STATE_PLAYING))
        assertEquals("paused", WalkMediaWatch.playbackState(PlaybackState.STATE_PAUSED))
        assertEquals("stopped", WalkMediaWatch.playbackState(PlaybackState.STATE_STOPPED))
        assertEquals("other", WalkMediaWatch.playbackState(PlaybackState.STATE_BUFFERING))
        assertEquals("other", WalkMediaWatch.playbackState(null))
    }

    @Test fun `a media event lists each session's package and state`() {
        val t0 = Instant.parse("2026-11-09T10:00:00Z")
        val event = WalkCollector.media(
            "w1", "after_start", "a",
            WalkMediaSnapshot(listOf(WalkMediaSession("com.spotify.music", "paused"))), t0,
        )
        assertEquals("walk", event.collector)
        assertEquals("walk.media", event.type)
        assertEquals(
            mapOf(
                "walk" to "w1", "point" to "after_start", "clip" to "a",
                "sessions" to listOf(mapOf("package" to "com.spotify.music", "state" to "paused")),
            ),
            event.payload,
        )
    }

    @Test fun `no notification access is recorded as an error, not as silence`() {
        val t0 = Instant.parse("2026-11-09T10:00:00Z")
        val event = WalkCollector.media("w1", "clip_start", "a", WalkMediaSnapshot(emptyList(), error = "no_notification_access"), t0)
        assertEquals("no_notification_access", event.payload?.get("error"))
        assertFalse(event.payload!!.containsKey("sessions"))
    }
}
