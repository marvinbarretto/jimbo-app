package dev.marvinbarretto.jimbo.telemetry

import android.content.Context
import android.util.Log
import dev.marvinbarretto.jimbo.data.EventEntity
import dev.marvinbarretto.jimbo.data.StepsDatabase
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val TAG = "TelemetryStore"

class TelemetryStore(private val context: Context) {
    private val database = StepsDatabase.getInstance(context)
    private val eventDao = database.eventDao()
    private val registry = CollectorRegistry(
        settingDao = database.collectorSettingDao(),
        collectors = listOf(
            HealthConnectCollector(context),
            DeviceCollector(context),
            UsageCollector(context)
        )
    )

    suspend fun collect(window: TimeWindow): Int {
        val events = registry.enabledCollectors().flatMap { collector ->
            runCatching { collector.collect(window) }
                .onFailure { Log.e(TAG, "Collector ${collector.id} failed", it) }
                .getOrDefault(emptyList())
        }

        if (events.isEmpty()) {
            Log.d(TAG, "No telemetry events collected")
            return 0
        }

        eventDao.insertAll(events.map { it.toEntity() })
        val pending = eventDao.pendingCount()
        Log.d(TAG, "Queued ${events.size} telemetry events, pending=$pending")
        return events.size
    }

    suspend fun collectBroadcast(action: String): Int {
        val now = java.time.Instant.now()
        return collect(TimeWindow(start = now, end = now, triggerAction = action))
    }

    suspend fun pendingCount(): Int = eventDao.pendingCount()

    suspend fun deadLetterCount(): Int = eventDao.deadLetterCount()
}

internal fun RawEvent.toEntity(): EventEntity = EventEntity(
    id = stableEventId(),
    collector = collector,
    type = type,
    ts = ts.toString(),
    tsEnd = tsEnd?.toString(),
    value = value,
    unit = unit,
    source = source,
    payload = payload?.let(::canonicalPayloadString)
)

private fun RawEvent.stableEventId(): String {
    val seed = buildString {
        append(collector)
        append('|')
        append(type)
        append('|')
        append(ts)
        append('|')
        append(tsEnd ?: "")
        append('|')
        append(value?.toString() ?: "")
        append('|')
        append(unit ?: "")
        append('|')
        append(source ?: "")
        append('|')
        append(payload?.let(::canonicalPayloadString) ?: "")
    }
    return UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()
}

private fun canonicalPayloadString(payload: Map<String, Any?>): String =
    payload.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"${escapeJson(key)}\":${canonicalJsonValueString(value)}"
    }

private fun canonicalJsonValueString(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"${escapeJson(value)}\""
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> canonicalPayloadString(
        value.entries.associate { (key, nestedValue) -> key.toString() to nestedValue }
    )
    is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalJsonValueString(it) }
    else -> "\"${escapeJson(value.toString())}\""
}

private fun escapeJson(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
