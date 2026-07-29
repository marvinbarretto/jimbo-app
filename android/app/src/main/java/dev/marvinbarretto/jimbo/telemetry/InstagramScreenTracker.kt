package dev.marvinbarretto.jimbo.telemetry

/**
 * InstagramScreenTracker — the decision logic behind InstagramScreenService.
 *
 * PURPOSE
 * Turns a firehose of raw accessibility events into three meaningful moments:
 *   - session started  — Instagram just came to the foreground
 *   - settle           — the human stopped scrolling; the screen is holding still
 *   - session ended    — no Instagram events for a while; they've moved on
 * M1 only logs these. M2 hangs the text harvest off `settle`.
 *
 * CONCEPT MAPPING (JS / Angular)
 * - This class is a **pure store / reducer**. Same shape as a Zustand store or an
 *   NgRx reducer: state in a field, events in via methods, plain data out. It
 *   imports nothing from Android on purpose — that is what makes it unit-testable
 *   without an emulator (`InstagramScreenTrackerTest`).
 * - `onSettleDeadline` / `onIdleDeadline` are **debounce callbacks**. In JS you'd
 *   write `clearTimeout(t); t = setTimeout(fn, 700)`. Android has no `setTimeout`,
 *   so the *service* owns a Handler that does removeCallbacks + postDelayed, and
 *   calls into here when the timer actually fires. The timer lives outside; the
 *   *meaning* of the timer firing lives here.
 * - Time is injected as a `nowMs` parameter rather than read from a clock. Same
 *   reason we use fake timers in Vitest — a test can say "pretend it is 3 seconds
 *   later" without sleeping.
 *
 * WHY SESSION END IS A TIMEOUT, NOT AN EVENT
 * The accessibility service is registered with `packageNames="com.instagram.android"`,
 * so the OS never delivers us events from any other app — that filter is the privacy
 * posture, enforced by the system rather than by an `if` we could get wrong. The
 * cost of that: when the user swipes out of Instagram, the event announcing the
 * *next* app belongs to that app, so we never see it. We infer "they left" from
 * silence instead. That is a deliberate trade: structurally blind beats accurate.
 */

/** The only package this service is ever allowed to observe. */
internal const val INSTAGRAM_PACKAGE = "com.instagram.android"

/**
 * How long the event stream must go quiet before we call it a settle.
 * Short enough to catch a pause mid-scroll, long enough not to fire on the gaps
 * between frames of a fling.
 */
internal const val DEFAULT_SETTLE_DELAY_MS = 700L

/**
 * How long the event stream must go quiet before we call the session over.
 * Instagram emits content-change events even when idle-ish (video, prefetch), so
 * this is generous — an early cut just splits one session into two in the log.
 */
internal const val DEFAULT_IDLE_TIMEOUT_MS = 30_000L

/**
 * Longest a burst may run before we harvest anyway, settle or no settle.
 *
 * Measured on device 2026-07-29 (M1): one burst ran 19.7s carrying 290 events
 * about 68ms apart — an autoplaying video or a fast fling. A pure "wait for
 * quiet" rule never fires during that, so anything that churns continuously is
 * invisible to it. Lowering the settle delay cannot fix a 68ms gap; only a
 * ceiling can. This bounds how much we can miss to one harvest per 5s.
 */
internal const val DEFAULT_MAX_BURST_MS = 5_000L

/** What a single accessibility event did to the tracker. */
internal data class EventOutcome(
    /** False when the event was not Instagram's — it should never happen, see rejection note below. */
    val accepted: Boolean,
    /** True only on the first event of a new foreground session. */
    val sessionStarted: Boolean,
    /** True when this event opened a new burst — the service arms the churn ceiling on it. */
    val burstStarted: Boolean,
    /** Id of the session this event landed in, or null when rejected. */
    val sessionId: Long?,
)

/** A burst of activity that has now gone quiet — the moment M2 will harvest on. */
internal data class SettleReport(
    val sessionId: Long,
    /** Events seen since the previous settle (or session start), by type. */
    val windowChanges: Int,
    val contentChanges: Int,
    /** How long the burst that just ended lasted, first event to last. */
    val burstDurationMs: Long,
    /**
     * True when the churn ceiling fired rather than the stream going quiet — the
     * screen was still changing when we harvested, so the content may be mid-flight.
     * M2 should treat a forced harvest as lower confidence than a quiet one.
     */
    val forced: Boolean,
)

/** A completed foreground session, summarised for the log. */
internal data class SessionReport(
    val sessionId: Long,
    val durationMs: Long,
    val windowChanges: Int,
    val contentChanges: Int,
    val settles: Int,
)

