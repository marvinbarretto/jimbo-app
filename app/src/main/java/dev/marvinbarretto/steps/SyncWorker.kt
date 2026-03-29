package dev.marvinbarretto.steps

import android.content.Context
import android.content.SharedPreferences
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.Instant

/**
 * Background worker that syncs Health Connect data to Jimbo on a schedule.
 *
 * WorkManager is Android's system for scheduling background tasks. It:
 * - Survives app restarts and phone reboots
 * - Respects battery optimization (won't run when battery is low)
 * - Only runs when network is available (we configure this constraint)
 * - Handles retries automatically
 *
 * Think of it like a cron job that Android manages for you.
 * We schedule it hourly in MainActivity, and it syncs the last 2 hours
 * of data each time (the overlap handles missed runs).
 *
 * CoroutineWorker is the Kotlin-flavoured version of Worker — it
 * supports suspend functions so we can call Health Connect's async APIs.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /**
     * Called by WorkManager when it's time to sync.
     * Returns Result.success(), Result.retry(), or Result.failure().
     * On retry, WorkManager will try again later with exponential backoff.
     */
    override suspend fun doWork(): Result {
        return try {
            val end = Instant.now()
            // Read the last 2 hours — overlaps with previous run to catch
            // any records that were written between syncs
            val start = end.minus(Duration.ofHours(2))

            val payload = HealthConnectReader.readAll(applicationContext, start, end)

            // Don't POST if there's nothing to sync
            if (payload.getJSONArray("records").length() == 0) return Result.success()

            val (code, _) = JimboClient.postSync(payload.toString())

            if (code in 200..299) {
                // Save the last successful sync time so we could show it
                // in the UI if we ever add one
                prefs().edit().putLong("last_sync", System.currentTimeMillis()).apply()
                Result.success()
            } else {
                // Non-2xx response — retry later
                Result.retry()
            }
        } catch (e: Exception) {
            // Network error, Health Connect unavailable, etc. — retry later
            Result.retry()
        }
    }

    // SharedPreferences is Android's simple key-value store (like localStorage)
    private fun prefs(): SharedPreferences =
        applicationContext.getSharedPreferences("steps_sync", Context.MODE_PRIVATE)
}
