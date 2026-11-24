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
import dev.marvinbarretto.jimbo.SyncScheduler
import dev.marvinbarretto.jimbo.data.StepsDatabase
import dev.marvinbarretto.jimbo.telemetry.PlaceCollector
import dev.marvinbarretto.jimbo.telemetry.RawEvent
import dev.marvinbarretto.jimbo.telemetry.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

private const val TAG = "JimboSync"

/**
 * Turns geofence transitions into three things: a `place` telemetry event for
 * every enter, dwell and exit (the place's id and name, never coordinates); the
 * current named place (home, gym) for the /m shell; and each fence's actions for
 * that transition (PlaceAction: a notification, a quip, the gym's one-tap
 * "start a session?"). Never starts a session itself.
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e(TAG, "Geofence error ${event.errorCode}")
            return
        }
        val now = System.currentTimeMillis()
        val transition = event.geofenceTransition
        val configured = PlaceGeofence.active(context).associateBy { it.requestId }
        val triggered = event.triggeringGeofences.orEmpty().mapNotNull { configured[it.requestId] }
        // One enter, dwell and exit per visit: Android repeats them on every re-registration (PlaceVisits).
        val fences = triggered.filter { fence ->
            val decision = PlaceVisits.decide(transition, PlaceVisits.load(context, fence.id), now)
            PlaceVisits.save(context, fence.id, decision.next)
            if (!decision.record) Log.d(TAG, "Dropped repeat transition $transition for ${fence.id}")
            decision.record
        }
        if (fences.isEmpty()) return

        // Everything above is recorded (with distance_m, accuracy_m) for jimbo-api to judge. Acting on it needs the
        // reading inside the circle too (docs/checks/2026-11-24-fence-overlap-false-visits.md, 24 Nov 2026). An ENTER
        // read outside is held, and its ARRIVE actions run at a later DWELL that reads inside. Exit is never gated.
        val reading = event.triggeringLocation
        val toRun = mutableListOf<Pair<PlaceGeofence, PlaceAction.When>>()
        for (fence in fences) {
            val inside = FenceReading.inside(reading?.latitude, reading?.longitude, fence)
            if (!inside && reading != null) {
                Log.d(TAG, "Fired from outside: ${fence.id} ${FenceReading.distanceFromCentre(reading.latitude, reading.longitude, fence).toInt()} m > ${fence.radiusMetres.toInt()} m")
            }
            val gate = FenceReading.gate(transition, inside, PlaceVisits.arriveHeld(context, fence.id))
            PlaceVisits.setArriveHeld(context, fence.id, gate.heldNext)
            if (gate.arrive) toRun += fence to PlaceAction.When.ARRIVE
            if (gate.stay) toRun += fence to PlaceAction.When.STAY
            if (gate.leave) toRun += fence to PlaceAction.When.LEAVE
        }
        val arrived = toRun.filter { it.second == PlaceAction.When.ARRIVE }.map { it.first }

        if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            if (fences.any { it.place != Place.OTHER && it.place == PlaceStateStore.current(context).first }) {
                PlaceStateStore.set(context, Place.OTHER, now)
            }
        } else {
            arrived.firstOrNull { it.place != Place.OTHER }?.let { PlaceStateStore.set(context, it.place, now) }
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                recordTransitions(context, fences, transition, Instant.ofEpochMilli(now), event.triggeringLocation)
                // Every arrival, stay and leaving goes up now, not at the next 30-minute sync: Jimbo knows where he is
                // while he is there (22 Nov 2026: an exit at 14:05 reached Jimbo at 15:32).
                SyncScheduler.enqueueManualSync(context)
                runActions(context, toRun, now)
            } catch (e: Exception) {
                Log.e(TAG, "Geofence handling failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** Each fence's actions for the moments the gate let through (arrive, stay, leave), as set on /places or carried over from the settings file. */
    private fun runActions(context: Context, toRun: List<Pair<PlaceGeofence, PlaceAction.When>>, now: Long) {
        for ((fence, whenever) in toRun) for (a in fence.actions.filter { it.whenever == whenever }) {
            val title = a.title ?: "At ${fence.displayName}"
            when (a.action) {
                PlaceAction.Do.GYM_OFFER -> offerSessionStart(context)
                PlaceAction.Do.QUIP -> PlaceQuipWorker.enqueue(context, fence.id, a.list ?: continue, title, now)
                PlaceAction.Do.NOTIFY -> {
                    val since = java.time.LocalTime.now().minusMinutes(if (whenever == PlaceAction.When.STAY) 3 else 0).withSecond(0).withNano(0)
                    val text = a.text ?: when (whenever) {
                        PlaceAction.When.LEAVE -> "Left at about ${java.time.LocalTime.now().withSecond(0).withNano(0)}."
                        else -> "Here since about $since. Jimbo logged the visit."
                    }
                    PlaceNotifier.show(context, fence.id, title, text, now)
                }
            }
        }
    }

    private suspend fun recordTransitions(
        context: Context, fences: List<PlaceGeofence>, transition: Int, at: Instant, reading: android.location.Location?,
    ) {
        val type = when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "place.enter"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "place.exit"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "place.dwell"
            else -> return
        }
        val database = StepsDatabase.getInstance(context)
        // An unseeded setting row (before the first sync) means the collector's default: on.
        if (database.collectorSettingDao().isEnabled(PlaceCollector.ID) == false) return
        val events = fences.map { fence ->
            RawEvent(
                collector = PlaceCollector.ID,
                type = type,
                ts = at,
                payload = buildMap {
                    put("place", fence.id)
                    put("radius_m", fence.radiusMetres.toDouble())
                    fence.name?.let { put("name", it) }
                    // How precise the reading that fired it was, and how far it sat from the circle's centre: never the
                    // coordinates themselves. It explains a false visit or a long pass (jimbo-api visits, 22 Nov 2026).
                    reading?.let { r ->
                        if (r.hasAccuracy()) put("accuracy_m", r.accuracy.toDouble())
                        put("distance_m", FenceReading.distanceFromCentre(r.latitude, r.longitude, fence).toDouble())
                    }
                },
            )
        }
        database.eventDao().insertAll(events.map { it.toEntity() })
        Log.d(TAG, "Recorded $type for ${fences.map { it.id }}")
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
