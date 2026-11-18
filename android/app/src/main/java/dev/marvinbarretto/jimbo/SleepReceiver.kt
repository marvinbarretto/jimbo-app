package dev.marvinbarretto.jimbo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "JimboSync"

/**
 * Sleep API deliveries, retired 18 Nov 2026 (see SleepManager). Kept so a delivery
 * already queued by Play Services before the subscription is removed lands somewhere
 * and is dropped, not recorded.
 */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Sleep API delivery dropped: the Sleep API is retired")
    }
}
