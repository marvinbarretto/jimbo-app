package dev.marvinbarretto.jimbo.telemetry

import android.app.usage.UsageEvents
import java.time.Duration
import java.time.Instant

/** One stretch of one app in the foreground, ended by another app, home, the lock screen or the screen going off. */
internal data class AppSession(val pkg: String, val start: Instant, val end: Instant) {
    val seconds: Long get() = Duration.between(start, end).seconds
}

internal data class ScreenSpan(val start: Instant, val end: Instant)

internal data class UsageSessions(
    val sessions: List<AppSession>,
    val unlocks: List<Instant>,
    val screenOn: List<ScreenSpan>,
    /** The earliest start still open at the end of the input: read again from here next time. Null when nothing is open. */
    val resumeFrom: Instant?,
)

/**
 * Turns Android's raw usage events into app sessions, unlocks and screen-on spans.
 *
 * The old daily total paired ACTIVITY_RESUMED with ACTIVITY_PAUSED and nothing
 * else, so an app left open when the screen went off kept counting until it was
 * next paused: the launcher showed 325 minutes on a day with 102 minutes of
 * screen. Here a session also ends when another app comes up, when you go home
 * ([ignore] holds the launcher and System UI), when the lock screen shows and
 * when the screen goes off.
 *
 * An app pausing and resuming itself within [pauseGrace] (moving between its own
 * screens) stays one session. Sessions still open at the end are not returned;
 * [UsageSessions.resumeFrom] says where to start reading next time.
 */
internal fun buildUsageSessions(
    events: List<UsageEventRow>,
    ignore: Set<String>,
    pauseGrace: Duration = Duration.ofSeconds(5),
): UsageSessions {
    val sessions = mutableListOf<AppSession>()
    val unlocks = mutableListOf<Instant>()
    val screens = mutableListOf<ScreenSpan>()
    var curPkg: String? = null
    var curStart: Instant? = null
    var pausedAt: Instant? = null
    var screenSince: Instant? = null

    fun close(at: Instant) {
        val pkg = curPkg
        val start = curStart
        if (pkg != null && start != null && at.isAfter(start)) sessions += AppSession(pkg, start, at)
        curPkg = null
        curStart = null
        pausedAt = null
    }

    events.sortedBy { it.timestamp }.forEach { e ->
        val ts = e.timestamp
        when (e.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                val pkg = e.packageName ?: return@forEach
                val paused = pausedAt
                if (pkg == curPkg && (paused == null || Duration.between(paused, ts) <= pauseGrace)) {
                    pausedAt = null
                } else {
                    close(paused ?: ts)
                    if (pkg !in ignore) {
                        curPkg = pkg
                        curStart = ts
                    }
                }
            }
            UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                if (e.packageName == curPkg && pausedAt == null) pausedAt = ts
            UsageEvents.Event.KEYGUARD_SHOWN -> close(pausedAt ?: ts)
            UsageEvents.Event.SCREEN_INTERACTIVE -> if (screenSince == null) screenSince = ts
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                close(pausedAt ?: ts)
                screenSince?.let { if (ts.isAfter(it)) screens += ScreenSpan(it, ts) }
                screenSince = null
            }
            UsageEvents.Event.KEYGUARD_HIDDEN -> unlocks += ts
        }
    }

    return UsageSessions(sessions, unlocks, screens, listOfNotNull(curStart, screenSince).minOrNull())
}
