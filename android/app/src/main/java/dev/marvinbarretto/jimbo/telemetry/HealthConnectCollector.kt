package dev.marvinbarretto.jimbo.telemetry

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.marvinbarretto.jimbo.HealthConnectReader
import dev.marvinbarretto.jimbo.exerciseTypeName
import dev.marvinbarretto.jimbo.stageTypeName
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private const val TAG = "HealthCollector"
private const val AGGREGATE_SOURCE = "health_connect_aggregate"
private const val DAILY_SOURCE = "health_connect_daily"

/**
 * Start of the logical day, mirroring LOGICAL_DAY_CUTOVER_HOURS in
 * jimbo-api's `coach-tz.ts`. Anchoring the daily totals here rather than at
 * midnight — the way `app_usage_daily` does — is deliberate: the server buckets
 * days on this cutover, so a midnight-anchored row would file the small hours
 * under the wrong day.
 */
private const val LOGICAL_DAY_CUTOVER_HOURS = 4L

private val HR_PERMISSION = HealthPermission.getReadPermission(HeartRateRecord::class)
private val SLEEP_PERMISSION = HealthPermission.getReadPermission(SleepSessionRecord::class)

class HealthConnectCollector(
    private val context: Context
) : Collector {
    override val id: String = "health_connect"
    override val defaultEnabled: Boolean = true
    override val cadence: CollectorCadence = CollectorCadence.Periodic(Duration.ofHours(1))

    override suspend fun collect(window: TimeWindow): List<RawEvent> {
        Log.d(TAG, "Collecting Health Connect telemetry from ${window.start} to ${window.end}")
        val client = HealthConnectClient.getOrCreate(context)
        val filter = TimeRangeFilter.between(window.start, window.end)
        val events = mutableListOf<RawEvent>()

        // Failures used to die in logcat, making server-side gaps (hours of
        // missing steps while every other collector flowed) undiagnosable.
        // Missing permissions and read errors now ship as events themselves,
        // so the DB can always answer "why is HC quiet here?".
        val granted = reportMissingPermissions(client, window, events)

        collectAggregateMetrics(client, filter, window, events)
        collectDailyTotals(client, window, events)
        collectFloors(client, filter, window, events)
        // Deliberately ungranted reads are skipped, not attempted: the
        // hc_diagnostic event above already documents the state, and trying
        // anyway would emit an hc_error per sync forever (~48/day of noise).
        // `granted == null` means the probe itself failed — attempt the read
        // so a broken probe can't silently disable collection.
        if (granted == null || HR_PERMISSION in granted) {
            collectHeartRate(client, filter, window, events)
        }
        collectExerciseSessions(client, filter, window, events)
        if (granted == null || SLEEP_PERMISSION in granted) {
            collectSleepSessions(client, filter, window, events)
        }

        Log.d(TAG, "Collected ${events.size} Health Connect events")
        return events
    }

    private suspend fun reportMissingPermissions(
        client: HealthConnectClient,
        window: TimeWindow,
        events: MutableList<RawEvent>
    ): Set<String>? {
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            val expected = HealthConnectReader.PERMISSIONS +
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
            val missing = (expected - granted).sorted()
            if (missing.isNotEmpty()) {
                events += RawEvent(
                    collector = id,
                    type = "hc_diagnostic",
                    ts = window.end,
                    payload = mapOf("missing_permissions" to missing)
                )
            }
            granted
        } catch (e: Exception) {
            events += errorEvent("permission_probe", e, window)
            null
        }
    }

    private fun errorEvent(phase: String, e: Exception, window: TimeWindow): RawEvent = RawEvent(
        collector = id,
        type = "hc_error",
        ts = window.end,
        payload = mapOf(
            "phase" to phase,
            "exception" to (e::class.simpleName ?: "Exception"),
            "message" to e.message?.take(200)
        )
    )

    private suspend fun collectAggregateMetrics(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        window: TimeWindow,
        events: MutableList<RawEvent>
    ) {
        try {
            val aggregate = client.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                        androidx.health.connect.client.records.TotalCaloriesBurnedRecord.ENERGY_TOTAL
                    ),
                    timeRangeFilter = filter
                )
            )

            aggregate[StepsRecord.COUNT_TOTAL]?.let {
                events += scalarEvent("steps", window, it.toDouble(), "count")
            }
            aggregate[DistanceRecord.DISTANCE_TOTAL]?.let {
                events += scalarEvent("distance", window, it.inMeters, "meters")
            }
            aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.let {
                events += scalarEvent("calories_active", window, it.inKilocalories, "kcal")
            }
            aggregate[androidx.health.connect.client.records.TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.let {
                events += scalarEvent("calories_total", window, it.inKilocalories, "kcal")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Aggregate metrics failed", e)
            events += errorEvent("aggregate", e, window)
        }
    }

    /**
     * Steps, distance and calories for the whole logical day so far — not just
     * the sync window.
     *
     * The windowed rows above cannot answer "how much today": they are trailing
     * two-hour aggregates posted every half hour, so they overlap each other and,
     * worse, any hour the worker fails to run is never asked about again. A day
     * that lost seven hours to doze read 2,028 steps against the phone's own
     * 5,224 on 13 Aug 2026.
     *
     * A since-cutover total is immune to both problems: each post carries the
     * entire day, so overlap is meaningless and a missed run costs nothing but
     * freshness. Health Connect is queried for the range every time, so the
     * figure self-heals the moment the worker runs again.
     *
     * The windowed rows stay — they are what intra-day shape is derived from.
     */
    private suspend fun collectDailyTotals(
        client: HealthConnectClient,
        window: TimeWindow,
        events: MutableList<RawEvent>
    ) {
        val dayStart = startOfLogicalDay(window.end)
        if (!window.end.isAfter(dayStart)) return

        try {
            val aggregate = client.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        androidx.health.connect.client.records.TotalCaloriesBurnedRecord.ENERGY_TOTAL
                    ),
                    timeRangeFilter = TimeRangeFilter.between(dayStart, window.end)
                )
            )

            fun daily(type: String, value: Double, unit: String) {
                events += RawEvent(
                    collector = id,
                    type = type,
                    ts = dayStart,
                    tsEnd = window.end,
                    value = value,
                    unit = unit,
                    source = DAILY_SOURCE
                )
            }

            aggregate[StepsRecord.COUNT_TOTAL]?.let { daily("steps_daily", it.toDouble(), "count") }
            aggregate[DistanceRecord.DISTANCE_TOTAL]?.let { daily("distance_daily", it.inMeters, "meters") }
            aggregate[androidx.health.connect.client.records.TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.let {
                daily("calories_total_daily", it.inKilocalories, "kcal")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Daily totals failed", e)
            events += errorEvent("daily_totals", e, window)
        }
    }

    /** The cutover instant on or before [now], in the device's zone. */
    private fun startOfLogicalDay(now: Instant): Instant {
        val zone = ZoneId.systemDefault()
        val local = now.atZone(zone)
        val cutoverToday = local.toLocalDate().atStartOfDay(zone).plusHours(LOGICAL_DAY_CUTOVER_HOURS)
        val start = if (local.isBefore(cutoverToday)) cutoverToday.minusDays(1) else cutoverToday
        return start.toInstant()
    }

    private suspend fun collectFloors(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        window: TimeWindow,
        events: MutableList<RawEvent>
    ) {
        try {
            val records = client.readRecords(ReadRecordsRequest(FloorsClimbedRecord::class, filter)).records
            if (records.isNotEmpty()) {
                val source = uniqueSourceOrNull(records.map { it.metadata.dataOrigin.packageName })
                events += RawEvent(
                    collector = id,
                    type = "floors",
                    ts = records.minOf { it.startTime },
                    tsEnd = records.maxOf { it.endTime },
                    value = records.sumOf { it.floors.toDouble() },
                    unit = "count",
                    source = source
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Floors collection failed", e)
            events += errorEvent("floors", e, window)
        }
    }

    private suspend fun collectHeartRate(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        window: TimeWindow,
        events: MutableList<RawEvent>
    ) {
        try {
            val aggregate = client.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        HeartRateRecord.BPM_MIN,
                        HeartRateRecord.BPM_AVG,
                        HeartRateRecord.BPM_MAX
                    ),
                    timeRangeFilter = filter
                )
            )
            val min = aggregate[HeartRateRecord.BPM_MIN]?.toDouble()
            val avg = aggregate[HeartRateRecord.BPM_AVG]?.toDouble()
            val max = aggregate[HeartRateRecord.BPM_MAX]?.toDouble()
            if (min != null || avg != null || max != null) {
                events += RawEvent(
                    collector = id,
                    type = "heart_rate_summary",
                    ts = window.start,
                    tsEnd = window.end,
                    source = AGGREGATE_SOURCE,
                    payload = mapOf(
                        "min" to min,
                        "avg" to avg,
                        "max" to max
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Heart rate collection failed", e)
            events += errorEvent("heart_rate", e, window)
        }
    }

    private suspend fun collectExerciseSessions(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        window: TimeWindow,
        events: MutableList<RawEvent>
    ) {
        try {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, filter)).records.forEach { record ->
                events += RawEvent(
                    collector = id,
                    type = "exercise_session",
                    ts = record.startTime,
                    tsEnd = record.endTime,
                    value = Duration.between(record.startTime, record.endTime).toMinutes().toDouble(),
                    unit = "duration_min",
                    source = record.metadata.dataOrigin.packageName,
                    payload = mapOf(
                        "exercise_type" to exerciseTypeName(record.exerciseType),
                        "hc_uid" to record.metadata.id
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exercise session collection failed", e)
            events += errorEvent("exercise_sessions", e, window)
        }
    }

    private suspend fun collectSleepSessions(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        window: TimeWindow,
        events: MutableList<RawEvent>
    ) {
        try {
            client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, filter)).records.forEach { record ->
                events += RawEvent(
                    collector = id,
                    type = "sleep_session",
                    ts = record.startTime,
                    tsEnd = record.endTime,
                    value = Duration.between(record.startTime, record.endTime).toMinutes().toDouble(),
                    unit = "duration_min",
                    source = record.metadata.dataOrigin.packageName,
                    payload = mapOf(
                        "title" to record.title?.toString(),
                        "hc_uid" to record.metadata.id,
                        "stages" to record.stages.map { stage ->
                            mapOf(
                                "stage_type" to stageTypeName(stage.stage),
                                "start_time" to stage.startTime.toString(),
                                "end_time" to stage.endTime.toString(),
                                "duration_min" to Duration.between(stage.startTime, stage.endTime).toMinutes().toDouble()
                            )
                        }
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sleep session collection failed", e)
            events += errorEvent("sleep_sessions", e, window)
        }
    }

    private fun scalarEvent(
        type: String,
        window: TimeWindow,
        value: Double,
        unit: String
    ): RawEvent = RawEvent(
        collector = id,
        type = type,
        ts = window.start,
        tsEnd = window.end,
        value = value,
        unit = unit,
        source = AGGREGATE_SOURCE
    )

    private fun uniqueSourceOrNull(sources: List<String>): String? =
        sources.distinct().singleOrNull()
}
