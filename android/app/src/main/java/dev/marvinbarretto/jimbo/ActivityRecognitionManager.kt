package dev.marvinbarretto.jimbo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

private const val TAG = "JimboSync"

object ActivityRecognitionManager {

    // Activities we want enter/exit transitions for.
    // Tilting is excluded — it's a device gesture, not a movement state,
    // and generates noise without useful behavioural signal.
    private val monitoredActivities = listOf(
        DetectedActivity.STILL,
        DetectedActivity.WALKING,
        DetectedActivity.RUNNING,
        DetectedActivity.ON_BICYCLE,
        DetectedActivity.IN_VEHICLE,
        DetectedActivity.ON_FOOT
    )

    fun register(context: Context) {
        val transitions = monitoredActivities.flatMap { activityType ->
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(activityType)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(activityType)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build()
            )
        }

        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(context))
            .addOnSuccessListener { Log.d(TAG, "Activity transition updates registered") }
            .addOnFailureListener { Log.e(TAG, "Failed to register activity transitions", it) }
    }

    fun unregister(context: Context) {
        // Uses the same PendingIntent instance so Play Services can match and cancel it.
        ActivityRecognition.getClient(context)
            .removeActivityTransitionUpdates(pendingIntent(context))
            .addOnSuccessListener { Log.d(TAG, "Activity transition updates unregistered") }
            .addOnFailureListener { Log.e(TAG, "Failed to unregister activity transitions", it) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ActivityTransitionReceiver::class.java)
        // FLAG_MUTABLE required on API 31+ — Play Services adds extras to the intent
        // when delivering transition results, so the PendingIntent must be mutable.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }
}
