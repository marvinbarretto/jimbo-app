package dev.marvinbarretto.jimbo.cap.telemetry

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.marvinbarretto.jimbo.cap.JimboClient
import dev.marvinbarretto.jimbo.cap.data.GymSessionPushEntity
import dev.marvinbarretto.jimbo.cap.data.StepsDatabase
import dev.marvinbarretto.jimbo.cap.exerciseTypeName
import org.json.JSONObject
import java.time.Duration

private const val TAG = "GymBridge"

// Hardcoded gym exercise IDs from the seeded user-created cardio catalogue.
// These IDs are stable per-API-instance (created once via POST /gym/exercises).
// HC reports completed ExerciseSessionRecords hours after the fact — this
// bridge converts each one into a (gym_session + gym_session_cardio) pair
// so the dashboard's workout view stays current without manual entry.
private const val GYM_EXERCISE_OUTDOOR_RUN = "ex_6fea39d8"
private const val GYM_EXERCISE_OUTDOOR_WALK = "ex_5eefd9f9"
private const val GYM_EXERCISE_OUTDOOR_CYCLE = "ex_129b2eae"
private const val GYM_EXERCISE_OTHER_CARDIO = "ex_20e4ffca"

// HC records sometimes contain fragments (paused/resumed) under one minute.
// Filtering these avoids cluttering the gym timeline with non-workouts.
private val MIN_SESSION_DURATION: Duration = Duration.ofMinutes(2)

class GymSessionBridge(private val context: Context) {

    private val pushDao = StepsDatabase.getInstance(context).gymSessionPushDao()

    suspend fun bridgeRecent(window: TimeWindow): BridgeOutcome {
        Log.d(TAG, "Bridge start: window ${window.start} → ${window.end}")
        val client = try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect unavailable, skipping bridge", e)
            return BridgeOutcome(0, 0)
        }

        val records = try {
            client.readRecords(
                ReadRecordsRequest(
                    ExerciseSessionRecord::class,
                    TimeRangeFilter.between(window.start, window.end)
                )
            ).records
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read exercise sessions", e)
            return BridgeOutcome(0, 0)
        }

        Log.d(TAG, "Found ${records.size} HC exercise session(s) in window")
        if (records.isEmpty()) {
            Log.d(TAG, "Bridge complete: pushed=0 skipped=0 (no records)")
            return BridgeOutcome(0, 0)
        }

        val alreadyPushed = pushDao.pushedUids(records.map { it.metadata.id }).toSet()
        val pending = records
            .filter { it.metadata.id !in alreadyPushed }
            .filter { Duration.between(it.startTime, it.endTime) >= MIN_SESSION_DURATION }

        var pushed = 0
        var skipped = alreadyPushed.size + (records.size - alreadyPushed.size - pending.size)

        for (record in pending) {
            if (pushRecord(record)) pushed++ else skipped++
        }

        Log.d(TAG, "Bridge complete: pushed=$pushed skipped=$skipped")
        return BridgeOutcome(pushed = pushed, skipped = skipped)
    }

    private suspend fun pushRecord(record: ExerciseSessionRecord): Boolean {
        val typeName = exerciseTypeName(record.exerciseType)
        val gymExerciseId = mapExerciseId(record.exerciseType)
        val durationS = Duration.between(record.startTime, record.endTime).seconds.toInt()
        val notes = "auto: HC $typeName (${record.metadata.dataOrigin.packageName})"

        // Step 1: create the session with the real start time.
        val sessionBody = JSONObject().apply {
            put("started_at", record.startTime.toString())
            put("notes", notes)
        }
        val (sessionCode, sessionResponse) = JimboClient.postGymSession(sessionBody.toString())
        if (sessionCode != 201) {
            Log.e(TAG, "Failed to create gym session for ${record.metadata.id}: HTTP $sessionCode $sessionResponse")
            return false
        }
        val sessionId = JSONObject(sessionResponse).optString("id").takeIf { it.isNotEmpty() }
            ?: run {
                Log.e(TAG, "Gym session response missing id: $sessionResponse")
                return false
            }

        // Step 2: log the cardio entry. Distance and HR are deferred — would
        // require extra HC reads scoped to the session window; not worth the
        // round-trips until the dashboard surfaces them.
        val cardioBody = JSONObject().apply {
            put("exercise_id", gymExerciseId)
            put("duration_s", durationS)
        }
        val (cardioCode, cardioResponse) = JimboClient.postGymCardio(sessionId, cardioBody.toString())
        if (cardioCode != 201) {
            Log.e(TAG, "Failed to log cardio for session $sessionId: HTTP $cardioCode $cardioResponse")
            // Continue: session exists but cardio missing. Still record the push so we
            // don't retry the whole flow and create a duplicate session.
        }

        // Step 3: close the session with the real end time.
        val patchBody = JSONObject().apply {
            put("ended_at", record.endTime.toString())
        }
        val (patchCode, patchResponse) = JimboClient.patchGymSession(sessionId, patchBody.toString())
        if (patchCode != 200) {
            Log.w(TAG, "Failed to close gym session $sessionId: HTTP $patchCode $patchResponse")
        }

        pushDao.insert(
            GymSessionPushEntity(
                hcUid = record.metadata.id,
                gymSessionId = sessionId
            )
        )
        Log.d(TAG, "Pushed HC session ${record.metadata.id} → gym session $sessionId ($typeName, ${durationS}s)")
        return true
    }

    private fun mapExerciseId(hcExerciseType: Int): String = when (hcExerciseType) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> GYM_EXERCISE_OUTDOOR_RUN
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> GYM_EXERCISE_OUTDOOR_WALK
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> GYM_EXERCISE_OUTDOOR_CYCLE
        else -> GYM_EXERCISE_OTHER_CARDIO
    }
}

data class BridgeOutcome(val pushed: Int, val skipped: Int)
