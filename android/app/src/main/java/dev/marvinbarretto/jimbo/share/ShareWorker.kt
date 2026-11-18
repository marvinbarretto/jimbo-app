package dev.marvinbarretto.jimbo.share

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.marvinbarretto.jimbo.JimboClient
import dev.marvinbarretto.jimbo.NotificationChannels
import java.util.concurrent.TimeUnit

const val SHARE_TAG = "JimboShare"

/**
 * Sends one shared link to POST /api/door. Only an accepted or duplicate answer counts as arrived (ShareOutcome);
 * anything else that will not succeed on retry posts a notification naming the link, so a failed share is never silent.
 */
class ShareWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val json = inputData.getString(KEY_JSON) ?: return Result.failure()
        return try {
            val (code, body) = JimboClient.postJson("/api/door", json)
            Log.i(SHARE_TAG, "door $code: ${body.take(200)}")
            when (ShareOutcome.of(code, body)) {
                ShareOutcome.ARRIVED -> Result.success()
                ShareOutcome.RETRY -> Result.retry()
                ShareOutcome.FLAGGED -> { notifyFailed("Jimbo flagged it", json); Result.success() }
                ShareOutcome.REJECTED -> { notifyFailed("Jimbo refused it ($code)", json); Result.failure() }
            }
        } catch (e: java.io.IOException) {
            Log.w(SHARE_TAG, "send failed, will retry: ${e.message}")
            Result.retry()
        }
    }

    private fun notifyFailed(why: String, json: String) {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val link = Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: "the link"
        NotificationChannels.ensureCreated(ctx)
        val n = NotificationCompat.Builder(ctx, NotificationChannels.NUDGES)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Share didn't reach Jimbo: $why")
            .setContentText(link)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(link.hashCode(), n)
    }

    companion object {
        private const val KEY_JSON = "json"

        fun enqueue(context: Context, item: SharedItem) {
            val request = OneTimeWorkRequestBuilder<ShareWorker>()
                .setInputData(Data.Builder().putString(KEY_JSON, ShareLink.doorJson(item)).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            Log.i(SHARE_TAG, "queued ${item.title.take(100)}")
        }
    }
}
