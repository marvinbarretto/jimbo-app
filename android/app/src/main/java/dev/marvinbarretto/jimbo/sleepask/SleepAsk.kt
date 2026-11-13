package dev.marvinbarretto.jimbo.sleepask

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dev.marvinbarretto.jimbo.NotificationChannels
import dev.marvinbarretto.jimbo.data.StepsDatabase
import dev.marvinbarretto.jimbo.telemetry.RawEvent
import dev.marvinbarretto.jimbo.telemetry.SleepCollector
import dev.marvinbarretto.jimbo.telemetry.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "JimboSleepAsk"
private const val PREFS = "sleep_ask"

/**
 * Asks Marvin, on the first unlock after a long phone-down stretch, whether he
 * was asleep and when. His answers are the ground truth the sleep-metric
 * project scores every source against (SM-7959). Events, collector `sleep`:
 * `sleep.ask` when the question is posted, `sleep.label` with his answer
 * (kind slept or not_asleep, his words verbatim), `sleep.ask_dismissed` when
 * swiped away. Each carries the phone-down stretch the phone saw.
 *
 * USER_PRESENT and SCREEN_OFF only reach runtime receivers, so this registers
 * for the life of the process (kept alive by the notification listener), like
 * DeviceBroadcasts.
 */
object SleepAsk {
    const val KEY_REPLY = "jimbo_sleep_reply"
    const val ACTION_REPLY = "dev.marvinbarretto.jimbo.SLEEP_REPLY"
    const val ACTION_NOT_ASLEEP = "dev.marvinbarretto.jimbo.SLEEP_NOT_ASLEEP"
    const val ACTION_DISMISSED = "dev.marvinbarretto.jimbo.SLEEP_DISMISSED"
    const val EXTRA_ASKED_AT = "asked_at"
    const val EXTRA_DOWN_FROM = "down_from"
    const val EXTRA_DOWN_UNTIL = "down_until"
    private const val NOTIFICATION_ID = 0x51ee9

    @Volatile private var registered = false

    fun register(context: Context) {
        if (registered) return
        registered = true
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                runCatching {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> onScreenOff(app)
                        Intent.ACTION_USER_PRESENT -> onUnlock(app)
                    }
                }.onFailure { Log.w(TAG, "Sleep ask ${intent.action} failed", it) }
            }
        }
        ContextCompat.registerReceiver(
            app, receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readState(context: Context): PhoneDownState {
        val p = prefs(context)
        val since = p.getLong("down_since", 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        return PhoneDownState(unlocked = p.getBoolean("unlocked", true), downSince = since)
    }

    private fun writeState(context: Context, state: PhoneDownState) {
        prefs(context).edit()
            .putBoolean("unlocked", state.unlocked)
            .putLong("down_since", state.downSince?.toEpochMilli() ?: 0L)
            .apply()
    }

    private fun onScreenOff(context: Context) {
        writeState(context, PhoneDown.onScreenOff(readState(context), Instant.now()))
    }

    private fun onUnlock(context: Context) {
        val now = Instant.now()
        val result = PhoneDown.onUnlock(readState(context), now)
        writeState(context, result.state)
        val lastAsked = prefs(context).getLong("last_asked", 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        if (!WakeAskGate.shouldAsk(result.downSince, now, lastAsked)) return
        val downFrom = result.downSince ?: return
        if (!post(context, now, downFrom, now)) return
        prefs(context).edit().putLong("last_asked", now.toEpochMilli()).apply()
        record(context, "sleep.ask", now, downFrom, now, mapOf("asked_at" to now.toString()))
    }

    private fun post(context: Context, askedAt: Instant, downFrom: Instant, downUntil: Instant): Boolean {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "No notification permission; sleep question not asked")
            return false
        }
        NotificationChannels.ensureCreated(context)
        fun intent(action: String, code: Int, mutable: Boolean): PendingIntent {
            val i = Intent(context, SleepAskReceiver::class.java).setAction(action)
                .putExtra(EXTRA_ASKED_AT, askedAt.toEpochMilli())
                .putExtra(EXTRA_DOWN_FROM, downFrom.toEpochMilli())
                .putExtra(EXTRA_DOWN_UNTIL, downUntil.toEpochMilli())
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
            return PendingIntent.getBroadcast(context, code, i, flags)
        }
        val since = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(downFrom)
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("e.g. 3:30 to 10:15").build()
        val reply = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit, "Slept…", intent(ACTION_REPLY, 11, mutable = true),
        ).addRemoteInput(input).build()
        val notAsleep = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "Wasn't asleep",
            intent(ACTION_NOT_ASLEEP, 12, mutable = false),
        ).build()
        val n = NotificationCompat.Builder(context, NotificationChannels.SLEEP)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Were you asleep?")
            .setContentText("Phone down since $since. When did you fall asleep and wake?")
            .addAction(reply)
            .addAction(notAsleep)
            .setDeleteIntent(intent(ACTION_DISMISSED, 13, mutable = false))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        return true
    }

    fun cancel(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)

    fun record(
        context: Context, type: String, ts: Instant, downFrom: Instant, downUntil: Instant,
        extra: Map<String, Any?>,
    ) {
        val event = RawEvent(
            collector = SleepCollector.ID, type = type, ts = ts,
            value = Duration.between(downFrom, downUntil).toMinutes().toDouble(), unit = "minutes_down",
            source = "sleep_ask",
            payload = mapOf("phone_down_from" to downFrom.toString(), "phone_down_until" to downUntil.toString()) + extra,
        )
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val database = StepsDatabase.getInstance(context)
                if (database.collectorSettingDao().isEnabled(SleepCollector.ID) == false) return@launch
                database.eventDao().insertAll(listOf(event.toEntity()))
                Log.d(TAG, "Recorded $type")
            }.onFailure { Log.w(TAG, "$type not recorded", it) }
        }
    }
}

/** His answer, the "wasn't asleep" tap, or a swipe-away. Never opens the app. */
class SleepAskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val now = Instant.now()
        val askedAt = Instant.ofEpochMilli(intent.getLongExtra(SleepAsk.EXTRA_ASKED_AT, now.toEpochMilli()))
        val downFrom = Instant.ofEpochMilli(intent.getLongExtra(SleepAsk.EXTRA_DOWN_FROM, now.toEpochMilli()))
        val downUntil = Instant.ofEpochMilli(intent.getLongExtra(SleepAsk.EXTRA_DOWN_UNTIL, now.toEpochMilli()))
        val base = mapOf("asked_at" to askedAt.toString())
        when (intent.action) {
            SleepAsk.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(SleepAsk.KEY_REPLY)?.toString()?.trim()
                if (text.isNullOrEmpty()) return
                SleepAsk.record(app, "sleep.label", now, downFrom, downUntil, base + mapOf("kind" to "slept", "answer" to text))
            }
            SleepAsk.ACTION_NOT_ASLEEP ->
                SleepAsk.record(app, "sleep.label", now, downFrom, downUntil, base + mapOf("kind" to "not_asleep"))
            SleepAsk.ACTION_DISMISSED -> {
                SleepAsk.record(app, "sleep.ask_dismissed", now, downFrom, downUntil, base)
                return
            }
            else -> return
        }
        SleepAsk.cancel(app)
    }
}
