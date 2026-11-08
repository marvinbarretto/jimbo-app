package dev.marvinbarretto.jimbo.walk

import android.media.session.PlaybackState

/** Another app's media session: its package and play state (playing, paused, stopped, other). */
data class WalkMediaSession(val pkg: String, val state: String)

/** Every active media session at one moment, or why they could not be read. */
data class WalkMediaSnapshot(val sessions: List<WalkMediaSession>, val error: String? = null)

/**
 * Samples other apps' media sessions around each clip (walk 2, HW-7849), to
 * answer "did the podcast pause, and did it play again": at clip_start and
 * 2 s after (after_start), at the clip's end, failure (clip_end) or skip
 * (skip), and 3 s after focus is handed back (after_abandon). Each sample goes
 * to [record] as walk.media under the walk id current when it was asked for.
 * Driven from WalkAudio's steps, on the walk's thread.
 */
class WalkMediaWatch(
    private val probe: () -> WalkMediaSnapshot,
    private val schedule: WalkSchedule,
    private val walkId: () -> String?,
    private val record: (walkId: String?, point: String, clip: String?, snapshot: WalkMediaSnapshot) -> Unit,
) {
    private var lastClip: String? = null

    fun onStep(phase: String, clip: String?) {
        when (phase) {
            "clip_start" -> {
                lastClip = clip
                sample("clip_start", clip)
                later(AFTER_START_MS, "after_start", clip)
            }
            "clip_end", "clip_error" -> sample("clip_end", clip)
            "clip_skipped" -> sample("skip", clip)
            "focus_abandoned" -> later(AFTER_ABANDON_MS, "after_abandon", clip ?: lastClip)
        }
    }

    private fun sample(point: String, clip: String?, walk: String? = walkId()) {
        record(walk, point, clip, probe())
    }

    private fun later(delayMs: Long, point: String, clip: String?) {
        val walk = walkId()
        schedule.after(delayMs) { sample(point, clip, walk) }
    }

    companion object {
        const val AFTER_START_MS = 2_000L
        const val AFTER_ABANDON_MS = 3_000L

        fun playbackState(state: Int?): String = when (state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_STOPPED -> "stopped"
            else -> "other"
        }
    }
}
