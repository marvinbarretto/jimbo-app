package dev.marvinbarretto.jimbo.place

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.marvinbarretto.jimbo.MainActivity
import dev.marvinbarretto.jimbo.NotificationChannels

/** The one notification a place posts once you have stayed there, plain or with a quip. */
object PlaceNotifier {
    private const val NOTIFICATION_BASE = 0x706c0000

    fun show(context: Context, placeId: String, title: String, text: String, atMillis: Long) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationChannels.ensureCreated(context)
        val tap = PendingIntent.getActivity(
            context,
            placeId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.PLACES)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setWhen(atMillis)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_BASE + (placeId.hashCode() and 0xffff), notification)
    }
}
