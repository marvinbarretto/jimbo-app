package dev.marvinbarretto.jimbo.plugins

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import dev.marvinbarretto.jimbo.NotificationChannels
import dev.marvinbarretto.jimbo.NotificationTriggerReceiver

/**
 * Lets the hosted shell schedule native notifications. Plumbing only — callers
 * own content and timing. Uses inexact alarms (no SCHEDULE_EXACT_ALARM grant
 * needed); they do not survive a reboot.
 */
@CapacitorPlugin(name = "NotificationTrigger")
class NotificationTriggerPlugin : Plugin() {

    override fun load() {
        NotificationChannels.ensureCreated(context)
    }

    @PluginMethod
    fun schedule(call: PluginCall) {
        val id = call.getString("id")
        val title = call.getString("title")
        val body = call.getString("body")
        val atMillis = call.getDouble("atMillis")
        if (id.isNullOrEmpty() || title == null || body == null || atMillis == null) {
            call.reject("id, title, body and atMillis are required")
            return
        }
        val tab = call.getString("tab")?.takeIf { TAB_PATTERN.matches(it) }
            ?: NotificationTriggerReceiver.DEFAULT_TAB

        val intent = receiverIntent(id).apply {
            putExtra(NotificationTriggerReceiver.EXTRA_TITLE, title)
            putExtra(NotificationTriggerReceiver.EXTRA_BODY, body)
            putExtra(NotificationTriggerReceiver.EXTRA_CHANNEL, NotificationChannels.resolve(call.getString("channelId")))
            putExtra(NotificationTriggerReceiver.EXTRA_TAB, tab)
        }
        val pending = PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis.toLong(), pending)
        call.resolve()
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        val id = call.getString("id")
        if (id.isNullOrEmpty()) {
            call.reject("id is required")
            return
        }
        val pending = PendingIntent.getBroadcast(
            context, id.hashCode(), receiverIntent(id),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) {
            context.getSystemService(AlarmManager::class.java).cancel(pending)
            pending.cancel()
        }
        call.resolve()
    }

    private fun receiverIntent(id: String) =
        Intent(context, NotificationTriggerReceiver::class.java).apply {
            putExtra(NotificationTriggerReceiver.EXTRA_ID, id)
        }

    private companion object {
        val TAB_PATTERN = Regex("[a-z0-9-]+")
    }
}
