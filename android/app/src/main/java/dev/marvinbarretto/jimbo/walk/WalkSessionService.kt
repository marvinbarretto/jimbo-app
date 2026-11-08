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
import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.marvinbarretto.jimbo.BuildConfig
import dev.marvinbarretto.jimbo.NotificationChannels
import dev.marvinbarretto.jimbo.NotificationCollectorService
import dev.marvinbarretto.jimbo.telemetry.WalkCollector
import java.time.Instant

private const val TAG = "JimboWalk"

/**
 * Walk mode: a foreground service of type location that runs one WalkSession
 * from "Start walk" to "Stop walk". It streams fused location, and it is also
 * what makes the audio focus request legal (Android 15 grants focus only to
 * the top app or a foreground service). Started and stopped from a debug
 * notification (WalkNotifications); nothing else starts it. The running
 * notification also carries the clip controls and the More offer (walk 2).
 */
class WalkSessionService : Service() {
    private var session: WalkSession? = null
    private var player: AndroidWalkClipPlayer? = null
    private var audio: WalkAudio? = null
    private var choice: WalkChoice? = null
    private var spotCount = 0
    private var lastSpot: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val schedule = WalkSchedule { delayMs, action ->
        val runnable = Runnable(action)
        main.postDelayed(runnable, delayMs)
        val cancel: () -> Unit = { main.removeCallbacks(runnable) }
        cancel
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when {
            action == ACTION_START -> startWalk()
            // A control for a walk that is gone (the process died): tidy up.
            action != null && action in CONTROLS && session == null -> stopWalk()
            action == ACTION_PAUSE -> control { it.pause() }
            action == ACTION_RESUME -> control { it.resume() }
            action == ACTION_BACK -> control { it.back() }
            action == ACTION_SKIP -> control { it.skip() }
            action == ACTION_MORE -> { choice?.take(); refresh() }
            action == ACTION_DECLINE -> { choice?.decline(); refresh() }
            else -> stopWalk()
        }
        return START_NOT_STICKY
    }

    private fun startWalk() {
        if (session?.isRunning == true) return
        val spots = WalkSpot.configured()
        spotCount = spots.size
        lastSpot = null
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
        val sink = RoomWalkEventSink(this)
        val media = WalkMediaWatch(
            probe = ::mediaSnapshot,
            schedule = schedule,
            walkId = { session?.currentWalkId },
            record = { walk, point, clip, snapshot ->
                sink.record(WalkCollector.media(walk, point, clip, snapshot, Instant.now()))
            },
        )
        val walkAudio = WalkAudio(AndroidWalkAudioFocus(this), clipPlayer, schedule = schedule, step = { step ->
            Log.d(TAG, "Audio ${step.phase} ${step.clip ?: ""} ${step.kind ?: ""}")
            sink.record(WalkCollector.audio(session?.currentWalkId, step.phase, step.clip, Instant.now(), step.kind))
            media.onStep(step.phase, step.clip)
            choice?.onStep(step.phase, step.clip)
            refresh()
        })
        audio = walkAudio
        choice = WalkChoice(
            hasClip = clipPlayer::hasClip,
            play = walkAudio::play,
            schedule = schedule,
            record = { what, clip, reason ->
                Log.d(TAG, "More $what for $clip ${reason ?: ""}")
                sink.record(WalkCollector.choice(session?.currentWalkId, what, clip, reason, Instant.now()))
            },
            changed = ::refresh,
        )
        val walk = WalkSession(
            spots = spots,
            location = FusedWalkLocation(this),
            playback = walkAudio,
            events = sink,
            diagnostics = sink,
            battery = ::batteryPercent,
            onHit = { hit ->
                Log.d(TAG, "Reached ${hit.spot.id} at ${"%.1f".format(hit.distanceMetres)} m")
                lastSpot = hit.spot.id
                refresh()
            },
        )
        session = walk
        walk.start()
        Log.d(TAG, "Walk started with ${spots.size} spots")
    }

    /** A notification control on the playing clip, recorded as walk.control. */
    private fun control(act: (WalkAudio) -> WalkControl) {
        val walkAudio = audio ?: return
        val result = act(walkAudio)
        Log.d(TAG, "Control $result")
        RoomWalkEventSink(this).record(WalkCollector.control(session?.currentWalkId, result, Instant.now()))
        refresh()
    }

    /** Redraws the running notification for what is happening now: a clip, an offer, or neither. */
    private fun refresh() {
        val running = session ?: return
        val walkAudio = audio
        val state = WalkNotifications.State(
            playing = walkAudio?.nowPlaying,
            paused = walkAudio?.isPaused == true,
            offer = choice?.offered,
        )
        getSystemService(NotificationManager::class.java).notify(
            WalkNotifications.RUNNING_ID,
            WalkNotifications.running(this, spotCount, running.triggered, lastSpot, state),
        )
    }

