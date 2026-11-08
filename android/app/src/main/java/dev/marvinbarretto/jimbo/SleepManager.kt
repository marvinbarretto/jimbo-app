package dev.marvinbarretto.jimbo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.SleepSegmentRequest

private const val TAG = "JimboSync"

/**
 * Subscribes to Google's Sleep API (Play Services): one sleep segment a day,
 * delivered after you wake, and a sleep-confidence reading about every ten
 * minutes. Rides the ACTIVITY_RECOGNITION grant the activity collector holds.
 * Its inputs are the phone's motion, ambient light and screen use, so it sees
 * the phone's night, not yours: a phone in a pocket or a YouTube-in-bed night
 * will read wrong. SleepReceiver turns deliveries into `sleep` telemetry.
 */
object SleepManager {
    fun register(context: Context) {
        ActivityRecognition.getClient(context)
            .requestSleepSegmentUpdates(pendingIntent(context), SleepSegmentRequest.getDefaultSleepSegmentRequest())
            .addOnSuccessListener { Log.d(TAG, "Sleep updates registered") }
            .addOnFailureListener { Log.e(TAG, "Failed to register sleep updates", it) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, SleepReceiver::class.java),
        // Mutable: Play Services writes the sleep events into the intent's extras.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}
