package dev.marvinbarretto.jimbo.walk

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.marvinbarretto.jimbo.BuildConfig
import dev.marvinbarretto.jimbo.NotificationChannels

private const val TAG = "JimboWalk"

/**
 * Walk mode: a foreground service of type location that runs one WalkSession
 * from "Start walk" to "Stop walk". It streams fused location, and it is also
 * what makes the audio focus request legal (Android 15 grants focus only to
 * the top app or a foreground service). Started and stopped from a debug
 * notification (WalkNotifications); nothing else starts it.
 */
class WalkSessionService : Service() {
    private var session: WalkSession? = null
    private var player: AndroidWalkClipPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startWalk()
            else -> stopWalk()
        }
        return START_NOT_STICKY
    }

    private fun startWalk() {
        if (session?.isRunning == true) return
        val spots = WalkSpot.configured()
        // startForegroundService promised a foreground notification within seconds: give it first.
        try {
            ServiceCompat.startForeground(
                this, WalkNotifications.RUNNING_ID, WalkNotifications.running(this, spots.size, 0, null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Walk could not start in the foreground", e)
            stopSelf()
            return
        }
        if (spots.isEmpty() || !hasFineLocation()) {
            Log.w(TAG, "Walk not started: ${spots.size} spots, fine location ${hasFineLocation()}")
            stopWalk()
            return
        }
        getSystemService(NotificationManager::class.java).cancel(WalkNotifications.IDLE_ID)
        val clipPlayer = AndroidWalkClipPlayer(this)
        player = clipPlayer
        val walk = WalkSession(
            spots = spots,
            location = FusedWalkLocation(this),
            playback = WalkAudio(AndroidWalkAudioFocus(this), clipPlayer),
            events = RoomWalkEventSink(this),
            onHit = hit@{ hit ->
                Log.d(TAG, "Reached ${hit.spot.id} at ${"%.1f".format(hit.distanceMetres)} m")
                val running = session ?: return@hit
                getSystemService(NotificationManager::class.java).notify(
                    WalkNotifications.RUNNING_ID,
                    WalkNotifications.running(this, spots.size, running.triggered, hit.spot.id),
                )
            },
        )
        session = walk
        walk.start()
        Log.d(TAG, "Walk started with ${spots.size} spots")
    }

    private fun stopWalk() {
        session?.let { Log.d(TAG, "Walk stopped, ${it.triggered} spots reached") }
        session?.stop()
        session = null
        player?.shutdown()
        player = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        WalkNotifications.showIdle(this)
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        player?.shutdown()
        player = null
        super.onDestroy()
    }

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "dev.marvinbarretto.jimbo.walk.START"
        const val ACTION_STOP = "dev.marvinbarretto.jimbo.walk.STOP"
    }
}

/**
 * The debug notifications that drive walk mode: an idle one with "Start walk"
 * (posted when the app opens, in a debug build with spots set) and the
 * service's own ongoing one with "Stop walk" and a tally of spots reached.
 */
object WalkNotifications {
    const val RUNNING_ID = 0x77616c6b
    const val IDLE_ID = RUNNING_ID + 1

    fun showIdle(context: Context) {
        if (!BuildConfig.DEBUG) return
        val spots = WalkSpot.configured()
        if (spots.isEmpty()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationChannels.ensureCreated(context)
        val start = PendingIntent.getForegroundService(
            context, IDLE_ID,
            Intent(context, WalkSessionService::class.java).setAction(WalkSessionService.ACTION_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.WALK)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Walk mode")
            .setContentText("${spots.size} spots set. Each plays once within 30 m.")
            .setSilent(true)
            .addAction(0, "Start walk", start)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(IDLE_ID, notification)
    }

    fun running(context: Context, spotCount: Int, reached: Int, last: String?): Notification {
        val stop = PendingIntent.getService(
            context, RUNNING_ID,
            Intent(context, WalkSessionService::class.java).setAction(WalkSessionService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationChannels.ensureCreated(context)
        return NotificationCompat.Builder(context, NotificationChannels.WALK)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Walk on")
            .setContentText("$reached of $spotCount spots reached" + (last?.let { " · last: $it" } ?: ""))
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop walk", stop)
            .build()
    }
}
