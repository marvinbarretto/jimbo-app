package dev.marvinbarretto.jimbo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognition

private const val TAG = "JimboSync"

/**
 * Google's Sleep API (Play Services), retired on 18 Nov 2026. Its inputs are the
 * phone's motion, ambient light and screen use, so it saw the phone's night, not
 * Marvin's: right on about 1 night in 6 in the first audit (7–12 Nov), a still phone
 * scored asleep while the band read 80–97 bpm (16 Nov), onset 04:56 against unlocks
 * until 05:39 (18 Nov). The band (Health Connect `sleep_session`) is the sleep source.
 *
 * A subscription survives app updates, so every cold start removes it. The
 * PendingIntent must match the one it was registered with (same receiver, request
 * code 0, same flags) for Play Services to find and drop it. Rows already stored
 * stay in jimbo-api; nothing new is recorded (SleepReceiver drops late deliveries).
 */
object SleepManager {
    fun unregister(context: Context) {
        ActivityRecognition.getClient(context)
            .removeSleepSegmentUpdates(pendingIntent(context))
            .addOnSuccessListener { Log.d(TAG, "Sleep API updates removed") }
            .addOnFailureListener { Log.e(TAG, "Failed to remove Sleep API updates", it) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, SleepReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}
