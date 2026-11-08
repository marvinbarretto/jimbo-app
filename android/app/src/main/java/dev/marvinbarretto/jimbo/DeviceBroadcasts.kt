package dev.marvinbarretto.jimbo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.core.content.ContextCompat
import dev.marvinbarretto.jimbo.data.StepsDatabase
import dev.marvinbarretto.jimbo.telemetry.ACTION_NETWORK_STATE_CHANGED
import dev.marvinbarretto.jimbo.telemetry.DeviceCollector
import dev.marvinbarretto.jimbo.telemetry.TimeWindow
import dev.marvinbarretto.jimbo.telemetry.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

private const val TAG = "JimboSync"

/**
 * Feeds plug/unplug and network changes to DeviceCollector as they happen.
 * Its charge_state_changed and network_state events existed from the start but
 * nothing ever delivered the broadcasts (0 of either in 14 days, Oct–Nov 2026):
 * Android 8+ drops these broadcasts for manifest receivers, so they are
 * registered at runtime here, for the life of the process (kept alive by the
 * notification listener).
 */
object DeviceBroadcasts {
    @Volatile private var registered = false

    fun register(context: Context) {
        if (registered) return
        registered = true
        val app = context.applicationContext
        val power = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                intent.action?.let { record(app, it) }
            }
        }
        ContextCompat.registerReceiver(
            app, power,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = record(app, ACTION_NETWORK_STATE_CHANGED)
                    override fun onLost(network: Network) = record(app, ACTION_NETWORK_STATE_CHANGED)
                }
            )
        }.onFailure { Log.w(TAG, "Network callback not registered", it) }
    }

    private fun record(context: Context, action: String) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val database = StepsDatabase.getInstance(context)
                if (database.collectorSettingDao().isEnabled("device") == false) return@launch
                val now = Instant.now()
                val events = DeviceCollector(context).collect(TimeWindow(now, now, action))
                if (events.isNotEmpty()) database.eventDao().insertAll(events.map { it.toEntity() })
            }.onFailure { Log.w(TAG, "Device broadcast $action not recorded", it) }
        }
    }
}
