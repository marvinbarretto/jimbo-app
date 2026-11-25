package dev.marvinbarretto.jimbo.proposals

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.marvinbarretto.jimbo.JimboClient
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Receives Right / Wrong (with its reply) / Do it, shows "sending", and queues the POST. Never opens the app. */
class ProposalVerdictReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ProposalNotifier.EXTRA_ID) ?: return
        val verdict = Verdict.of(intent.getStringExtra(ProposalNotifier.EXTRA_VERDICT)) ?: return
        val brief = intent.getStringExtra(ProposalNotifier.EXTRA_BRIEF) ?: ""
        val note = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(ProposalNotifier.KEY_WHY)?.toString()
        val app = context.applicationContext
        // Replacing the notification also stops the reply box spinning.
        ProposalNotifier.postStatus(app, id, "Sending…", brief, timeoutMs = 0)
        ProposalVerdictWorker.enqueue(app, id, verdict, note, brief)
    }
}

/**
 * POSTs one verdict to /api/jimbo/log/{id}/verdict, then confirms in the notification. 409 (already has a verdict)
 * counts as done. A verdict that finally cannot be sent posts "Verdict didn't reach Jimbo", so it is never silent.
 */
class ProposalVerdictWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val verdict = Verdict.of(inputData.getString(KEY_VERDICT)) ?: return Result.failure()
        val note = inputData.getString(KEY_NOTE)
        val brief = inputData.getString(KEY_BRIEF) ?: ""
        val app = applicationContext
        val hasNote = !note.isNullOrBlank()

        var status: Int? = null
        val outcome = try {
            val (code, body) = JimboClient.postJson("/api/jimbo/log/$id/verdict", Proposals.verdictBody(verdict, note))
            status = code
            Log.i(TAG, "verdict $id ${verdict.wire} -> $code: ${body.take(120)}")
            VerdictOutcome.of(code)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "verdict $id ${verdict.wire} failed, will retry: ${e.message}")
            VerdictOutcome.RETRY
        }

        if (outcome == VerdictOutcome.RETRY && runAttemptCount < MAX_ATTEMPTS) return Result.retry()

        val ok = outcome == VerdictOutcome.DONE
        ProposalEvents.record(app, dev.marvinbarretto.jimbo.telemetry.JimboCollector.verdict(id, verdict, ok, hasNote, status, Instant.now()))
        if (ok) {
            ProposalNotifier.postStatus(app, id, Proposals.confirmation(verdict), brief, timeoutMs = CONFIRM_MS)
            return Result.success()
        }
        ProposalNotifier.postStatus(app, id, "Verdict didn't reach Jimbo", brief, timeoutMs = 0, error = true)
        return Result.failure()
    }

    companion object {
        private const val TAG = PROPOSALS_TAG
        private const val KEY_ID = "id"
        private const val KEY_VERDICT = "verdict"
        private const val KEY_NOTE = "note"
        private const val KEY_BRIEF = "brief"
        private const val MAX_ATTEMPTS = 8
        private const val CONFIRM_MS = 5_000L

        fun enqueue(context: Context, id: String, verdict: Verdict, note: String?, brief: String) {
            val data = Data.Builder().putString(KEY_ID, id).putString(KEY_VERDICT, verdict.wire).putString(KEY_BRIEF, brief)
                .apply { if (!note.isNullOrBlank()) putString(KEY_NOTE, note.trim()) }.build()
            val request = OneTimeWorkRequestBuilder<ProposalVerdictWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "queued verdict $id ${verdict.wire} note=${!note.isNullOrBlank()}")
        }
    }
}
