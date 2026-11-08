package dev.marvinbarretto.jimbo.place

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.marvinbarretto.jimbo.JimboClient
import org.json.JSONObject
import kotlin.random.Random

private const val TAG = "JimboSync"

/**
 * Once you have stayed at a place with a quipList, shows one line from that
 * Jimbo list you have not seen yet, then marks it done (done_at = when you saw
 * it), so nothing repeats and the API knows what landed. A worker, not the
 * geofence receiver, because a broadcast has about ten seconds and the API call
 * may take longer. Offline or an empty list falls back to a plain line.
 */
class PlaceQuipWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val placeId = inputData.getString(KEY_PLACE) ?: return Result.failure()
        val list = inputData.getString(KEY_LIST) ?: return Result.failure()
        val label = inputData.getString(KEY_LABEL) ?: placeId
        val at = inputData.getLong(KEY_AT, System.currentTimeMillis())
        val quip = runCatching { takeQuip(list, placeId) }
            .onFailure { Log.w(TAG, "Quip fetch failed for $placeId", it) }
            .getOrNull()
        PlaceNotifier.show(applicationContext, placeId, "At $label", quip ?: FALLBACK, at)
        return Result.success()
    }

    private fun takeQuip(list: String, placeId: String): String? {
        val (code, body) = JimboClient.getWantedListEntries(list)
        if (code !in 200..299) return null
        val items = JSONObject(body).optJSONArray("items") ?: return null
        if (items.length() == 0) return null
        val pick = items.getJSONObject(Random.nextInt(items.length()))
        val title = pick.optString("title").takeIf { it.isNotBlank() } ?: return null
        val id = pick.optString("id")
        if (id.isNotBlank()) {
            val done = JSONObject()
                .put("status", "done")
                .put("attributes", JSONObject().put("shown_at_place", placeId))
            runCatching { JimboClient.patchListEntry(id, done.toString()) }
                .onFailure { Log.w(TAG, "Could not mark quip $id shown", it) }
        }
        return title
    }

    companion object {
        private const val KEY_PLACE = "place"
        private const val KEY_LIST = "list"
        private const val KEY_LABEL = "label"
        private const val KEY_AT = "at"
        const val FALLBACK = "You're here again. Jimbo has run out of jokes, which says something."

        fun enqueue(context: Context, fence: PlaceGeofence, atMillis: Long) {
            val list = fence.quipList ?: return
            val request = OneTimeWorkRequestBuilder<PlaceQuipWorker>()
                .setInputData(workDataOf(KEY_PLACE to fence.id, KEY_LIST to list, KEY_LABEL to fence.label, KEY_AT to atMillis))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("place-quip-${fence.id}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
