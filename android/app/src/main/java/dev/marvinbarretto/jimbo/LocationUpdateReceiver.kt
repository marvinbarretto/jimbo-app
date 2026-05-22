package dev.marvinbarretto.jimbo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.LocationResult
import dev.marvinbarretto.jimbo.data.StepsDatabase
import dev.marvinbarretto.jimbo.telemetry.RawEvent
import dev.marvinbarretto.jimbo.telemetry.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

private const val TAG = "JimboSync"
private const val COLLECTOR_ID = "location"

class LocationUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!LocationResult.hasResult(intent)) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                process(context, intent)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun process(context: Context, intent: Intent) {
        val database = StepsDatabase.getInstance(context)
        val enabled = database.collectorSettingDao().isEnabled(COLLECTOR_ID) ?: false
        if (!enabled) return

        val location = LocationResult.extractResult(intent)?.lastLocation ?: return

        val event = RawEvent(
            collector = COLLECTOR_ID,
            type = "location.update",
            ts = Instant.ofEpochMilli(location.time),
            payload = mapOf(
                "lat" to location.latitude,
                "lng" to location.longitude,
                "accuracy_m" to location.accuracy.toDouble(),
                // speed is -1 if not available (e.g. GPS fix without motion data)
                "speed_mps" to location.speed.takeIf { it >= 0f }?.toDouble()
            )
        )

        database.eventDao().insertAll(listOf(event.toEntity()))
        Log.d(TAG, "Recorded location.update (accuracy=${location.accuracy}m)")
    }
}
