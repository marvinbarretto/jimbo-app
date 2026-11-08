package dev.marvinbarretto.jimbo.telemetry

import android.content.Context
import android.os.Process
import android.util.Log
import java.time.Duration
import java.util.concurrent.TimeUnit

private const val TAG = "BatteryUsage"
private const val PREFS = "battery_usage_collector"
private const val GMS = "com.google.android.gms"
private const val TOP_N = 5

/**
 * Battery use per app, as Android's own estimate (mAh since the last full
 * charge), read from `dumpsys batterystats --usage`. Android wipes these stats
 * when you unplug after a full charge, so a reading taken once a week shows a
 * day at most. Recording one every hour and keeping the last one before each
 * charge gives every day its total without anyone reading anything.
 *
 * Needs android.permission.DUMP, a development permission granted only over
 * adb (scripts/install-phone.sh does it). Without it dumpsys answers with a
 * permission denial and this collector records nothing.
 *
 * Records this app, Play services (it runs the geofences, so their cost lands
 * there, not here), the phone's computed and actual drain, and the top apps.
 */
class BatteryUsageCollector(private val context: Context) : Collector {
    override val id: String = "battery_usage"
    override val defaultEnabled: Boolean = true
    override val cadence: CollectorCadence = CollectorCadence.Periodic(Duration.ofHours(1))

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun collect(window: TimeWindow): List<RawEvent> {
        val now = window.end
        val last = prefs.getLong("last_at", 0L)
        if (last != 0L && now.toEpochMilli() - last < Duration.ofMinutes(55).toMillis()) return emptyList()

        val dump = readUsageDump() ?: return emptyList()
        val usage = parseBatteryUsage(dump) ?: run {
            Log.w(TAG, "No usage in dumpsys output: ${dump.take(160)}")
            return emptyList()
        }
        prefs.edit().putLong("last_at", now.toEpochMilli()).apply()

        val pm = context.packageManager
        fun label(uidLabel: String): String {
            val uid = uidFromLabel(uidLabel) ?: return uidLabel
            return pm.getPackagesForUid(uid)?.firstOrNull() ?: uidLabel
        }
        val own = uidLabel(Process.myUid())
        val gms = runCatching { uidLabel(pm.getPackageUid(GMS, 0)) }.getOrNull()

        return listOf(
            RawEvent(
                collector = id,
                type = "battery_usage",
                ts = now,
                value = usage.byUid[own] ?: 0.0,
                unit = "mAh_since_charge",
                source = "dumpsys_usage",
                payload = mapOf(
                    "gms_mah" to (gms?.let { usage.byUid[it] } ?: 0.0),
                    "computed_drain_mah" to usage.computedDrain,
                    "actual_drain_mah" to usage.actualDrain,
                    "capacity_mah" to usage.capacity,
                    "top" to usage.byUid.entries.sortedByDescending { it.value }.take(TOP_N)
                        .map { mapOf("app" to label(it.key), "mah" to it.value) },
                ),
            )
        )
    }

    private fun readUsageDump(): String? = runCatching {
        val proc = ProcessBuilder("dumpsys", "batterystats", "--usage").redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        if (!proc.waitFor(20, TimeUnit.SECONDS)) proc.destroy()
        text
    }.onFailure { Log.w(TAG, "dumpsys failed", it) }.getOrNull()
}

internal data class BatteryUsage(
    val capacity: Double?,
    val computedDrain: Double?,
    val actualDrain: Double?,
    val byUid: Map<String, Double>,
)

private val CAPACITY = Regex("""Capacity:\s*([\d.]+),\s*Computed drain:\s*([\d.]+),\s*actual drain:\s*([\d.]+)""")
private val UID_LINE = Regex("""^\s*UID\s+(\S+):\s*([\d.]+)""")

/** Null when the text has no usage section (a permission denial, say). */
internal fun parseBatteryUsage(text: String): BatteryUsage? {
    val cap = CAPACITY.find(text)
    val byUid = text.lineSequence().mapNotNull { line ->
        UID_LINE.find(line)?.let { m -> m.groupValues[2].toDoubleOrNull()?.let { m.groupValues[1] to it } }
    }.toMap()
    if (cap == null && byUid.isEmpty()) return null
    return BatteryUsage(
        capacity = cap?.groupValues?.get(1)?.toDoubleOrNull(),
        computedDrain = cap?.groupValues?.get(2)?.toDoubleOrNull(),
        actualDrain = cap?.groupValues?.get(3)?.toDoubleOrNull(),
        byUid = byUid,
    )
}

/** dumpsys's spelling of a uid: app uids as u<user>a<appId - 10000>, others bare. */
internal fun uidLabel(uid: Int): String {
    val user = uid / 100_000
    val app = uid % 100_000
    return if (app >= 10_000) "u${user}a${app - 10_000}" else "$app"
}

internal fun uidFromLabel(label: String): Int? {
    Regex("""^u(\d+)a(\d+)$""").find(label)?.let { m ->
        return m.groupValues[1].toInt() * 100_000 + 10_000 + m.groupValues[2].toInt()
    }
    return label.toIntOrNull()
}
