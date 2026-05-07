package dev.marvinbarretto.steps

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.marvinbarretto.steps.telemetry.SyncConstraintsRepository
import dev.marvinbarretto.steps.telemetry.TelemetryStore
import dev.marvinbarretto.steps.telemetry.TelemetryDrainOutcome
import dev.marvinbarretto.steps.telemetry.TelemetrySyncer
import dev.marvinbarretto.steps.telemetry.TimeWindow
import java.time.Duration
import java.time.Instant

private const val TAG = "StepsSync"
private const val SYNC_WINDOW_HOURS = 2L

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

        return when (TelemetrySyncer(applicationContext).drainPending()) {
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
