package dev.marvinbarretto.jimbo.telemetry

import android.content.Context
import android.util.Log
import dev.marvinbarretto.jimbo.JimboClient
import dev.marvinbarretto.jimbo.data.EventEntity
import dev.marvinbarretto.jimbo.data.StepsDatabase
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "JimboSync"
private const val MAX_ATTEMPTS = 10
private const val BATCH_SIZE = 500
private const val DEVICE_ID = "pixel-marvin"

sealed interface TelemetryDrainOutcome {
    data class Success(val syncedCount: Int) : TelemetryDrainOutcome
    data class RetryableFailure(val pendingCount: Int) : TelemetryDrainOutcome
    data class PermanentFailure(val pendingCount: Int) : TelemetryDrainOutcome
}

class TelemetrySyncer(context: Context) {
    private val eventDao = StepsDatabase.getInstance(context).eventDao()

    suspend fun drainPending(): TelemetryDrainOutcome {
        var syncedCount = 0

        while (true) {
            val pending = eventDao.pendingBatch(BATCH_SIZE)
            if (pending.isEmpty()) {
                Log.d(TAG, "Telemetry queue empty")
                return TelemetryDrainOutcome.Success(syncedCount)
            }

            try {
                val (code, response) = JimboClient.postTelemetryEvents(requestBody(pending).toString())
                when {
                    code == 201 -> {
                        eventDao.markSynced(pending.map { it.id })
                        syncedCount += pending.size
                        Log.d(TAG, "Synced ${pending.size} telemetry events")
                    }

                    code == 429 || code in 500..599 -> {
                        recordRetryableFailure(pending, "HTTP $code")
                        return TelemetryDrainOutcome.RetryableFailure(eventDao.pendingCount())
                    }

                    else -> {
                        Log.e(TAG, "Telemetry sync failed permanently: HTTP $code $response")
                        pending.forEach { eventDao.markDeadLetter(it.id) }
                        return TelemetryDrainOutcome.PermanentFailure(eventDao.pendingCount())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Telemetry sync failed with network error", e)
                recordRetryableFailure(pending, e.message ?: "network error")
                return TelemetryDrainOutcome.RetryableFailure(eventDao.pendingCount())
            }
        }
    }

    private suspend fun recordRetryableFailure(pending: List<EventEntity>, reason: String) {
        Log.w(TAG, "Telemetry sync retryable failure: $reason")
        val ids = pending.map { it.id }
        eventDao.incrementAttempts(ids)
        pending
            .filter { it.attempts + 1 >= MAX_ATTEMPTS }
            .forEach { eventDao.markDeadLetter(it.id) }
    }

    private fun requestBody(events: List<EventEntity>): JSONObject {
        val jsonEvents = JSONArray().apply {
            events.forEach { put(it.toRequestJson()) }
        }
        return JSONObject().apply {
            put("device_id", DEVICE_ID)
            put("events", jsonEvents)
        }
    }
}
