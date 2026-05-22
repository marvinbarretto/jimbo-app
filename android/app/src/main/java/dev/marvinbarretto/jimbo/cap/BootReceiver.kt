package dev.marvinbarretto.jimbo.cap

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "JimboSync"

// Re-arms broadcast-driven collectors after device reboot or app upgrade.
// Play Services does not persist ActivityRecognition or FusedLocation registrations
// across reboots, and app upgrades clear the previous app's PendingIntents — without
// this receiver the phone goes silent on those collectors until the user opens the
// app. WorkManager's periodic work is persisted, but re-enqueuing with UPDATE policy
// is a cheap safety net.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        Log.d(TAG, "BootReceiver received $action — re-arming collectors")

        SyncScheduler.schedulePeriodic(context)

        if (hasPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)) {
            ActivityRecognitionManager.register(context)
        }

        if (hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
            hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            JimboLocationManager.register(context)
        }
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
