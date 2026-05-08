package dev.marvinbarretto.jimbo

import android.app.NotificationManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.marvinbarretto.jimbo.data.StepsDatabase
import dev.marvinbarretto.jimbo.telemetry.RawEvent
import dev.marvinbarretto.jimbo.telemetry.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "JimboSync"
private const val COLLECTOR_ID = "notifications"
private const val MEDIA_COLLECTOR_ID = "media"

// Telegram and similar apps post 2-4 notifications within ~50ms for a single
// user-facing message (one per channel: individual, group, summary). Drop any
// duplicate (pkg, type) pair that arrives within this window.
private const val DEBOUNCE_MS = 1_000L

// If a media session ends and the same package starts again within this window,
// treat it as a track skip rather than a genuine stop/start. Suppresses the
// ended+started pair that Spotify (and others) emit on every track change.
private const val MEDIA_SESSION_DEBOUNCE_MS = 3_000L

class NotificationCollectorService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ConcurrentHashMap because record() dispatches onto multiple IO threads
    // simultaneously — plain HashMap would have races on burst arrivals.
    private val lastRecorded = ConcurrentHashMap<String, Long>()

    // Tracks currently active media session packages so we can diff on change.
    private var activeMediaSessions: Set<String> = emptySet()

    // Pending session_ended jobs keyed by package. Cancelled if the session
    // restarts within MEDIA_SESSION_DEBOUNCE_MS (track skip suppression).
    private val pendingSessionEnded = ConcurrentHashMap<String, Job>()

    private val mediaSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        val newSessions = controllers?.map { it.packageName }?.toSet() ?: emptySet()
        val started = newSessions - activeMediaSessions
        val ended = activeMediaSessions - newSessions
        activeMediaSessions = newSessions

        if (started.isEmpty() && ended.isEmpty()) return@OnActiveSessionsChangedListener

        // Handle restarts first: if a package appears in both ended and started
        // within the same callback, it's an immediate cycle — suppress both.
        val restarted = started.intersect(ended)

        started.forEach { pkg ->
            val pendingEnded = pendingSessionEnded.remove(pkg)
            if (pendingEnded != null) {
                // Session ended job is still pending — cancel it, this is a track skip.
                pendingEnded.cancel()
                Log.d(TAG, "Suppressed media session cycle (track skip) for $pkg")
                return@forEach
            }
            if (pkg in restarted) return@forEach

            // Genuine new session — record immediately.
            val controller = controllers?.find { it.packageName == pkg } ?: return@forEach
            scope.launch {
                val database = StepsDatabase.getInstance(applicationContext)
                val enabled = database.collectorSettingDao().isEnabled(MEDIA_COLLECTOR_ID) ?: false
                if (!enabled) return@launch
                val meta = controller.metadata
                val event = RawEvent(
                    collector = MEDIA_COLLECTOR_ID,
                    type = "media.session_started",
                    ts = Instant.now(),
                    payload = mapOf(
                        "pkg" to pkg,
                        "title" to meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
                        "artist" to meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                        "album" to meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    )
                )
                database.eventDao().insertAll(listOf(event.toEntity()))
                Log.d(TAG, "Recorded media.session_started from $pkg")
            }
        }

        ended.filter { it !in restarted }.forEach { pkg ->
            // Delay the ended event — if the session restarts within the window
            // the job is cancelled and neither event is written (track skip).
            val job = scope.launch {
                delay(MEDIA_SESSION_DEBOUNCE_MS)
                pendingSessionEnded.remove(pkg)
                val database = StepsDatabase.getInstance(applicationContext)
                val enabled = database.collectorSettingDao().isEnabled(MEDIA_COLLECTOR_ID) ?: false
                if (!enabled) return@launch
                val event = RawEvent(
                    collector = MEDIA_COLLECTOR_ID,
                    type = "media.session_ended",
                    ts = Instant.now(),
                    payload = mapOf("pkg" to pkg)
                )
                database.eventDao().insertAll(listOf(event.toEntity()))
                Log.d(TAG, "Recorded media.session_ended from $pkg")
            }
            pendingSessionEnded[pkg] = job
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Seed initial session state so the first diff is correct.
        // Without this, all sessions active at bind time would fire as "started".
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        val componentName = android.content.ComponentName(this, NotificationCollectorService::class.java)
        activeMediaSessions = msm.getActiveSessions(componentName)
            .map { it.packageName }.toSet()
        msm.addOnActiveSessionsChangedListener(mediaSessionsListener, componentName)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        getSystemService(MediaSessionManager::class.java)
            ?.removeOnActiveSessionsChangedListener(mediaSessionsListener)
        pendingSessionEnded.values.forEach { it.cancel() }
        pendingSessionEnded.clear()
        activeMediaSessions = emptySet()
    }

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
            if (!shouldRecord) return@launch

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
