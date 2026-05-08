package dev.marvinbarretto.jimbo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

private const val TAG = "JimboSync"

// Cadence cross-references activity recognition state — faster when moving so
// walks and runs are tracked with useful resolution, slower when still to save
// battery. ActivityTransitionReceiver calls updateCadence() on state change.
private const val INTERVAL_MOVING_MS = 5 * 60 * 1_000L   // 5 min
private const val INTERVAL_STILL_MS  = 30 * 60 * 1_000L  // 30 min

object JimboLocationManager {

    fun register(context: Context, isMoving: Boolean = false) {
        val request = buildRequest(isMoving)
        LocationServices.getFusedLocationProviderClient(context)
            .requestLocationUpdates(request, pendingIntent(context))
            .addOnSuccessListener { Log.d(TAG, "Location updates registered (moving=$isMoving)") }
            .addOnFailureListener { Log.e(TAG, "Failed to register location updates", it) }
    }

    fun updateCadence(context: Context, isMoving: Boolean) {
        // Re-registering with FLAG_UPDATE_CURRENT replaces the existing request —
        // no need to remove first, FusedLocationProviderClient handles the swap.
        register(context, isMoving)
    }

    fun unregister(context: Context) {
        LocationServices.getFusedLocationProviderClient(context)
            .removeLocationUpdates(pendingIntent(context))
            .addOnSuccessListener { Log.d(TAG, "Location updates unregistered") }
            .addOnFailureListener { Log.e(TAG, "Failed to unregister location updates", it) }
    }

    private fun buildRequest(isMoving: Boolean): LocationRequest =
        LocationRequest.Builder(
            // Use HIGH_ACCURACY when moving (walking/running needs better resolution),
            // BALANCED when still (cell/wifi is sufficient and saves battery).
            if (isMoving) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            if (isMoving) INTERVAL_MOVING_MS else INTERVAL_STILL_MS
        ).build()

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, LocationUpdateReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }
}
