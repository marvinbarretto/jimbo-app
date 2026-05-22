package dev.marvinbarretto.jimbo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import dev.marvinbarretto.jimbo.telemetry.hasFineLocationPermission
import dev.marvinbarretto.jimbo.telemetry.hasBackgroundLocationPermission
import dev.marvinbarretto.jimbo.data.StepsDatabase
import dev.marvinbarretto.jimbo.telemetry.RawEvent
import dev.marvinbarretto.jimbo.telemetry.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

private const val TAG = "JimboSync"
private const val COLLECTOR_ID = "activity"

class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return

        // goAsync() extends the BroadcastReceiver deadline beyond the default 10s
        // so the Room write doesn't get killed mid-flight.
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

        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val now = Instant.now()

        // We use the Transitions API rather than ActivityRecognitionResult —
        // it fires on state changes only (enter/exit) rather than on a polling
        // interval, which is significantly more battery-efficient. The trade-off
        // is no confidence score; enter/exit is binary.
        val events = result.transitionEvents.map { event ->
            RawEvent(
                collector = COLLECTOR_ID,
                type = "activity.transition",
                ts = now,
                payload = mapOf(
                    "activity_type" to activityTypeName(event.activityType),
                    "transition" to if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) "enter" else "exit"
                )
            )
        }

        if (events.isEmpty()) return
        database.eventDao().insertAll(events.map { it.toEntity() })
        Log.d(TAG, "Recorded ${events.size} activity transition(s): ${events.map { it.payload }}")

        // Update location cadence to match movement state — faster when moving,
        // slower when still. Only fires if location permissions are already granted.
        if (hasFineLocationPermission(context) && hasBackgroundLocationPermission(context)) {
            result.transitionEvents.forEach { event ->
                if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                    val isMoving = event.activityType != DetectedActivity.STILL
                    JimboLocationManager.updateCadence(context, isMoving)
                }
            }
        }
    }
}

internal fun activityTypeName(type: Int): String = when (type) {
    DetectedActivity.STILL -> "still"
    DetectedActivity.WALKING -> "walking"
    DetectedActivity.RUNNING -> "running"
    DetectedActivity.ON_BICYCLE -> "on_bicycle"
    DetectedActivity.IN_VEHICLE -> "in_vehicle"
    DetectedActivity.ON_FOOT -> "on_foot"
    DetectedActivity.TILTING -> "tilting"
    else -> "unknown"
}
