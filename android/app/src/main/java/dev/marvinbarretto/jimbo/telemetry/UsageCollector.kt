package dev.marvinbarretto.jimbo.telemetry

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private const val TAG = "UsageCollector"
private const val PREFS = "usage_collector"
private const val TOP_APP_LIMIT = 20
private const val SESSION_CURSOR = "session_cursor_ms"
private val SCREEN_LOOKBACK: Duration = Duration.ofHours(12)
private val SESSION_BACKFILL: Duration = Duration.ofDays(7)

class UsageCollector(
    private val context: Context
) : Collector {
    override val id: String = "usage"
    override val defaultEnabled: Boolean = true
    override val cadence: CollectorCadence = CollectorCadence.Periodic(Duration.ofHours(1))

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
    private val packageManager = context.packageManager

    override suspend fun collect(window: TimeWindow): List<RawEvent> {
        if (!hasUsageAccess(context)) {
            Log.d(TAG, "Usage access not granted; skipping collection")
            return emptyList()
        }

        val manager = usageStatsManager ?: return emptyList()
        val events = mutableListOf<RawEvent>()

        collectScreenSession(manager, window)?.let { events += it }
        collectDailyAppUsage(manager, window.end)?.let { events += it }
        events += collectSessions(manager, window.end)

        Log.d(TAG, "Collected ${events.size} usage events")
        return events
    }

    private fun collectScreenSession(
        usageStatsManager: UsageStatsManager,
        window: TimeWindow
    ): RawEvent? {
        // Read back past the window start so a screen-on that began before it is seen.
        val summary = summarizeUsageWindow(
            readUsageEvents(usageStatsManager, window.start.minus(SCREEN_LOOKBACK), window.end),
            window.start,
            window.end
        )

        if (summary.totalOnSeconds <= 0.0 && summary.unlockCount == 0) {
            return null
        }

        return RawEvent(
            collector = id,
            type = "screen_session",
            ts = window.start,
            tsEnd = window.end,
            value = summary.totalOnSeconds,
            unit = "total_on_seconds",
            source = "usage_stats",
            payload = mapOf(
                "unlock_count" to summary.unlockCount,
                "first_unlock_ts" to summary.firstUnlockTs?.toString(),
                "last_unlock_ts" to summary.lastUnlockTs?.toString()
            )
        )
    }

    private fun collectSessions(usageStatsManager: UsageStatsManager, now: Instant): List<RawEvent> =
        collectSessionsImpl(context, usageStatsManager, now)

    private fun collectDailyAppUsage(
        usageStatsManager: UsageStatsManager,
        now: Instant
    ): RawEvent? {
        val dayToEmit = previousLocalDate(now)
        val lastEmitted = prefs.getString("last_daily_usage_date", null)
        if (lastEmitted == dayToEmit.toString()) {
            return null
        }

        val zone = ZoneId.systemDefault()
        val start = dayToEmit.atStartOfDay(zone).toInstant()
        val end = dayToEmit.plusDays(1).atStartOfDay(zone).toInstant()
        val events = readUsageEvents(usageStatsManager, start, end)
        val topApps = topAppUsage(events, ignoredUsagePackages(context)) { pkg -> packageLabel(packageManager, pkg) }
        if (topApps.isEmpty()) {
            return null
        }

        prefs.edit().putString("last_daily_usage_date", dayToEmit.toString()).apply()
        return RawEvent(
            collector = id,
            type = "app_usage_daily",
            ts = start,
            tsEnd = end,
            source = "usage_stats",
            payload = mapOf(
                "top_apps" to topApps.map {
                    mapOf(
                        "pkg" to it.pkg,
                        "label" to it.label,
                        "foreground_seconds" to it.foregroundSeconds
                    )
                }
            )
        )
    }
}


/**
 * Every app session (app, start, end), every unlock and every screen-on span
 * since the last run, so "what did he pick the phone up for" has an answer to
 * the second. Reads from a cursor: the first run goes back [SESSION_BACKFILL]
 * (Android keeps about a week); later runs start at the earliest session still
 * open last time. Re-reads are harmless: event ids are content-derived and the
 * server drops duplicates.
 */
private fun UsageCollector.collectSessionsImpl(
    context: Context,
    usageStatsManager: UsageStatsManager,
    now: Instant,
): List<RawEvent> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val floor = now.minus(SESSION_BACKFILL)
    val from = prefs.getLong(SESSION_CURSOR, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        ?.takeIf { it.isAfter(floor) } ?: floor
    val built = buildUsageSessions(readUsageEvents(usageStatsManager, from, now), ignoredUsagePackages(context))
    prefs.edit().putLong(SESSION_CURSOR, (built.resumeFrom ?: now).toEpochMilli()).apply()

    val pm = context.packageManager
    val labels = mutableMapOf<String, String>()
    val out = mutableListOf<RawEvent>()
    built.sessions.filter { it.seconds >= 1 }.forEach { session ->
        out += RawEvent(
            collector = id, type = "app_session", ts = session.start, tsEnd = session.end,
            value = session.seconds.toDouble(), unit = "seconds", source = "usage_events",
            payload = mapOf("pkg" to session.pkg, "label" to labels.getOrPut(session.pkg) { packageLabel(pm, session.pkg) }),
        )
    }
    built.unlocks.forEach { out += RawEvent(collector = id, type = "unlock", ts = it, source = "usage_events") }
    built.screenOn.forEach { span ->
        out += RawEvent(
            collector = id, type = "screen_on", ts = span.start, tsEnd = span.end,
            value = Duration.between(span.start, span.end).seconds.toDouble(), unit = "seconds", source = "usage_events",
        )
    }
    Log.d(TAG, "Sessions from $from: ${built.sessions.size} app, ${built.unlocks.size} unlocks, ${built.screenOn.size} screen-on")
    return out
}

