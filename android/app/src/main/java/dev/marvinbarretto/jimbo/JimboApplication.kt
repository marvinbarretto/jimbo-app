package dev.marvinbarretto.jimbo

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class JimboApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // WorkManager is idempotent under UPDATE policy — safe to call on every launch.
        SyncScheduler.schedulePeriodic(this)

        // Play Services activity recognition + location registrations don't survive
        // process death. Re-register on every cold start if permissions are already granted.
        // The PendingIntent key is stable so Play Services deduplicates.
        if (hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            ActivityRecognitionManager.register(this)
        }
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            JimboLocationManager.register(this)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
