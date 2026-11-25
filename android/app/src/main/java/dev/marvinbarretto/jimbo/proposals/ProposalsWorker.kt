package dev.marvinbarretto.jimbo.proposals

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.marvinbarretto.jimbo.JimboClient
import dev.marvinbarretto.jimbo.data.StepsDatabase
import dev.marvinbarretto.jimbo.telemetry.JimboCollector
import dev.marvinbarretto.jimbo.telemetry.RawEvent
import dev.marvinbarretto.jimbo.telemetry.toEntity
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

const val PROPOSALS_TAG = "JimboProposals"

/** Jimbo-event writes onto the telemetry queue; an unseeded setting row means the collector's default: on. */
object ProposalEvents {
    suspend fun record(context: Context, event: RawEvent) {
        try {
            val db = StepsDatabase.getInstance(context.applicationContext)
            if (db.collectorSettingDao().isEnabled(JimboCollector.ID) == false) return
            db.eventDao().insertAll(listOf(event.toEntity()))
        } catch (e: Exception) {
            Log.e(PROPOSALS_TAG, "event ${event.type} not recorded", e)
        }
    }
}

/**
 * Every 15 minutes: fetch Jimbo's pending proposals newer than the last one notified, and notify. The API may
 * not be live, or the key may be refused: any non-200 is "nothing new", logged and evented, never shown.
 */
class ProposalsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = Instant.now()
        val lastAt = prefs.getString(KEY_LAST_AT, null)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val since = Proposals.since(lastAt, now)

        val path = "/api/jimbo/log?kind=proposed&verdict=pending&since=${URLEncoder.encode(since.toString(), "UTF-8")}&limit=${Proposals.LIMIT}"
        val (code, body) = try {
            JimboClient.getJson(path)
        } catch (e: Exception) {
            Log.w(TAG, "poll failed: ${e.javaClass.simpleName}: ${e.message}")
            ProposalEvents.record(app, JimboCollector.poll(0, null, e.javaClass.simpleName, now))
            return Result.success()
        }
        if (code != 200) {
            Log.i(TAG, "poll $code: nothing new")
            ProposalEvents.record(app, JimboCollector.poll(0, code, null, now))
            return Result.success()
        }

        val entries = Proposals.parse(body)
        ProposalEvents.record(app, JimboCollector.poll(entries.size, code, null, now))
        val plan = Proposals.plan(entries, since)
        Log.i(TAG, "poll 200: ${entries.size} fetched, ${plan.fresh.size} new, summary=${plan.summary}")
        if (plan.fresh.isEmpty()) return Result.success()
        // Without the permission nothing can be shown: keep the cursor so they surface once it is granted.
        if (!ProposalNotifier.canPost(app)) {
            Log.w(TAG, "${plan.fresh.size} new but POST_NOTIFICATIONS not granted")
            return Result.success()
        }

        if (plan.summary) ProposalNotifier.postSummary(app, plan.fresh.size)
        else plan.fresh.forEach { ProposalNotifier.postProposal(app, it) }
        plan.fresh.forEach { ProposalEvents.record(app, JimboCollector.notified(it.id, plan.summary, Instant.now())) }
        plan.cursor?.let { prefs.edit().putString(KEY_LAST_AT, it.toString()).apply() }
        return Result.success()
    }

    companion object {
        private const val TAG = PROPOSALS_TAG
        private const val PREFS = "jimbo_proposals"
        private const val KEY_LAST_AT = "last_at"
        private const val WORK_NAME = "jimbo_proposals_poll"
        private const val NOW_NAME = "jimbo_proposals_poll_now"
        private val NETWORK = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** 15 minutes is WorkManager's floor. Also one poll now, so a launch does not wait out the interval. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ProposalsWorker>(15, TimeUnit.MINUTES).setConstraints(NETWORK).build(),
            )
            wm.enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ProposalsWorker>().setConstraints(NETWORK).build())
        }
    }
}
