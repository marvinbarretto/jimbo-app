package dev.marvinbarretto.jimbo.cap

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.marvinbarretto.jimbo.cap.telemetry.GymSessionBridge
import dev.marvinbarretto.jimbo.cap.telemetry.SyncConstraintsRepository
import dev.marvinbarretto.jimbo.cap.telemetry.TelemetryStore
import dev.marvinbarretto.jimbo.cap.telemetry.TelemetryDrainOutcome
import dev.marvinbarretto.jimbo.cap.telemetry.TelemetrySyncer
import dev.marvinbarretto.jimbo.cap.telemetry.TimeWindow
import java.time.Duration
import java.time.Instant

private const val TAG = "JimboSync"
private const val SYNC_WINDOW_HOURS = 2L
// Bridge looks further back than the collection window because HC may surface a
// completed exercise session hours after it ended, and the local dedup table
// makes it safe to re-scan the same range repeatedly.
private const val GYM_BRIDGE_WINDOW_HOURS = 24L

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val minBatteryPercent = SyncConstraintsRepository(applicationContext).get().minBatteryPercent
        if (!isBatteryHealthyOrCharging(minBatteryPercent)) {
            Log.d(TAG, "Skipping telemetry sync because battery is low and device is not charging")
            return Result.retry()
        }

        val now = Instant.now()
        val telemetryStore = TelemetryStore(applicationContext)
        telemetryStore.collect(
            TimeWindow(
                start = now.minus(Duration.ofHours(SYNC_WINDOW_HOURS)),
                end = now
            )
        )

        val drainOutcome = TelemetrySyncer(applicationContext).drainPending()

        // Bridge HC exercise sessions → gym sessions only after telemetry sync
        // succeeded — if the network is flaky, no point trying gym POSTs either.
        // Failures here are logged and swallowed; they should not fail the worker
        // and trigger a retry of the telemetry drain.
        if (drainOutcome is TelemetryDrainOutcome.Success) {
            try {
                val bridgeWindow = TimeWindow(
                    start = now.minus(Duration.ofHours(GYM_BRIDGE_WINDOW_HOURS)),
                    end = now
                )
                GymSessionBridge(applicationContext).bridgeRecent(bridgeWindow)
            } catch (e: Exception) {
                Log.e(TAG, "Gym session bridge failed", e)
            }
        }

        return when (drainOutcome) {
            is TelemetryDrainOutcome.Success -> Result.success()
            is TelemetryDrainOutcome.RetryableFailure -> Result.retry()
            is TelemetryDrainOutcome.PermanentFailure -> Result.failure()
        }
    }

    private fun isBatteryHealthyOrCharging(minBatteryPercent: Int): Boolean {
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

        val batteryPercent = level.toFloat() / scale.toFloat() * 100f
        return batteryPercent > minBatteryPercent.toFloat()
    }
}
