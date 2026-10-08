package dev.marvinbarretto.jimbo.place

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dev.marvinbarretto.jimbo.MainActivity
import dev.marvinbarretto.jimbo.NotificationChannels
import dev.marvinbarretto.jimbo.NotificationTriggerReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

private const val TAG = "JimboSync"

/**
 * Turns geofence transitions into the current named place and, on gym entry,
 * the one-tap "start a session?" notification. Never starts a session itself.
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e(TAG, "Geofence error ${event.errorCode}")
            return
        }
        val now = System.currentTimeMillis()
        val fences = event.triggeringGeofences.orEmpty()

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_EXIT ->
                if (fences.any { PlaceGeofence.placeForRequestId(it.requestId) == PlaceStateStore.current(context).first }) {
                    PlaceStateStore.set(context, Place.OTHER, now)
                }
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                val place = fences.firstNotNullOfOrNull { PlaceGeofence.placeForRequestId(it.requestId) } ?: return
                PlaceStateStore.set(context, place, now)
                if (place == Place.GYM) {
                    val pending = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            offerSessionStart(context)
                        } catch (e: Exception) {
                            Log.e(TAG, "Gym arrival check failed", e)
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
        }
    }

    private fun offerSessionStart(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val state = GymSessionState.fetch() ?: return
        if (!GymArrivalPolicy.shouldOfferStart(state.hasActive, state.lastEndedAt, Instant.now())) return

        NotificationChannels.ensureCreated(context)
        val tap = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(NotificationTriggerReceiver.EXTRA_TAB, "train")
                putExtra(EXTRA_START_SESSION, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.GYM)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("At the gym")
            .setContentText("Tap to start a session")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        /** Set on the tap intent; MainActivity starts a session before opening /m/train. */
        const val EXTRA_START_SESSION = "jimbo_start_gym_session"
        private const val NOTIFICATION_ID = 0x6a796d
    }
}
