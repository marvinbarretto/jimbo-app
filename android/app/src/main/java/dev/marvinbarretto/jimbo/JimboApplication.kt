package dev.marvinbarretto.jimbo

import android.Manifest
import dev.marvinbarretto.jimbo.place.PlaceGeofenceManager
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class JimboApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // WorkManager is idempotent under UPDATE policy — safe to call on every launch.
        SyncScheduler.schedulePeriodic(this)
        DeviceBroadcasts.register(this)
        // Sleep night question: off since 13 Nov 2026. Since 15 Nov the band, unlocks
        // and Mac activity place onset and wake without asking him, so no question
        // runs anywhere (SM-7959). Kept in case a one-tap confirmation is ever needed.
        // dev.marvinbarretto.jimbo.sleepask.SleepAsk.register(this)
        // The food nudge is removed (27 Nov 2026, APP-8366). WorkManager keeps its hourly
        // job across app updates, so cancel the one an older build saved.
        androidx.work.WorkManager.getInstance(this).cancelUniqueWork("jimbo_food_nudge")
        dev.marvinbarretto.jimbo.proposals.ProposalsWorker.schedule(this)

        // Play Services activity recognition + location registrations don't survive
        // process death. Re-register on every cold start if permissions are already granted.
        // The PendingIntent key is stable so Play Services deduplicates.
        if (hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            ActivityRecognitionManager.register(this)
            // The Sleep API is retired (18 Nov 2026): drop any subscription an older build left.
            SleepManager.unregister(this)
        }
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            JimboLocationManager.register(this)
            PlaceGeofenceManager.register(this)
            // The nearest saved places too, when a pick is due: launch doesn't queue a sync once permissions are
            // granted, and the periodic one can be 45 minutes off. Network, so off the main thread.
            Thread {
                try { dev.marvinbarretto.jimbo.place.RemoteFences.refreshIfDue(this) }
                catch (e: Exception) { android.util.Log.e("JimboSync", "Remote fences refresh on launch failed", e) }
            }.start()
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
