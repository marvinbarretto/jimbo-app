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
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "JimboSync"
private const val COLLECTOR_ID = "notifications"

// Telegram and similar apps post 2-4 notifications within ~50ms for a single
// user-facing message (one per channel: individual, group, summary). Drop any
// duplicate (pkg, type) pair that arrives within this window.
private const val DEBOUNCE_MS = 1_000L

class NotificationCollectorService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ConcurrentHashMap because record() dispatches onto multiple IO threads
    // simultaneously — plain HashMap would have races on burst arrivals.
    private val lastRecorded = ConcurrentHashMap<String, Long>()

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

            // Drop silent and background notifications (importance < 0).
            // Telegram posts importance:-1 summary notifications alongside every
            // visible message; Google services fire importance:-2 background probes.
            // Neither has user-facing value — keeping them would 2-4x event counts.
            if (importance < 0) {
                Log.d(TAG, "Dropped low-importance notification from ${sbn.packageName} (importance=$importance)")
                return@launch
            }

            // Debounce: collapse bursts of the same (pkg, type) within DEBOUNCE_MS.
            // Telegram still fires 2 importance:0 events per message after the
            // importance filter above — this reduces that to exactly one.
            val key = "${sbn.packageName}:$type"
            val now = System.currentTimeMillis()
            var shouldRecord = false
            lastRecorded.compute(key) { _, last ->
                if (last == null || now - last >= DEBOUNCE_MS) {
                    shouldRecord = true
                    now
                } else {
                    last
                }
            }
            if (!shouldRecord) {
                Log.d(TAG, "Debounced $type from ${sbn.packageName}")
                return@launch
            }

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
