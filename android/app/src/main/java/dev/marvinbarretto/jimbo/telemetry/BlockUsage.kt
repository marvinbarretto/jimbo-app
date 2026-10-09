package dev.marvinbarretto.jimbo.telemetry

import android.app.usage.UsageEvents
import java.time.Duration

internal data class BlockAppUsage(
    val pkg: String,
    val launches: Int,
    val foregroundSeconds: Double
)

/**
 * Per-app facts for one window: how often each app took the foreground from a
 * different app, and for how long. Apps in [ignore] (the shell itself, the
 * launcher, System UI) are dropped, and an app only counts as a launch when
 * the previously-resumed app was another one, so rotating or hopping between
 * one app's own screens is not an "interruption".
 */
internal fun summarizeBlockUsage(
    events: List<UsageEventRow>,
    ignore: Set<String>
): List<BlockAppUsage> {
    val sorted = events.filter { it.packageName != null && it.packageName !in ignore }.sortedBy { it.timestamp }
    val launches = mutableMapOf<String, Int>()
    var lastResumed: String? = null
    sorted.forEach { event ->
        val pkg = event.packageName ?: return@forEach
        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && pkg != lastResumed) {
            launches[pkg] = launches.getOrDefault(pkg, 0) + 1
            lastResumed = pkg
        }
    }

    val foreground = mutableMapOf<String, Double>()
    val since = mutableMapOf<String, java.time.Instant>()
    sorted.forEach { event ->
        val pkg = event.packageName ?: return@forEach
        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> since[pkg] = event.timestamp
            UsageEvents.Event.ACTIVITY_PAUSED -> {
                val start = since.remove(pkg) ?: return@forEach
                if (!event.timestamp.isBefore(start)) {
                    foreground[pkg] = foreground.getOrDefault(pkg, 0.0) +
                        Duration.between(start, event.timestamp).seconds
                }
            }
        }
    }

    return (launches.keys + foreground.keys)
        .map { BlockAppUsage(it, launches.getOrDefault(it, 0), foreground.getOrDefault(it, 0.0)) }
        .filter { it.launches > 0 || it.foregroundSeconds > 0 }
        .sortedByDescending { it.foregroundSeconds }
}
