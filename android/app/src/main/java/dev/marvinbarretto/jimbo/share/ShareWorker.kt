package dev.marvinbarretto.jimbo.share

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.marvinbarretto.jimbo.JimboClient
import java.util.concurrent.TimeUnit

const val SHARE_TAG = "JimboShare"

/** Sends one shared link to POST /api/door. Any 200 counts as sent (the door may answer would_refuse until the API accepts the phone key). */
class ShareWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val json = inputData.getString(KEY_JSON) ?: return Result.failure()
        return try {
            val (code, body) = JimboClient.postJson("/api/door", json)
            Log.i(SHARE_TAG, "door $code: ${body.take(200)}")
            when {
                code == 200 -> Result.success()
                code in 400..499 && code != 408 && code != 429 -> Result.failure()
                else -> Result.retry()
            }
        } catch (e: java.io.IOException) {
            Log.w(SHARE_TAG, "send failed, will retry: ${e.message}")
            Result.retry()
        }
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