/** The launcher and System UI: being on them is not using an app. */
internal fun ignoredUsagePackages(context: Context): Set<String> {
    val home = context.packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
    )?.activityInfo?.packageName
    return setOfNotNull("com.android.systemui", home)
}

internal data class UsageEventRow(
    val packageName: String?,
    val eventType: Int,
    val timestamp: Instant
)

internal data class ScreenSessionSummary(
    val totalOnSeconds: Double,
    val unlockCount: Int,
    val firstUnlockTs: Instant?,
    val lastUnlockTs: Instant?
)

internal data class AppUsageSummary(
    val pkg: String,
    val label: String,
    val foregroundSeconds: Double
)

internal fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

/**
 * Screen-on seconds and unlocks inside [windowStart]..[windowEnd]. [events] may
 * start earlier (the collector reads [SCREEN_LOOKBACK] back): a screen-on that
 * began before the window, or is still going at its end, counts for the part
 * inside it. Counting only on/off pairs seen inside the window read 0 for a
 * 2h window that sat wholly within one 3h screen-on (14 Nov 2026).
 */
internal fun summarizeUsageWindow(
    events: List<UsageEventRow>,
    windowStart: Instant,
    windowEnd: Instant
): ScreenSessionSummary {
    var interactiveSince: Instant? = null
    var totalOnSeconds = 0.0
    var unlockCount = 0
    var firstUnlock: Instant? = null
    var lastUnlock: Instant? = null
    var sawScreenEvent = false

    fun addOn(from: Instant, to: Instant) {
        val a = maxOf(from, windowStart)
        val b = minOf(to, windowEnd)
        if (b.isAfter(a)) totalOnSeconds += Duration.between(a, b).seconds.toDouble()
    }

    events.sortedBy { it.timestamp }.forEach { event ->
        when (event.eventType) {
            UsageEvents.Event.SCREEN_INTERACTIVE -> {
                sawScreenEvent = true
                interactiveSince = interactiveSince ?: event.timestamp
            }

            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                // First screen event is an "off": it was on since before the events we have.
                val start = interactiveSince ?: if (!sawScreenEvent) windowStart else null
                sawScreenEvent = true
                if (start != null && !event.timestamp.isBefore(start)) addOn(start, event.timestamp)
                interactiveSince = null
            }

            UsageEvents.Event.KEYGUARD_HIDDEN -> {
                if (!event.timestamp.isBefore(windowStart) && event.timestamp.isBefore(windowEnd)) {
                    unlockCount += 1
                    firstUnlock = firstUnlock ?: event.timestamp
                    lastUnlock = event.timestamp
                }
            }
        }
    }
    interactiveSince?.let { addOn(it, windowEnd) }

    return ScreenSessionSummary(
        totalOnSeconds = totalOnSeconds,
        unlockCount = unlockCount,
        firstUnlockTs = firstUnlock,
        lastUnlockTs = lastUnlock
    )
}

/**
 * The day's top apps by foreground time, from the same sessions as
 * app_session events: clipped at screen-off and lock, launcher excluded.
 */
internal fun topAppUsage(
    events: List<UsageEventRow>,
    ignore: Set<String> = emptySet(),
    resolveLabel: (String) -> String
): List<AppUsageSummary> =
    buildUsageSessions(events, ignore).sessions
        .groupBy { it.pkg }
        .mapValues { (_, list) -> list.sumOf { it.seconds } }
        .filter { it.value > 0 }
        .entries
        .sortedByDescending { it.value }
        .take(TOP_APP_LIMIT)
        .map { (pkg, seconds) -> AppUsageSummary(pkg = pkg, label = resolveLabel(pkg), foregroundSeconds = seconds.toDouble()) }

internal fun previousLocalDate(now: Instant) =
    now.atZone(ZoneId.systemDefault()).toLocalDate().minusDays(1)

private fun readUsageEvents(
    usageStatsManager: UsageStatsManager,
    start: Instant,
    end: Instant
): List<UsageEventRow> {
    val usageEvents = usageStatsManager.queryEvents(start.toEpochMilli(), end.toEpochMilli())
    val rows = mutableListOf<UsageEventRow>()
    val event = UsageEvents.Event()
    while (usageEvents.hasNextEvent()) {
        usageEvents.getNextEvent(event)
        rows += UsageEventRow(
            packageName = event.packageName,
            eventType = event.eventType,
            timestamp = Instant.ofEpochMilli(event.timeStamp)
        )
    }
    return rows
}

private fun packageLabel(packageManager: PackageManager, pkg: String): String {
    return try {
        val applicationInfo = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(applicationInfo).toString()
    } catch (_: Exception) {
        pkg
    }
}
