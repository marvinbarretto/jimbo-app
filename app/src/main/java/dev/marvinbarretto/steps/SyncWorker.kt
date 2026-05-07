package dev.marvinbarretto.steps

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.marvinbarretto.steps.data.EventEntity
import dev.marvinbarretto.steps.data.StepsDatabase
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "StepsSync"
private const val MAX_ATTEMPTS = 10
private const val BATCH_SIZE = 500
private const val DEVICE_ID = "pixel-marvin"

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!isBatteryHealthyOrCharging()) {
            Log.d(TAG, "Skipping telemetry sync because battery is low and device is not charging")
            return Result.retry()
        }

        val eventDao = StepsDatabase.getInstance(applicationContext).eventDao()

        while (true) {
            val pending = eventDao.pendingBatch(BATCH_SIZE)
            if (pending.isEmpty()) {
                Log.d(TAG, "Telemetry queue empty")
                return Result.success()
            }

            val body = telemetryRequestBody(pending)

            try {
                val (code, response) = JimboClient.postTelemetryEvents(body.toString())
                when {
                    code == 201 -> {
                        eventDao.markSynced(pending.map { it.id })
                        Log.d(TAG, "Synced ${pending.size} telemetry events")
                    }

                    code == 429 -> {
                        Log.w(TAG, "Telemetry sync rate-limited: HTTP 429")
                        return Result.retry()
                    }

                    code in 500..599 -> {
                        recordRetryableFailure(eventDao, pending, "HTTP $code")
                        return Result.retry()
                    }

                    else -> {
                        Log.e(TAG, "Telemetry sync failed permanently: HTTP $code $response")
                        pending.forEach { eventDao.markDeadLetter(it.id) }
                        return Result.failure()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Telemetry sync failed with network error", e)
                recordRetryableFailure(eventDao, pending, e.message ?: "network error")
                return Result.retry()
            }
        }
    }

    private suspend fun recordRetryableFailure(
        eventDao: dev.marvinbarretto.steps.data.EventDao,
        pending: List<EventEntity>,
        reason: String
    ) {
        Log.w(TAG, "Telemetry sync retryable failure: $reason")
        val ids = pending.map { it.id }
        eventDao.incrementAttempts(ids)
        pending
            .filter { it.attempts + 1 >= MAX_ATTEMPTS }
            .forEach { eventDao.markDeadLetter(it.id) }
    }

    private fun telemetryRequestBody(events: List<EventEntity>): JSONObject {
        val jsonEvents = JSONArray().apply {
            events.forEach { put(it.toRequestJson()) }
        }
        return JSONObject().apply {
            put("device_id", DEVICE_ID)
            put("events", jsonEvents)
        }
    }

    private fun isBatteryHealthyOrCharging(): Boolean {
        val batteryIntent = applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return true

        val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        if (isCharging) {
            return true
        }

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return true
        }

        val batteryPercent = level.toFloat() / scale.toFloat()
        return batteryPercent > 0.15f
    }
}
