package dev.marvinbarretto.jimbo

import android.app.NotificationManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.marvinbarretto.jimbo.data.StepsDatabase
import dev.marvinbarretto.jimbo.telemetry.RawEvent
import dev.marvinbarretto.jimbo.telemetry.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant

private const val TAG = "JimboSync"
private const val COLLECTOR_ID = "notifications"

class NotificationCollectorService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        record("notifications.posted", sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        record("notifications.removed", sbn)
    }

    private fun record(type: String, sbn: StatusBarNotification) {
        scope.launch {
            val database = StepsDatabase.getInstance(applicationContext)
            val enabled = database.collectorSettingDao().isEnabled(COLLECTOR_ID) ?: false
            if (!enabled) return@launch

            val notification = sbn.notification ?: return@launch
            @Suppress("DEPRECATION")
            val importance = channelImportance(sbn) ?: notification.priority
            val hasText = notification.extras?.getCharSequence("android.text") != null

            val event = RawEvent(
                collector = COLLECTOR_ID,
                type = type,
                ts = Instant.ofEpochMilli(sbn.postTime),
                payload = mapOf(
                    "pkg" to sbn.packageName,
                    "category" to notification.category,
                    "importance" to importance,
                    "has_text" to hasText
                )
            )
            database.eventDao().insertAll(listOf(event.toEntity()))
            Log.d(TAG, "Recorded $type from ${sbn.packageName}")
        }
    }

    private fun channelImportance(sbn: StatusBarNotification): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val channelId = sbn.notification?.channelId ?: return null
        val nm = getSystemService(NotificationManager::class.java) ?: return null
        return nm.getNotificationChannel(channelId)?.importance
    }
}