internal class InstagramScreenTracker(
    private val settleDelayMs: Long = DEFAULT_SETTLE_DELAY_MS,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val maxBurstMs: Long = DEFAULT_MAX_BURST_MS,
) {

    private class Session(
        val id: Long,
        val startedAtMs: Long,
        var lastEventAtMs: Long,
        var windowChanges: Int = 0,
        var contentChanges: Int = 0,
        var settles: Int = 0,
        // Burst = events since the last settle. Reset every time we settle.
        var burstOpen: Boolean = false,
        var burstStartedAtMs: Long = 0,
        var burstWindowChanges: Int = 0,
        var burstContentChanges: Int = 0,
    )

    private var nextSessionId = 1L
    private var session: Session? = null

    /** True while we believe Instagram is in the foreground. Read by the service for logging. */
    val hasActiveSession: Boolean get() = session != null

    /**
     * Feed one accessibility event in.
     *
     * @param windowStateChanged true for TYPE_WINDOW_STATE_CHANGED, false for
     *   TYPE_WINDOW_CONTENT_CHANGED. The service maps the Android int constant to
     *   this boolean so that this class stays free of Android imports.
     */
    fun onEvent(packageName: String?, windowStateChanged: Boolean, nowMs: Long): EventOutcome {
        // Belt and braces. The manifest's packageNames filter means the OS should
        // never hand us anything else; if it somehow does, we drop it rather than
        // quietly widen what this service watches.
        if (packageName != INSTAGRAM_PACKAGE) {
            return EventOutcome(
                accepted = false,
                sessionStarted = false,
                burstStarted = false,
                sessionId = null,
            )
        }

        var started = false
        val current = session ?: Session(
            id = nextSessionId++,
            startedAtMs = nowMs,
            lastEventAtMs = nowMs,
        ).also {
            session = it
            started = true
        }

        current.lastEventAtMs = nowMs
        if (windowStateChanged) current.windowChanges++ else current.contentChanges++

        var burstStarted = false
        if (!current.burstOpen) {
            current.burstOpen = true
            current.burstStartedAtMs = nowMs
            current.burstWindowChanges = 0
            current.burstContentChanges = 0
            burstStarted = true
        }
        if (windowStateChanged) current.burstWindowChanges++ else current.burstContentChanges++

        return EventOutcome(
            accepted = true,
            sessionStarted = started,
            burstStarted = burstStarted,
            sessionId = current.id,
        )
    }

    /**
     * Called when the settle timer fires. Returns a report if the stream really has
     * been quiet for `settleDelayMs`, otherwise null (a late timer that a newer
     * event has already outrun).
     */
    fun onSettleDeadline(nowMs: Long): SettleReport? {
        val current = session ?: return null
        if (!current.burstOpen) return null
        if (nowMs - current.lastEventAtMs < settleDelayMs) return null
        return closeBurst(current, endedAtMs = current.lastEventAtMs, forced = false)
    }

    /**
     * Called when the churn ceiling fires. Returns a report if the burst really has
     * been running for `maxBurstMs`, otherwise null. Unlike the settle timer this
     * one is armed once per burst and never re-armed by later events — it is a
     * ceiling on the burst, not a debounce.
     */
    fun onChurnDeadline(nowMs: Long): SettleReport? {
        val current = session ?: return null
        if (!current.burstOpen) return null
        if (nowMs - current.burstStartedAtMs < maxBurstMs) return null
        return closeBurst(current, endedAtMs = nowMs, forced = true)
    }

    private fun closeBurst(current: Session, endedAtMs: Long, forced: Boolean): SettleReport {
        val report = SettleReport(
            sessionId = current.id,
            windowChanges = current.burstWindowChanges,
            contentChanges = current.burstContentChanges,
            burstDurationMs = endedAtMs - current.burstStartedAtMs,
            forced = forced,
        )
        current.settles++
        // Closing the burst is what lets the next event open a fresh one — which is
        // how continuous churn produces a harvest every maxBurstMs rather than none.
        current.burstOpen = false
        return report
    }

    /**
     * Called when the idle timer fires. Returns the finished session's summary if
     * Instagram really has been silent for `idleTimeoutMs`, otherwise null.
     */
    fun onIdleDeadline(nowMs: Long): SessionReport? {
        val current = session ?: return null
        if (nowMs - current.lastEventAtMs < idleTimeoutMs) return null

        session = null
        return SessionReport(
            sessionId = current.id,
            durationMs = current.lastEventAtMs - current.startedAtMs,
            windowChanges = current.windowChanges,
            contentChanges = current.contentChanges,
            settles = current.settles,
        )
    }

    /**
     * Drop any in-flight session without reporting it — used when the service is
     * unbound (user toggled it off, or the system reclaimed it).
     */
    fun reset() {
        session = null
    }
}
