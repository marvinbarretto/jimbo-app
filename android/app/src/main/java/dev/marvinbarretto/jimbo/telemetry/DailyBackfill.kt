package dev.marvinbarretto.jimbo.telemetry

import android.content.Context
import java.time.LocalDate

/** How far back a sync will re-read Health Connect for missed days. */
internal const val MAX_BACKFILL_DAYS = 14L

/**
 * The closed logical days a sync still owes the server.
 *
 * [lastPosted] is the newest closed day whose `*_daily` rows reached the server,
 * [yesterday] the newest closed day there is. Nothing owed means an empty list,
 * so a sync with no gap posts no extra days. With no history it is the full cap.
 */
internal fun missedDays(lastPosted: LocalDate?, yesterday: LocalDate): List<LocalDate> {
    val earliest = yesterday.minusDays(MAX_BACKFILL_DAYS - 1)
    val first = if (lastPosted == null || lastPosted.plusDays(1).isBefore(earliest)) earliest
    else lastPosted.plusDays(1)
    return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(yesterday) }.toList()
}

/**
 * Remembers the newest closed day posted. The collector only *stages* a day;
 * SyncWorker commits it once the queue has drained, so a day whose rows are
 * still stuck in the queue is not counted as posted.
 */
class DailyBackfillState(context: Context) {
    private val prefs = context.getSharedPreferences("daily_backfill", Context.MODE_PRIVATE)

    fun lastPosted(): LocalDate? = prefs.getString(KEY_POSTED, null)?.let(LocalDate::parse)

    fun stage(day: LocalDate) {
        prefs.edit().putString(KEY_STAGED, day.toString()).apply()
    }

    fun commitStaged() {
        val staged = prefs.getString(KEY_STAGED, null) ?: return
        prefs.edit().putString(KEY_POSTED, staged).remove(KEY_STAGED).apply()
    }

    private companion object {
        const val KEY_POSTED = "last_posted_day"
        const val KEY_STAGED = "staged_day"
    }
}
