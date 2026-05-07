package dev.marvinbarretto.steps.telemetry

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
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.marvinbarretto.steps.exerciseTypeName
import dev.marvinbarretto.steps.stageTypeName
import java.time.Duration

private const val TAG = "HealthCollector"
private const val AGGREGATE_SOURCE = "health_connect_aggregate"

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

        collectAggregateMetrics(client, filter, window, events)
        collectFloors(client, filter, events)
        collectHeartRate(client, filter, window, events)
        collectExerciseSessions(client, filter, events)
        collectSleepSessions(client, filter, events)

        Log.d(TAG, "Collected ${events.size} Health Connect events")
        return events
    }

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
        }
    }

    private suspend fun collectFloors(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
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
        }
    }

    private suspend fun collectExerciseSessions(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
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
        }
    }

    private suspend fun collectSleepSessions(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
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
