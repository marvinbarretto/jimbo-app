package dev.marvinbarretto.jimbo.telemetry

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.marvinbarretto.jimbo.HealthConnectReader
import dev.marvinbarretto.jimbo.exerciseTypeName
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
        reportMissingPermissions(client, window, events)

        collectAggregateMetrics(client, filter, window, events)
        collectDailyTotals(client, window, events)
        collectMissedDays(client, window, events)
        collectFloors(client, filter, window, events)
        collectExerciseSessions(client, filter, window, events)

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
            events += dailyEvents(client, dayStart, window.end)
        } catch (e: Exception) {
            Log.e(TAG, "Daily totals failed", e)
            events += errorEvent("daily_totals", e, window)
        }
    }

    /**
     * Closed days the server has not been sent `*_daily` rows for, oldest first,
     * at most [MAX_BACKFILL_DAYS] back. A phone that could not sync for days
     * (Wi-Fi-only abroad, 17-23 Sep 2026) otherwise never posts those days:
     * [collectDailyTotals] only ever covers today. Health Connect keeps the
     * history, so each day is simply re-read. Event ids are content-derived, so
     * re-posting a day with the same totals is harmless.
     */
    private suspend fun collectMissedDays(
        client: HealthConnectClient,
        window: TimeWindow,
        events: MutableList<RawEvent>
    ) {
        val state = DailyBackfillState(context)
        val yesterday = startOfLogicalDay(window.end).atZone(ZoneId.systemDefault()).toLocalDate().minusDays(1)
        var reached: LocalDate? = null
        for (day in missedDays(state.lastPosted(), yesterday)) {
            try {
                events += dailyEvents(client, logicalDayStart(day), logicalDayStart(day.plusDays(1)))
                reached = day
            } catch (e: Exception) {
                Log.e(TAG, "Backfill of $day failed", e)
                events += errorEvent("daily_backfill", e, window)
                break
            }
        }
        reached?.let(state::stage)
    }

    private suspend fun dailyEvents(client: HealthConnectClient, from: Instant, to: Instant): List<RawEvent> {
        val aggregate = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL,
                    androidx.health.connect.client.records.TotalCaloriesBurnedRecord.ENERGY_TOTAL
                ),
                timeRangeFilter = TimeRangeFilter.between(from, to)
            )
        )
        fun daily(type: String, value: Double, unit: String) = RawEvent(
            collector = id,
            type = type,
            ts = from,
            tsEnd = to,
            value = value,
            unit = unit,
            source = DAILY_SOURCE
        )
        return listOfNotNull(
            aggregate[StepsRecord.COUNT_TOTAL]?.let { daily("steps_daily", it.toDouble(), "count") },
            aggregate[DistanceRecord.DISTANCE_TOTAL]?.let { daily("distance_daily", it.inMeters, "meters") },
            aggregate[androidx.health.connect.client.records.TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.let {
                daily("calories_total_daily", it.inKilocalories, "kcal")
            }
        )
    }

    private fun logicalDayStart(day: LocalDate): Instant =
        day.atStartOfDay(ZoneId.systemDefault()).plusHours(LOGICAL_DAY_CUTOVER_HOURS).toInstant()

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
