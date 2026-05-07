package dev.marvinbarretto.steps.telemetry

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private const val TAG = "UsageCollector"
private const val PREFS = "usage_collector"
private const val TOP_APP_LIMIT = 20

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

        Log.d(TAG, "Collected ${events.size} usage events")
        return events
    }

    private fun collectScreenSession(
        usageStatsManager: UsageStatsManager,
        window: TimeWindow
    ): RawEvent? {
        val summary = summarizeUsageWindow(
            readUsageEvents(usageStatsManager, window.start, window.end)
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
        val topApps = topAppUsage(events) { pkg -> packageLabel(packageManager, pkg) }
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
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

internal fun summarizeUsageWindow(events: List<UsageEventRow>): ScreenSessionSummary {
    var interactiveSince: Instant? = null
    var totalOnSeconds = 0.0
    var unlockCount = 0
    var firstUnlock: Instant? = null
    var lastUnlock: Instant? = null

    events.sortedBy { it.timestamp }.forEach { event ->
        when (event.eventType) {
            UsageEvents.Event.SCREEN_INTERACTIVE -> {
                interactiveSince = interactiveSince ?: event.timestamp
            }

            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                val start = interactiveSince
                if (start != null && !event.timestamp.isBefore(start)) {
                    totalOnSeconds += Duration.between(start, event.timestamp).seconds.toDouble()
                }
                interactiveSince = null
            }

            UsageEvents.Event.KEYGUARD_HIDDEN -> {
                unlockCount += 1
                firstUnlock = firstUnlock ?: event.timestamp
                lastUnlock = event.timestamp
            }
        }
    }

    return ScreenSessionSummary(
        totalOnSeconds = totalOnSeconds,
        unlockCount = unlockCount,
        firstUnlockTs = firstUnlock,
        lastUnlockTs = lastUnlock
    )
}

internal fun topAppUsage(
    events: List<UsageEventRow>,
    resolveLabel: (String) -> String
): List<AppUsageSummary> {
    val durations = mutableMapOf<String, Long>()
    val foregroundStarts = mutableMapOf<String, Instant>()

    events.sortedBy { it.timestamp }.forEach { event ->
        val pkg = event.packageName ?: return@forEach
        when (event.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND,
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                foregroundStarts[pkg] = event.timestamp
            }

            UsageEvents.Event.MOVE_TO_BACKGROUND,
            UsageEvents.Event.ACTIVITY_PAUSED -> {
                val start = foregroundStarts.remove(pkg) ?: return@forEach
                if (!event.timestamp.isBefore(start)) {
                    durations[pkg] = durations.getOrDefault(pkg, 0L) +
                        Duration.between(start, event.timestamp).seconds
                }
            }
        }
    }

    return durations.entries
        .filter { it.value > 0 }
        .sortedByDescending { it.value }
        .take(TOP_APP_LIMIT)
        .map { (pkg, seconds) ->
            AppUsageSummary(
                pkg = pkg,
                label = resolveLabel(pkg),
                foregroundSeconds = seconds.toDouble()
            )
        }
}

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
