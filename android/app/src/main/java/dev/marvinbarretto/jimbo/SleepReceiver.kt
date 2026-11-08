package dev.marvinbarretto.jimbo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.SleepClassifyEvent
import com.google.android.gms.location.SleepSegmentEvent
import dev.marvinbarretto.jimbo.data.StepsDatabase
import dev.marvinbarretto.jimbo.telemetry.RawEvent
import dev.marvinbarretto.jimbo.telemetry.SleepCollector
import dev.marvinbarretto.jimbo.telemetry.sleepStatusName
import dev.marvinbarretto.jimbo.telemetry.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

private const val TAG = "JimboSync"

/** Sleep API deliveries → `sleep.segment` and `sleep.classify` events. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val events = mutableListOf<RawEvent>()
        if (SleepSegmentEvent.hasEvents(intent)) {
            SleepSegmentEvent.extractEvents(intent).forEach { s ->
                val start = Instant.ofEpochMilli(s.startTimeMillis)
                val end = Instant.ofEpochMilli(s.endTimeMillis)
                events += RawEvent(
                    collector = SleepCollector.ID, type = "sleep.segment", ts = start, tsEnd = end,
                    value = Duration.between(start, end).toMinutes().toDouble(), unit = "minutes",
                    source = "sleep_api", payload = mapOf("status" to sleepStatusName(s.status)),
                )
            }
        }
        if (SleepClassifyEvent.hasEvents(intent)) {
            SleepClassifyEvent.extractEvents(intent).forEach { c ->
                events += RawEvent(
                    collector = SleepCollector.ID, type = "sleep.classify", ts = Instant.ofEpochMilli(c.timestampMillis),
                    value = c.confidence.toDouble(), unit = "confidence", source = "sleep_api",
                    payload = mapOf("motion" to c.motion, "light" to c.light),
                )
            }
        }
        if (events.isEmpty()) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = StepsDatabase.getInstance(context)
                if (database.collectorSettingDao().isEnabled(SleepCollector.ID) != false) {
                    database.eventDao().insertAll(events.map { it.toEntity() })
                    Log.d(TAG, "Recorded ${events.size} sleep event(s)")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
