package dev.marvinbarretto.jimbo.nudge

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dev.marvinbarretto.jimbo.NotificationChannels

/** Posts the food nudge (with an inline reply) and the retry notification. */
object NudgeNotifier {
    const val KEY_REPLY = "jimbo_food_reply"
    const val EXTRA_RETRY_TEXT = "jimbo_food_retry_text"
    const val ACTION_REPLY = "dev.marvinbarretto.jimbo.FOOD_REPLY"
    const val NUDGE_ID = 0x4f00d
    const val RETRY_ID = 0x4f00e

    private fun canPost(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun replyIntent(context: Context, retryText: String?): PendingIntent {
        val intent = Intent(context, FoodReplyReceiver::class.java).setAction(ACTION_REPLY).apply {
            if (retryText != null) putExtra(EXTRA_RETRY_TEXT, retryText)
        }
        // Mutable: the system fills the RemoteInput results into this intent.
        return PendingIntent.getBroadcast(
            context, if (retryText == null) 1 else 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    fun postNudge(context: Context) {
        if (!canPost(context)) return
        NotificationChannels.ensureCreated(context)
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("What did you eat?").build()
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit, "Log food", replyIntent(context, null),
        ).addRemoteInput(input).build()
        val n = NotificationCompat.Builder(context, NotificationChannels.NUDGES)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Nothing logged yet")
            .setContentText("What have you eaten?")
            .addAction(action)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NUDGE_ID, n)
    }

    fun postRetry(context: Context, text: String) {
        if (!canPost(context)) return
        NotificationChannels.ensureCreated(context)
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_popup_sync, "Retry", replyIntent(context, text),
        ).build()
        val n = NotificationCompat.Builder(context, NotificationChannels.NUDGES)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Couldn't log that")
            .setContentText(text)
            .addAction(action)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(RETRY_ID, n)
    }

    fun cancelRetry(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(RETRY_ID)

    fun cancelNudge(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(NUDGE_ID)
}
