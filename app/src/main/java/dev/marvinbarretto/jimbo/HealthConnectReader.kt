package dev.marvinbarretto.jimbo

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private const val TAG = "JimboSync"

data class ExerciseSession(
    val type: String,
    val durationMin: Double,
    val startTime: Instant,
    val sourceApp: String
)

data class TodayData(
    val steps: Long,
    val distanceMetres: Double,
    val caloriesActive: Double,
    val caloriesTotal: Double,
    val floors: Double,
    val elevationMetres: Double,
    val exerciseSessions: List<ExerciseSession>,
    val sleepSessions: List<SleepSessionSummary>,
    val heartRateAvg: Double?,
    val heartRateMin: Double?,
    val heartRateMax: Double?,
    val recordCount: Int
)

data class SleepStageSummary(
    val type: String,
    val startTime: Instant,
    val endTime: Instant,
    val durationMinutes: Double
)

data class SleepSessionSummary(
    val startTime: Instant,
    val endTime: Instant,
    val durationMinutes: Double,
    val stages: List<SleepStageSummary>,
    val sourceApp: String,
    val title: String?
)

object HealthConnectReader {

    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
        HealthPermission.getReadPermission(ElevationGainedRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    suspend fun readToday(context: Context): TodayData {
        val end = Instant.now()
        val start = end.atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()

        val client = HealthConnectClient.getOrCreate(context)
        val filter = TimeRangeFilter.between(start, end)

        var steps = 0L
        var distance = 0.0
        var caloriesActive = 0.0
        var caloriesTotal = 0.0
        var floors = 0.0
        var elevation = 0.0
        val exercises = mutableListOf<ExerciseSession>()
        val sleepSessions = mutableListOf<SleepSessionSummary>()
        var hrAvg: Double? = null
        var hrMin: Double? = null
        var hrMax: Double? = null
        var recordCount = 0

        try {
            val aggregate = client.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                    ),
                    timeRangeFilter = filter
                )
            )
            steps = aggregate[StepsRecord.COUNT_TOTAL] ?: 0L
            distance = aggregate[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
            caloriesActive = aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
            caloriesTotal = aggregate[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0
            recordCount += 4
        } catch (e: Exception) {
            Log.e(TAG, "readToday aggregate failed", e)
        }

        try {
            client.readRecords(ReadRecordsRequest(FloorsClimbedRecord::class, filter)).records.forEach { record ->
                floors += record.floors
                recordCount++
            }
        } catch (e: Exception) {
            Log.e(TAG, "readToday floors failed", e)
        }

        try {
            client.readRecords(ReadRecordsRequest(ElevationGainedRecord::class, filter)).records.forEach { record ->
                elevation += record.elevation.inMeters
                recordCount++
            }
        } catch (e: Exception) {
            Log.e(TAG, "readToday elevation failed", e)
        }

        try {
            val heartRate = client.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        HeartRateRecord.BPM_AVG,
                        HeartRateRecord.BPM_MIN,
                        HeartRateRecord.BPM_MAX,
                    ),
                    timeRangeFilter = filter
                )
            )
            hrAvg = heartRate[HeartRateRecord.BPM_AVG]?.toDouble()
            hrMin = heartRate[HeartRateRecord.BPM_MIN]?.toDouble()
            hrMax = heartRate[HeartRateRecord.BPM_MAX]?.toDouble()
            if (hrAvg != null) {
                recordCount++
            }
        } catch (e: Exception) {
            Log.e(TAG, "readToday heart rate failed", e)
        }

        try {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, filter)).records.forEach { record ->
                exercises += ExerciseSession(
                    type = exerciseTypeName(record.exerciseType),
                    durationMin = Duration.between(record.startTime, record.endTime).toMinutes().toDouble(),
                    startTime = record.startTime,
                    sourceApp = record.metadata.dataOrigin.packageName
                )
                recordCount++
            }
        } catch (e: Exception) {
            Log.e(TAG, "readToday exercise failed", e)
        }

        try {
            client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, filter)).records.forEach { record ->
                sleepSessions += record.toSummary()
                recordCount++
            }
        } catch (e: Exception) {
            Log.e(TAG, "readToday sleep failed", e)
        }

        return TodayData(
            steps = steps,
            distanceMetres = distance,
            caloriesActive = caloriesActive,
            caloriesTotal = caloriesTotal,
            floors = floors,
            elevationMetres = elevation,
            exerciseSessions = exercises,
            sleepSessions = sleepSessions,
            heartRateAvg = hrAvg,
            heartRateMin = hrMin,
            heartRateMax = hrMax,
            recordCount = recordCount
        )
    }
}

internal fun SleepSessionRecord.Stage.toSummary(): SleepStageSummary {
    val durationMin = Duration.between(startTime, endTime).toMinutes().toDouble()
    return SleepStageSummary(
        type = stageTypeName(stage),
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMin
    )
}

internal fun SleepSessionRecord.toSummary(): SleepSessionSummary {
    val durationMin = Duration.between(startTime, endTime).toMinutes().toDouble()
    return SleepSessionSummary(
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMin,
        stages = stages.map { it.toSummary() },
        sourceApp = metadata.dataOrigin.packageName,
        title = title?.toString()
    )
}

internal fun exerciseTypeName(type: Int): String = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "running"
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "biking"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "swimming"
    else -> "other_$type"
}

internal fun stageTypeName(type: Int): String = when (type) {
    SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "awake_out_of_bed"
    SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
    SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
    SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
    SleepSessionRecord.STAGE_TYPE_REM -> "rem"
    SleepSessionRecord.STAGE_TYPE_UNKNOWN -> "unknown"
    else -> "unknown_$type"
}
