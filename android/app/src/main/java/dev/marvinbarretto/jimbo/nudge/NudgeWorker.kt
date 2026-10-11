package dev.marvinbarretto.jimbo.nudge

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import dev.marvinbarretto.jimbo.JimboClient
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Hourly check: inside a meal window, nudge if nothing has been logged since the
 * window opened and fewer than two nudges went out today. The Android "Nudges"
 * channel toggle is the off switch.
 */
class NudgeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val zone = ZoneId.of("Europe/London")
        val now = java.time.ZonedDateTime.now(zone)
        val windowStart = MEAL_WINDOWS.firstOrNull { now.toLocalTime() >= it.first && now.toLocalTime() < it.second }
            ?.first ?: return Result.success()
        val windowStartMillis = now.toLocalDate().atTime(windowStart).atZone(zone).toInstant().toEpochMilli()

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now(zone).toString()
        val sent = if (prefs.getString(KEY_DAY, null) == today) prefs.getInt(KEY_COUNT, 0) else 0
        if (sent >= NudgeGate.MAX_PER_DAY) return Result.success()

        // Unknown is not "nothing logged": on any API trouble stay quiet and retry next hour.
        val lastLog = try {
            val (code, body) = JimboClient.getFoodLog("days=2&limit=1")
            if (code != 200) return Result.success()
            Regex("\"logged_at\"\\s*:\\s*\"([^\"]+)\"").find(body)
                ?.let { Instant.parse(it.groupValues[1]).toEpochMilli() }
        } catch (e: Exception) {
            return Result.success()
        }

        if (!NudgeGate.shouldNudge(sent, lastLog, windowStartMillis)) return Result.success()
        // One nudge per window.
        if (prefs.getLong(KEY_LAST_WINDOW, 0L) == windowStartMillis) return Result.success()

        NudgeNotifier.postNudge(applicationContext)
        prefs.edit().putString(KEY_DAY, today).putInt(KEY_COUNT, sent + 1)
            .putLong(KEY_LAST_WINDOW, windowStartMillis).apply()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "jimbo_food_nudge"
        private const val PREFS = "jimbo_nudges"
        private const val KEY_DAY = "day"
        private const val KEY_COUNT = "count"
        private const val KEY_LAST_WINDOW = "last_window"

        /** Lunch and evening meal windows, London time. */
        private val MEAL_WINDOWS = listOf(
            LocalTime.of(13, 0) to LocalTime.of(15, 0),
            LocalTime.of(19, 0) to LocalTime.of(21, 0),
        )

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NudgeWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }
}
