package dev.marvinbarretto.jimbo.widgets

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.marvinbarretto.jimbo.JimboClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Pulls today's macros and the briefing's top priority, then redraws every
 * widget. Each source fails independently: a half-failed refresh keeps the
 * other half's old value instead of blanking the widget.
 */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val previous = WidgetStore.read(applicationContext)
        val now = Instant.now()
        val today = WidgetData.logicalDay(now)

        val macros = fetch("/api/coach/food-log/daily?from=$today&to=$today") { WidgetData.parseMacros(it, today) }
        // A 404 means no fresh briefing — a real "nothing", not a failed read.
        val priority = fetch("/api/briefing/latest", allowNotFound = true) { WidgetData.parsePriority(it) }

        val reached = macros != null || priority != null
        val next = WidgetSnapshot(
            macros = if (macros != null) macros.value else previous.macros,
            priority = if (priority != null) priority.value else previous.priority,
            fetchedAtMs = if (reached) now.toEpochMilli() else previous.fetchedAtMs,
        )
        WidgetStore.write(applicationContext, next)
        MacroWidget().updateAll(applicationContext)
        PriorityWidget().updateAll(applicationContext)
        return Result.success()
    }

    private class Reading<T>(val value: T?)

    /** null = the API couldn't be read; Reading(null) = read fine, nothing there. */
    private suspend fun <T> fetch(path: String, allowNotFound: Boolean = false, parse: (String) -> T?): Reading<T>? =
        withContext(Dispatchers.IO) {
            try {
                val (code, body) = JimboClient.get(path)
                when {
                    code in 200..299 -> Reading(parse(body))
                    code == 404 && allowNotFound -> Reading(null)
                    else -> null
                }
            } catch (e: Exception) {
                Log.w(TAG, "widget fetch $path failed: ${e.message}")
                null
            }
        }

    companion object {
        private const val TAG = "JimboWidgets"
        private const val WORK_NAME = "jimbo_widget_refresh"

        /** Idempotent; every widget's onEnabled and the app launch call it. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
