package dev.marvinbarretto.steps

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

private const val TAG = "StepsSync"

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
    val recordCount: Int
)

/**
 * Reads movement data from Android's Health Connect system.
 *
 * Health Connect is Android's central health data store — apps like
 * Google Fit, Samsung Health, and Fitbit write data into it. We read
 * from it, convert everything to JSON, and hand it to JimboClient.
 */
object HealthConnectReader {

    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
        HealthPermission.getReadPermission(ElevationGainedRecord::class),
    )

    /**
     * Read all movement records between [start] and [end] from Health Connect.
     * Returns a JSONObject matching the Jimbo /api/fitness/sync payload format.
     */
    suspend fun readAll(context: Context, start: Instant, end: Instant): JSONObject {
        Log.d(TAG, "readAll: Reading from $start to $end")

        val client = HealthConnectClient.getOrCreate(context)
        val filter = TimeRangeFilter.between(start, end)
        val records = JSONArray()

        // Read each type with individual try/catch so one failure doesn't kill them all
        readType("steps", records) {
            client.readRecords(ReadRecordsRequest(StepsRecord::class, filter)).records.forEach { r ->
                records.put(record("steps", r.count.toDouble(), "count", r.startTime, r.endTime, r.metadata))
            }
        }

        readType("distance", records) {
            client.readRecords(ReadRecordsRequest(DistanceRecord::class, filter)).records.forEach { r ->
                records.put(record("distance", r.distance.inMeters, "metres", r.startTime, r.endTime, r.metadata))
            }
        }

        readType("calories_active", records) {
            client.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, filter)).records.forEach { r ->
                records.put(record("calories_active", r.energy.inKilocalories, "kcal", r.startTime, r.endTime, r.metadata))
            }
        }

        readType("calories_total", records) {
            client.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, filter)).records.forEach { r ->
                records.put(record("calories_total", r.energy.inKilocalories, "kcal", r.startTime, r.endTime, r.metadata))
            }
        }

        readType("exercise_session", records) {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, filter)).records.forEach { r ->
                val durationMin = java.time.Duration.between(r.startTime, r.endTime).toMinutes().toDouble()
                val meta = JSONObject()
                    .put("exercise_type", exerciseTypeName(r.exerciseType))
                    .put("hc_uid", r.metadata.id)
                records.put(JSONObject().apply {
                    put("record_type", "exercise_session")
                    put("value", durationMin)
                    put("unit", "minutes")
                    put("start_time", r.startTime.toString())
                    put("end_time", r.endTime.toString())
                    put("source_app", r.metadata.dataOrigin.packageName)
                    put("metadata", meta)
                })
            }
        }

        readType("floors", records) {
            client.readRecords(ReadRecordsRequest(FloorsClimbedRecord::class, filter)).records.forEach { r ->
                records.put(record("floors", r.floors, "floors", r.startTime, r.endTime, r.metadata))
            }
        }

        readType("elevation", records) {
            client.readRecords(ReadRecordsRequest(ElevationGainedRecord::class, filter)).records.forEach { r ->
                records.put(record("elevation", r.elevation.inMeters, "metres", r.startTime, r.endTime, r.metadata))
            }
        }

        Log.d(TAG, "readAll: Total records collected: ${records.length()}")

        return JSONObject().apply {
            put("device_id", "pixel-marvin")
            put("records", records)
        }
    }

    /**
     * Read today's health data as structured Kotlin types for the UI layer.
     * Unlike readAll() which returns JSON for the API, this returns data classes
     * the ViewModel can display directly.
     */
    suspend fun readToday(context: Context): TodayData {
        val end = Instant.now()
        val start = end.atZone(java.time.ZoneId.systemDefault())
            .toLocalDate().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()

        val client = HealthConnectClient.getOrCreate(context)
        val filter = TimeRangeFilter.between(start, end)

        var steps = 0L
        var distance = 0.0
        var caloriesActive = 0.0
        var caloriesTotal = 0.0
        var floors = 0.0
        var elevation = 0.0
        val exercises = mutableListOf<ExerciseSession>()
        var recordCount = 0

        try {
            client.readRecords(ReadRecordsRequest(StepsRecord::class, filter)).records.forEach { r ->
                steps += r.count; recordCount++
            }
        } catch (e: Exception) { Log.e(TAG, "readToday steps failed", e) }

        try {
            client.readRecords(ReadRecordsRequest(DistanceRecord::class, filter)).records.forEach { r ->
                distance += r.distance.inMeters; recordCount++
            }
        } catch (e: Exception) { Log.e(TAG, "readToday distance failed", e) }

        try {
            client.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, filter)).records.forEach { r ->
                caloriesActive += r.energy.inKilocalories; recordCount++
            }
        } catch (e: Exception) { Log.e(TAG, "readToday caloriesActive failed", e) }

        try {
            client.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, filter)).records.forEach { r ->
                caloriesTotal += r.energy.inKilocalories; recordCount++
            }
        } catch (e: Exception) { Log.e(TAG, "readToday caloriesTotal failed", e) }

        try {
            client.readRecords(ReadRecordsRequest(FloorsClimbedRecord::class, filter)).records.forEach { r ->
                floors += r.floors; recordCount++
            }
        } catch (e: Exception) { Log.e(TAG, "readToday floors failed", e) }

        try {
            client.readRecords(ReadRecordsRequest(ElevationGainedRecord::class, filter)).records.forEach { r ->
                elevation += r.elevation.inMeters; recordCount++
            }
        } catch (e: Exception) { Log.e(TAG, "readToday elevation failed", e) }

        try {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, filter)).records.forEach { r ->
                val durationMin = java.time.Duration.between(r.startTime, r.endTime).toMinutes().toDouble()
                exercises.add(ExerciseSession(
                    type = exerciseTypeName(r.exerciseType),
                    durationMin = durationMin,
                    startTime = r.startTime,
                    sourceApp = r.metadata.dataOrigin.packageName
                ))
                recordCount++
            }
        } catch (e: Exception) { Log.e(TAG, "readToday exercise failed", e) }

        return TodayData(
            steps = steps,
            distanceMetres = distance,
            caloriesActive = caloriesActive,
            caloriesTotal = caloriesTotal,
            floors = floors,
            elevationMetres = elevation,
            exerciseSessions = exercises,
            recordCount = recordCount
        )
    }

    /**
     * Wraps each record type read in try/catch with logging.
     * If one type fails, the others still get read.
     */
    private suspend fun readType(name: String, records: JSONArray, block: suspend () -> Unit) {
        val before = records.length()
        try {
            block()
            val added = records.length() - before
            Log.d(TAG, "  $name: $added records")
        } catch (e: Exception) {
            Log.e(TAG, "  $name: FAILED - ${e.message}", e)
        }
    }

    private fun record(
        type: String, value: Double, unit: String,
        start: Instant, end: Instant,
        meta: androidx.health.connect.client.records.metadata.Metadata
    ): JSONObject {
        return JSONObject().apply {
            put("record_type", type)
            put("value", value)
            put("unit", unit)
            put("start_time", start.toString())
            put("end_time", end.toString())
            put("source_app", meta.dataOrigin.packageName)
            put("metadata", JSONObject().put("hc_uid", meta.id))
        }
    }

    private fun exerciseTypeName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "running"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "swimming"
        else -> "other_$type"
    }
}
