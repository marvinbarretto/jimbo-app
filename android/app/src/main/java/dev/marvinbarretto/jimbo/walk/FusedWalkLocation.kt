package dev.marvinbarretto.jimbo.walk

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.time.Instant

/**
 * Fused location at high accuracy every few seconds, for the life of a walk.
 * The geofence trial (place/) rides on transitions alone; 30 m needs a stream.
 */
class FusedWalkLocation(context: Context) : WalkLocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null

    // WalkSessionService checks ACCESS_FINE_LOCATION before it starts a session.
    @SuppressLint("MissingPermission")
    override fun start(onFix: (WalkFix) -> Unit) {
        stop()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { onFix(it.toFix()) }
            }
        }
        callback = cb
        client.requestLocationUpdates(request, cb, Looper.getMainLooper())
    }

    override fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }

    private fun Location.toFix() = WalkFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMetres = if (hasAccuracy()) accuracy else null,
        at = Instant.ofEpochMilli(time),
    )

    companion object {
        const val INTERVAL_MS = 3_000L
        const val MIN_INTERVAL_MS = 2_000L
    }
}
