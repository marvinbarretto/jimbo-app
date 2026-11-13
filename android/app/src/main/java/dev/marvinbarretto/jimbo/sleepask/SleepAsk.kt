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
 * Asks Marvin "were you asleep?" once per night, covering the whole night
 * (see Night). His answers are the ground truth the sleep-metric project
 * scores every source against (SM-7959). Events, collector `sleep`, source
 * `sleep_ask`: `sleep.ask` each time the question is posted or refreshed,
 * `sleep.label` with his answer (kind slept or not_asleep, his words
 * verbatim), `sleep.ask_hidden` for Later or a swipe, `sleep.ask_expired` for
 * a night never answered. Each carries the night's phone-down stretches.
 *
 * USER_PRESENT and SCREEN_OFF only reach runtime receivers, so this registers
 * for the life of the process (kept alive by the notification listener), like
 * DeviceBroadcasts.
 */
object SleepAsk {
    const val KEY_REPLY = "jimbo_sleep_reply"
    const val ACTION_REPLY = "dev.marvinbarretto.jimbo.SLEEP_REPLY"
    const val ACTION_NOT_ASLEEP = "dev.marvinbarretto.jimbo.SLEEP_NOT_ASLEEP"
    const val ACTION_LATER = "dev.marvinbarretto.jimbo.SLEEP_LATER"
    const val ACTION_DISMISSED = "dev.marvinbarretto.jimbo.SLEEP_DISMISSED"
    private const val NOTIFICATION_ID = 0x51ee9
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

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

    // ── State, in shared prefs so a process restart keeps the night ──────────

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun instant(ms: Long) = ms.takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    private fun readPhone(context: Context): PhoneDownState {
        val p = prefs(context)
        return PhoneDownState(unlocked = p.getBoolean("unlocked", true), downSince = instant(p.getLong("down_since", 0L)))
    }

    private fun writePhone(context: Context, s: PhoneDownState) {
        prefs(context).edit().putBoolean("unlocked", s.unlocked)
            .putLong("down_since", s.downSince?.toEpochMilli() ?: 0L).apply()
    }

    fun readNight(context: Context): NightState {
        val p = prefs(context)
        val stretches = p.getString("stretches", "").orEmpty().split(';').filter { it.isNotBlank() }.map {
            val (a, b) = it.split('-')
            Stretch(Instant.ofEpochMilli(a.toLong()), Instant.ofEpochMilli(b.toLong()))
        }
        return NightState(stretches, instant(p.getLong("hidden_at", 0L)), instant(p.getLong("last_slept", 0L)))
    }

    fun writeNight(context: Context, s: NightState) {
        prefs(context).edit()
            .putString("stretches", s.stretches.joinToString(";") { "${it.from.toEpochMilli()}-${it.until.toEpochMilli()}" })
            .putLong("hidden_at", s.hiddenAt?.toEpochMilli() ?: 0L)
            .putLong("last_slept", s.lastSlept?.toEpochMilli() ?: 0L)
            .apply()
    }

    // ── Phone events ──────────────────────────────────────────────────────────

    private fun onScreenOff(context: Context) {
        writePhone(context, PhoneDown.onScreenOff(readPhone(context), Instant.now()))
    }

    private fun onUnlock(context: Context) {
        val now = Instant.now()
        val phone = PhoneDown.onUnlock(readPhone(context), now)
        writePhone(context, phone.state)
        val r = Night.onUnlock(readNight(context), phone.downSince, now)
        writeNight(context, r.state)
        r.expired?.let {
            cancel(context)
            record(context, "sleep.ask_expired", now, it, emptyMap())
        }
        if (r.post && post(context, r.state.stretches)) {
            record(context, "sleep.ask", now, r.state.stretches, emptyMap())
        }
    }

    private fun post(context: Context, stretches: List<Stretch>): Boolean {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "No notification permission; sleep question not asked")
            return false
        }
        NotificationChannels.ensureCreated(context)
        fun intent(action: String, code: Int, mutable: Boolean): PendingIntent {
            val i = Intent(context, SleepAskReceiver::class.java).setAction(action)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
            return PendingIntent.getBroadcast(context, code, i, flags)
        }
        val down = stretches.joinToString(", ") { "${hhmm.format(it.from)}–${hhmm.format(it.until)}" }
        val text = "Phone down $down. When did you fall asleep and wake? Any breaks?"
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("e.g. 2 to 10, up at 5 for a bit").build()
        val reply = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit, "Slept…", intent(ACTION_REPLY, 11, mutable = true),
        ).addRemoteInput(input).build()
        val notAsleep = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "Wasn't asleep", intent(ACTION_NOT_ASLEEP, 12, mutable = false),
        ).build()
        val later = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_recent_history, "Later", intent(ACTION_LATER, 14, mutable = false),
        ).build()
        val n = NotificationCompat.Builder(context, NotificationChannels.SLEEP)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Were you asleep?")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(reply)
            .addAction(notAsleep)
            .addAction(later)
            .setDeleteIntent(intent(ACTION_DISMISSED, 13, mutable = false))
            // Silent: it may arrive at a 4am pickup, and must not wake him further.
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        return true
    }

    fun cancel(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)

    fun record(context: Context, type: String, ts: Instant, stretches: List<Stretch>, extra: Map<String, Any?>) {
        val minutesDown = stretches.sumOf { Duration.between(it.from, it.until).toMinutes() }
        val event = RawEvent(
            collector = SleepCollector.ID, type = type, ts = ts,
            value = minutesDown.toDouble(), unit = "minutes_down", source = "sleep_ask",
            payload = mapOf(
                "phone_down_from" to stretches.firstOrNull()?.from?.toString(),
                "phone_down_until" to stretches.lastOrNull()?.until?.toString(),
                "stretches" to stretches.joinToString(",") { "${it.from}/${it.until}" },
            ) + extra,
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

/** His answer, "wasn't asleep", Later, or a swipe-away. Never opens the app. */
class SleepAskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val now = Instant.now()
        val night = SleepAsk.readNight(app)
        if (night.stretches.isEmpty()) {
            SleepAsk.cancel(app)
            return
        }
        when (intent.action) {
            SleepAsk.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(SleepAsk.KEY_REPLY)?.toString()?.trim()
                if (text.isNullOrEmpty()) return
                SleepAsk.record(app, "sleep.label", now, night.stretches, mapOf("kind" to "slept", "answer" to text))
                SleepAsk.writeNight(app, Night.onAnsweredSlept(night, now))
                SleepAsk.cancel(app)
            }
            SleepAsk.ACTION_NOT_ASLEEP -> {
                SleepAsk.record(app, "sleep.label", now, night.stretches, mapOf("kind" to "not_asleep"))
                SleepAsk.writeNight(app, Night.onAnsweredNotAsleep(night))
                SleepAsk.cancel(app)
            }
            SleepAsk.ACTION_LATER, SleepAsk.ACTION_DISMISSED -> {
                val how = if (intent.action == SleepAsk.ACTION_LATER) "later" else "swiped"
                SleepAsk.record(app, "sleep.ask_hidden", now, night.stretches, mapOf("how" to how))
                SleepAsk.writeNight(app, Night.onHidden(night, now))
                SleepAsk.cancel(app)
            }
        }
    }
}
