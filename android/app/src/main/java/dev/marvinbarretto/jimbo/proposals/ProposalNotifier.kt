package dev.marvinbarretto.jimbo.proposals

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dev.marvinbarretto.jimbo.MainActivity
import dev.marvinbarretto.jimbo.NotificationChannels
import dev.marvinbarretto.jimbo.widgets.WidgetLinks

/** Posts the proposal notifications: one per proposal with Right / Wrong (reply) / Do it, or one summary. */
object ProposalNotifier {
    const val ACTION_VERDICT = "dev.marvinbarretto.jimbo.PROPOSAL_VERDICT"
    const val EXTRA_ID = "proposal_id"
    const val EXTRA_VERDICT = "proposal_verdict"
    const val EXTRA_BRIEF = "proposal_brief"
    const val KEY_WHY = "proposal_why"
    private const val SUMMARY_ID = 0x7a5a1

    fun canPost(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notificationId(proposalId: String) = proposalId.hashCode()

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun openPage(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("jimbo-proposals://open${WidgetLinks.JIMBO}")
            putExtra(WidgetLinks.EXTRA_PATH, WidgetLinks.JIMBO)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun verdictIntent(context: Context, p: Proposal, verdict: Verdict, mutable: Boolean): PendingIntent {
        val intent = Intent(context, ProposalVerdictReceiver::class.java).setAction(ACTION_VERDICT).apply {
            // Distinct data per proposal and verdict so the PendingIntents never collapse into one.
            data = Uri.parse("jimbo-proposals://verdict/${p.id}/${verdict.wire}")
            putExtra(EXTRA_ID, p.id)
            putExtra(EXTRA_VERDICT, verdict.wire)
            putExtra(EXTRA_BRIEF, Proposals.brief(p.text))
        }
        // The Wrong action is mutable: the system fills the RemoteInput reply into its intent.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, "${p.id}:${verdict.wire}".hashCode(), intent, flags)
    }

    fun postProposal(context: Context, p: Proposal) {
        if (!canPost(context)) return
        NotificationChannels.ensureCreated(context)
        val why = RemoteInput.Builder(KEY_WHY).setLabel("Why?").build()
        val n = NotificationCompat.Builder(context, NotificationChannels.JIMBO_PROPOSALS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Jimbo would…")
            .setContentText(p.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(p.text))
            .setContentIntent(openPage(context, notificationId(p.id)))
            .addAction(android.R.drawable.checkbox_on_background, "Right", verdictIntent(context, p, Verdict.RIGHT, false))
            .addAction(
                NotificationCompat.Action.Builder(android.R.drawable.ic_delete, "Wrong", verdictIntent(context, p, Verdict.WRONG, true))
                    .addRemoteInput(why).build(),
            )
            .addAction(android.R.drawable.ic_media_play, "Do it", verdictIntent(context, p, Verdict.DO_IT, false))
            .setAutoCancel(true)
            .setWhen(p.at.toEpochMilli())
            .build()
        manager(context).notify(notificationId(p.id), n)
    }

    fun postSummary(context: Context, count: Int) {
        if (!canPost(context)) return
        NotificationChannels.ensureCreated(context)
        val n = NotificationCompat.Builder(context, NotificationChannels.JIMBO_PROPOSALS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Jimbo: $count proposals")
            .setContentText("Tap to read them and mark each")
            .setContentIntent(openPage(context, SUMMARY_ID))
            .setAutoCancel(true)
            .build()
        manager(context).notify(SUMMARY_ID, n)
    }

    /** Replaces the proposal's notification with a short line; [timeoutMs] 0 leaves it until swiped. */
    fun postStatus(context: Context, proposalId: String, title: String, text: String?, timeoutMs: Long, error: Boolean = false) {
        if (!canPost(context)) return
        NotificationChannels.ensureCreated(context)
        val n = NotificationCompat.Builder(context, NotificationChannels.JIMBO_PROPOSALS)
            .setSmallIcon(if (error) android.R.drawable.stat_notify_error else android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setContentIntent(openPage(context, notificationId(proposalId)))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .apply { if (timeoutMs > 0) setTimeoutAfter(timeoutMs) }
            .build()
        manager(context).notify(notificationId(proposalId), n)
    }
}
