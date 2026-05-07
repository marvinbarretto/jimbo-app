package dev.marvinbarretto.steps

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.marvinbarretto.steps.telemetry.SyncConstraintsRepository
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val PERIODIC_WORK_NAME = "steps_sync"
    private const val MANUAL_WORK_NAME = "steps_sync_manual"

    fun schedulePeriodic(context: Context) {
        val syncConstraints = runBlocking { SyncConstraintsRepository(context).get() }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            30, TimeUnit.MINUTES,
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints(syncConstraints.wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun enqueueManualSync(context: Context) {
        val syncConstraints = runBlocking { SyncConstraintsRepository(context).get() }
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(syncConstraints.wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun periodicWorkName(): String = PERIODIC_WORK_NAME

    fun manualWorkName(): String = MANUAL_WORK_NAME

    private fun constraints(wifiOnly: Boolean): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
}