    /** Every active media session's package and play state; reading them needs notification access. */
    private fun mediaSnapshot(): WalkMediaSnapshot = try {
        val manager = getSystemService(MediaSessionManager::class.java)
        if (manager == null) {
            WalkMediaSnapshot(emptyList(), error = "no_media_session_manager")
        } else {
            val listener = ComponentName(this, NotificationCollectorService::class.java)
            WalkMediaSnapshot(
                manager.getActiveSessions(listener).map {
                    WalkMediaSession(it.packageName, WalkMediaWatch.playbackState(it.playbackState?.state))
                },
            )
        }
    } catch (e: SecurityException) {
        WalkMediaSnapshot(emptyList(), error = "no_notification_access")
    } catch (e: Exception) {
        WalkMediaSnapshot(emptyList(), error = "failed: ${e.javaClass.simpleName}")
    }

    private fun stopWalk() {
        session?.let { Log.d(TAG, "Walk stopped, ${it.triggered} spots reached") }
        choice?.end()
        choice = null
        session?.stop()
        session = null
        audio = null
        player?.shutdown()
        player = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        WalkNotifications.showIdle(this)
    }

    override fun onDestroy() {
        choice?.end()
        choice = null
        session?.stop()
        session = null
        audio = null
        player?.shutdown()
        player = null
        super.onDestroy()
    }

    private fun batteryPercent(): Int? =
        getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "dev.marvinbarretto.jimbo.walk.START"
        const val ACTION_STOP = "dev.marvinbarretto.jimbo.walk.STOP"
        const val ACTION_PAUSE = "dev.marvinbarretto.jimbo.walk.PAUSE"
        const val ACTION_RESUME = "dev.marvinbarretto.jimbo.walk.RESUME"
        const val ACTION_BACK = "dev.marvinbarretto.jimbo.walk.BACK"
        const val ACTION_SKIP = "dev.marvinbarretto.jimbo.walk.SKIP"
        const val ACTION_MORE = "dev.marvinbarretto.jimbo.walk.MORE"
        const val ACTION_DECLINE = "dev.marvinbarretto.jimbo.walk.DECLINE"
        private val CONTROLS = setOf(ACTION_PAUSE, ACTION_RESUME, ACTION_BACK, ACTION_SKIP, ACTION_MORE, ACTION_DECLINE)
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

    /** What the running notification shows besides the tally: a clip playing or paused, or a More offer. */
    data class State(val playing: String? = null, val paused: Boolean = false, val offer: String? = null)

    /**
     * The running notification. Android shows three actions, so: during a
     * clip, Pause or Resume, Back 10 s and Skip; during a More offer, More,
     * Skip and Stop walk; otherwise Stop walk.
     */
    fun running(context: Context, spotCount: Int, reached: Int, last: String?, state: State = State()): Notification {
        NotificationChannels.ensureCreated(context)
        val tally = "$reached of $spotCount spots reached" + (last?.let { " · last: $it" } ?: "")
        val builder = NotificationCompat.Builder(context, NotificationChannels.WALK)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when {
            state.playing != null -> builder
                .setContentTitle(if (state.paused) "Paused: ${state.playing}" else "Playing: ${state.playing}")
                .setContentText(tally)
                .addAction(0, if (state.paused) "Resume" else "Pause",
                    action(context, if (state.paused) WalkSessionService.ACTION_RESUME else WalkSessionService.ACTION_PAUSE, 2))
                .addAction(0, "Back 10 s", action(context, WalkSessionService.ACTION_BACK, 3))
                .addAction(0, "Skip", action(context, WalkSessionService.ACTION_SKIP, 4))
            state.offer != null -> builder
                .setContentTitle("More on ${state.offer}?")
                .setContentText("The longer telling, if you want it (60 s). $tally")
                .addAction(0, "More", action(context, WalkSessionService.ACTION_MORE, 5))
                .addAction(0, "Skip", action(context, WalkSessionService.ACTION_DECLINE, 6))
                .addAction(0, "Stop walk", action(context, WalkSessionService.ACTION_STOP, 0))
            else -> builder
                .setContentTitle("Walk on")
                .setContentText(tally)
                .addAction(0, "Stop walk", action(context, WalkSessionService.ACTION_STOP, 0))
        }
        return builder.build()
    }

    /** One PendingIntent per action, each with its own request code so none replaces another. */
    private fun action(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getService(
        context, RUNNING_ID + 0x100 * code,
        Intent(context, WalkSessionService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
