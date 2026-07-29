package dev.marvinbarretto.jimbo

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.marvinbarretto.jimbo.data.ScreenPostEntity
import dev.marvinbarretto.jimbo.data.StepsDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * ScreenSyncWorker — drains the harvested-post queue to LocalShout.
 *
 * CONCEPT MAPPING (JS / Angular)
 * WorkManager is the OS's **durable job scheduler**. Closest web analogue is a
 * service worker's Background Sync: you describe the work and its constraints
 * ("only on a network"), and the system runs it later — after the app is closed,
 * after a reboot, whenever conditions are met. Unlike `setInterval`, it survives
 * the process dying, which is the whole point.
 *
 * `CoroutineWorker.doWork()` is a suspend function, so awaiting inside it is
 * ordinary `await`-style code rather than callbacks.
 *
 * DELETE-ON-CONFIRM
 * Rows leave the queue only after the server answers 2xx. Any other outcome —
 * offline, 500, timeout — increments `attempts` and leaves them queued, because
 * deleting on failure loses captures that can never be re-harvested: the post
 * has scrolled away and there is no URL to fetch it back from.
 */
class ScreenSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!LocalShoutClient.isConfigured()) {
            Log.d(TAG, "no LocalShout ingest config; skipping (set localshout.* in local.properties)")
            return Result.success()
        }

        val dao = StepsDatabase.getInstance(applicationContext).screenPostDao()

        val dropped = dao.dropExhausted(MAX_ATTEMPTS)
        if (dropped > 0) {
            // Visible drops, never silent — the house rule for anything bounded.
            Log.w(TAG, "dropped $dropped post(s) after $MAX_ATTEMPTS failed attempts")
        }

        val batch = dao.pendingBatch(BATCH_SIZE)
        if (batch.isEmpty()) return Result.success()

        val (status, body) = LocalShoutClient.postScreenBatch(buildPayload(batch, dropped))

        return when {
            status in 200..299 -> {
                dao.deleteSent(batch.map { it.contentHash })
                Log.i(TAG, "synced ${batch.size} post(s) → $body")
                // More may be queued than one batch holds; ask to run again soon.
                if (dao.pendingCount() > 0) Result.retry() else Result.success()
            }

            // 4xx means this batch will never be accepted — malformed or
            // unauthorised. Retrying forever would just burn battery, so count
            // it against the attempt budget and let dropExhausted clear it.
            status in 400..499 -> {
                dao.incrementAttempts(batch.map { it.contentHash })
                Log.e(TAG, "rejected ($status): $body")
                Result.success()
            }

            else -> {
                dao.incrementAttempts(batch.map { it.contentHash })
                Log.w(TAG, "sync failed ($status); ${batch.size} post(s) stay queued")
                Result.retry()
            }
        }
    }

    private fun buildPayload(batch: List<ScreenPostEntity>, dropped: Int): String {
        val records = JSONArray()
        batch.forEach { post ->
            records.put(
                JSONObject().apply {
                    put("content_hash", post.contentHash)
                    put("handle", post.handle)
                    put("kind_hint", post.kindHint)
                    put("captured_at", Instant.ofEpochMilli(post.capturedAt).toString())
                    // JSONObject.put(null) silently omits the key, so nulls are
                    // written explicitly — the server schema expects the field.
                    put("caption", post.caption ?: JSONObject.NULL)
                    put("caption_truncated", post.captionTruncated)
                    put("alt_text", post.altText ?: JSONObject.NULL)
                    put("location_name", post.locationName ?: JSONObject.NULL)
                    put("posted_at_label", post.postedAtLabel ?: JSONObject.NULL)
                }
            )
        }

        return JSONObject().apply {
            put("device_id", BuildConfig.JIMBO_DEVICE_ID)
            put("dropped_since_last", dropped)
            put("records", records)
        }.toString()
    }

    companion object {
        private const val TAG = "JimboScreen"
        private const val WORK_NAME = "screen_sync"

        /** Matches the server schema's `records` ceiling. */
        private const val BATCH_SIZE = 50
        private const val MAX_ATTEMPTS = 5

        /**
         * 15 minutes is WorkManager's minimum periodic interval — the OS refuses
         * anything shorter to protect battery. Nothing here is time-critical:
         * the extraction drain on the server runs hourly anyway.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScreenSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
