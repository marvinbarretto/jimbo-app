package dev.marvinbarretto.jimbo.place

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

private const val TAG = "JimboSync"

/** How long inside a fence counts as being there rather than passing by. */
private const val LOITERING_MS = 3 * 60 * 1000

/**
 * Registers the configured place geofences (PlaceGeofence.configured). Transitions only — no GPS stream. Play
 * Services drops geofences on reboot and package replacement, so callers
 * re-arm from the same places as JimboLocationManager.register.
 */
object PlaceGeofenceManager {

    @SuppressLint("MissingPermission")
    fun register(context: Context) {
        val fences = PlaceGeofence.configured()
        if (fences.isEmpty()) return
        if (!hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            !hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) return

        val request = GeofencingRequest.Builder()
            // No INITIAL_TRIGGER_ENTER: being at the gym when the app is
            // installed or re-armed is not an arrival.
            .setInitialTrigger(0)
            .addGeofences(fences.map { it.toGeofence() })
            .build()
        LocationServices.getGeofencingClient(context)
            .addGeofences(request, pendingIntent(context))
            .addOnSuccessListener { Log.d(TAG, "Geofences registered: ${fences.map { it.requestId }}") }
            .addOnFailureListener { Log.e(TAG, "Failed to register geofences", it) }
    }

    private fun PlaceGeofence.toGeofence(): Geofence =
        Geofence.Builder()
            .setRequestId(requestId)
            .setCircularRegion(latitude, longitude, radiusMetres)
            // DWELL fires once you have stayed LOITERING_MS inside: the trigger for a
            // place's notification, so walking past one does not count as a visit.
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT or
                    Geofence.GEOFENCE_TRANSITION_DWELL
            )
            .setLoiteringDelay(LOITERING_MS)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, GeofenceReceiver::class.java),
            // Play Services fills in the transition extras, so it must be mutable.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    private fun hasPermission(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
