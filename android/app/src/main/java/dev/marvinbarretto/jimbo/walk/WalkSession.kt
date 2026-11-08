package dev.marvinbarretto.jimbo.walk

import dev.marvinbarretto.jimbo.telemetry.RawEvent
import dev.marvinbarretto.jimbo.telemetry.WalkCollector
import java.time.Instant

/** A stream of fixes: fused location in the app (FusedWalkLocation), a fake in tests. */
interface WalkLocationSource {
    fun start(onFix: (WalkFix) -> Unit)
    fun stop()
}

/** Plays a spot's clip: WalkAudio in the app. */
interface WalkPlayback {
    fun play(clip: String)
    fun stop()
}

/** Where walk events go: the telemetry queue in the app (RoomWalkEventSink). */
interface WalkEventSink {
    fun record(event: RawEvent)
}

/**
 * One walk: from start to stop it streams location, and the first fix within
 * 30 m of a spot plays that spot's clip and records one `walk.trigger` event.
 * Each spot plays once per session; a new session starts the count again.
 * Not thread-safe: the service drives it from the main looper.
 */
class WalkSession(
    private val spots: List<WalkSpot>,
    private val location: WalkLocationSource,
    private val playback: WalkPlayback,
    private val events: WalkEventSink,
    private val clock: () -> Instant = Instant::now,
    private val onHit: (WalkHit) -> Unit = {},
    /** Where walk.fix and walk.summary go; kept apart from [events] so the walk's own stream stays three kinds. */
    private val diagnostics: WalkEventSink? = null,
    /** Battery percent now, for the summary. */
    private val battery: () -> Int? = { null },
) {
    private var trigger: WalkTrigger? = null
    private var walkId: String? = null

    val isRunning: Boolean get() = trigger != null
    val triggered: Int get() = trigger?.fired?.size ?: lastTriggered
    private var lastTriggered = 0

    /** The running walk's id, for events recorded outside the session (audio steps). */
    val currentWalkId: String? get() = walkId

    private var fixCount = 0
    private var ignoredCount = 0
    private var lastFixAt: Instant? = null
    private var maxGapSeconds: Double? = null
    private var batteryAtStart: Int? = null

    fun start() {
        if (isRunning) return
        val now = clock()
        val id = now.toEpochMilli().toString()
        val session = WalkTrigger(spots)
        trigger = session
        walkId = id
        lastTriggered = 0
        fixCount = 0
        ignoredCount = 0
        lastFixAt = null
        maxGapSeconds = null
        batteryAtStart = battery()
        events.record(WalkCollector.started(id, spots.size, now))
        location.start { fix -> onFix(session, id, fix) }
    }

    fun stop() {
        val session = trigger ?: return
        val id = walkId ?: return
        location.stop()
        playback.stop()
        lastTriggered = session.fired.size
        trigger = null
        walkId = null
        val now = clock()
        events.record(WalkCollector.stopped(id, lastTriggered, now))
        diagnostics?.record(
            WalkCollector.summary(id, fixCount, ignoredCount, maxGapSeconds, batteryAtStart, battery(), now),
        )
    }

    private fun onFix(session: WalkTrigger, id: String, fix: WalkFix) {
        // A fix delivered after stop (or from an earlier session) is dropped.
        if (trigger !== session) return
        diagnose(session, id, fix)
        session.onFix(fix).forEach { hit ->
            playback.play(hit.spot.clip)
            events.record(WalkCollector.trigger(id, hit, fix.accuracyMetres, fix.at))
            onHit(hit)
        }
    }

    /** Records the fix before the trigger sees it, so `nearest` is the spot it could fire. */
    private fun diagnose(session: WalkTrigger, id: String, fix: WalkFix) {
        val diag = diagnostics ?: return
        val gap = lastFixAt?.let { (fix.at.toEpochMilli() - it.toEpochMilli()) / 1000.0 }
        lastFixAt = fix.at
        if (gap != null && gap > (maxGapSeconds ?: -1.0)) maxGapSeconds = gap
        val ignored = fix.accuracyMetres?.let { it > WalkTrigger.MAX_ACCURACY_M } ?: false
        fixCount++
        if (ignored) ignoredCount++
        val nearest = spots
            .filter { it.id !in session.fired }
            .map { it to WalkGeo.distanceMetres(fix.latitude, fix.longitude, it.latitude, it.longitude) }
            .minByOrNull { it.second }
        diag.record(WalkCollector.fix(id, fix.accuracyMetres, nearest?.first?.id, nearest?.second, gap, ignored, fix.at))
    }
}
