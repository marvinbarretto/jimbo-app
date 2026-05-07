package dev.marvinbarretto.steps.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

private const val TAG = "DeviceCollector"
private const val PREFS = "device_collector"
private const val BATTERY_PERIOD_MINUTES = 30L
private const val BROADCAST_DEBOUNCE_SECONDS = 30L

class DeviceCollector(
    private val context: Context
) : Collector {
    override val id: String = "device"
    override val defaultEnabled: Boolean = true
    override val cadence: CollectorCadence = CollectorCadence.OnBroadcast(
        actions = listOf(
            Intent.ACTION_BATTERY_CHANGED,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            ConnectivityManager.CONNECTIVITY_ACTION
        )
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun collect(window: TimeWindow): List<RawEvent> {
        val now = window.end
        val action = window.triggerAction
        val events = mutableListOf<RawEvent>()

        currentBatterySnapshot(context, now)?.let { snapshot ->
            if (shouldEmitBattery(snapshot, action)) {
                events += RawEvent(
                    collector = id,
                    type = "battery_level",
                    ts = now,
                    value = snapshot.levelPercent,
                    unit = "percent",
                    payload = mapOf(
                        "is_charging" to snapshot.isCharging,
                        "charger_type" to snapshot.chargerType
                    )
                )
                persistBatterySnapshot(snapshot, now)
            }

            if (action == Intent.ACTION_POWER_CONNECTED || action == Intent.ACTION_POWER_DISCONNECTED) {
                val pluggedIn = snapshot.isCharging
                val lastPluggedIn = prefs.getBoolean("last_power_connected", pluggedIn)
                if (pluggedIn != lastPluggedIn || !prefs.contains("last_power_connected")) {
                    events += RawEvent(
                        collector = id,
                        type = "charge_state_changed",
                        ts = now,
                        payload = mapOf("plugged_in" to pluggedIn)
                    )
                }
                prefs.edit().putBoolean("last_power_connected", pluggedIn).apply()
            }
        }

        if (action == ConnectivityManager.CONNECTIVITY_ACTION) {
            currentNetworkSnapshot(context)?.let { snapshot ->
                if (shouldEmitNetwork(now, snapshot)) {
                    events += RawEvent(
                        collector = id,
                        type = "network_state",
                        ts = now,
                        payload = mapOf(
                            "kind" to snapshot.kind,
                            "ssid_hash" to snapshot.ssidHash
                        )
                    )
                    persistNetworkSnapshot(now, snapshot)
                }
            }
        }

        Log.d(TAG, "Collected ${events.size} device events for action=${action ?: "periodic"}")
        return events
    }

    private fun shouldEmitBattery(snapshot: BatterySnapshot, action: String?): Boolean {
        val lastAt = prefs.getLong("last_battery_emitted_at", 0L)
        val batteryAction = action == Intent.ACTION_BATTERY_CHANGED
        val minDelay = if (batteryAction) Duration.ofSeconds(BROADCAST_DEBOUNCE_SECONDS) else Duration.ofMinutes(BATTERY_PERIOD_MINUTES)
        val enoughTimeElapsed = lastAt == 0L || snapshot.capturedAt.toEpochMilli() - lastAt >= minDelay.toMillis()

        if (batteryAction) {
            return enoughTimeElapsed
        }

        return enoughTimeElapsed
    }

    private fun shouldEmitNetwork(now: Instant, snapshot: NetworkSnapshot): Boolean {
        val lastAt = prefs.getLong("last_network_emitted_at", 0L)
        val lastKind = prefs.getString("last_network_kind", null)
        val lastSsidHash = prefs.getString("last_network_ssid_hash", null)
        val enoughTimeElapsed = lastAt == 0L ||
            now.toEpochMilli() - lastAt >= Duration.ofSeconds(BROADCAST_DEBOUNCE_SECONDS).toMillis()
        val stateChanged = snapshot.kind != lastKind || snapshot.ssidHash != lastSsidHash
        return enoughTimeElapsed && stateChanged
    }

    private fun persistBatterySnapshot(snapshot: BatterySnapshot, now: Instant) {
        prefs.edit()
            .putLong("last_battery_emitted_at", now.toEpochMilli())
            .putFloat("last_battery_level", snapshot.levelPercent.toFloat())
            .putBoolean("last_battery_is_charging", snapshot.isCharging)
            .putString("last_battery_charger_type", snapshot.chargerType)
            .apply()
    }

    private fun persistNetworkSnapshot(now: Instant, snapshot: NetworkSnapshot) {
        prefs.edit()
            .putLong("last_network_emitted_at", now.toEpochMilli())
            .putString("last_network_kind", snapshot.kind)
            .putString("last_network_ssid_hash", snapshot.ssidHash)
            .apply()
    }
}

internal data class BatterySnapshot(
    val levelPercent: Double,
    val isCharging: Boolean,
    val chargerType: String?,
    val capturedAt: Instant
)

internal data class NetworkSnapshot(
    val kind: String,
    val ssidHash: String?
)

internal fun currentBatterySnapshot(context: Context, now: Instant): BatterySnapshot? {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null

    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)

    return BatterySnapshot(
        levelPercent = level.toDouble() / scale.toDouble() * 100.0,
        isCharging = isCharging,
        chargerType = chargerTypeName(plugged),
        capturedAt = now
    )
}

internal fun currentNetworkSnapshot(context: Context): NetworkSnapshot? {
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
    val kind = networkKind(capabilities)
    val ssidHash = if (kind == "wifi") {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        ssidHash(wifiManager?.connectionInfo?.ssid)
    } else {
        null
    }
    return NetworkSnapshot(kind = kind, ssidHash = ssidHash)
}

internal fun chargerTypeName(plugged: Int): String? = when (plugged) {
    BatteryManager.BATTERY_PLUGGED_AC -> "ac"
    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
    else -> null
}

internal fun networkKind(capabilities: NetworkCapabilities?): String = when {
    capabilities == null -> "none"
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
    else -> "none"
}

internal fun ssidHash(ssid: String?): String? {
    val normalized = ssid
        ?.removePrefix("\"")
        ?.removeSuffix("\"")
        ?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID && it != "<unknown ssid>" }
        ?: return null

    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}
