package dev.marvinbarretto.jimbo

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Posts a notification scheduled by NotificationTriggerPlugin. Tapping it
 * launches MainActivity with [EXTRA_TAB], which the activity turns into a
 * navigation to `/m/<tab>`.
 */
class NotificationTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val tab = intent.getStringExtra(EXTRA_TAB) ?: DEFAULT_TAB
        NotificationChannels.ensureCreated(context)

        val tap = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_TAB, tab)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(
            context,
            NotificationChannels.resolve(intent.getStringExtra(EXTRA_CHANNEL)),
        )
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(intent.getStringExtra(EXTRA_TITLE))
            .setContentText(intent.getStringExtra(EXTRA_BODY))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(id.hashCode(), notification)
    }

    companion object {
        const val EXTRA_ID = "jimbo_notification_id"
        const val EXTRA_TITLE = "jimbo_notification_title"
        const val EXTRA_BODY = "jimbo_notification_body"
        const val EXTRA_CHANNEL = "jimbo_notification_channel"
        /** Also the extra MainActivity reads on launch. */
        const val EXTRA_TAB = "jimbo_tab"
        const val DEFAULT_TAB = "today"
    }
}
