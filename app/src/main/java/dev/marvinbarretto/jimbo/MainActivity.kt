package dev.marvinbarretto.jimbo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import dev.marvinbarretto.jimbo.telemetry.ACTION_NETWORK_STATE_CHANGED
import dev.marvinbarretto.jimbo.telemetry.TelemetryStore
import dev.marvinbarretto.jimbo.ui.JimboApp
import dev.marvinbarretto.jimbo.ui.theme.JimboTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val statusViewModel: StatusViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val telemetryStore by lazy { TelemetryStore(this) }
    private var deviceReceiverRegistered = false
    private var networkCallbackRegistered = false
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }

    private val deviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            lifecycleScope.launch {
                telemetryStore.collectBroadcast(action)
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            collectNetworkState()
        }

        override fun onLost(network: Network) {
            collectNetworkState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            collectNetworkState()
        }
    }

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectReader.PERMISSIONS)) {
            SyncScheduler.schedulePeriodic(this)
            statusViewModel.refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            JimboTheme {
                JimboApp(
                    statusViewModel = statusViewModel,
                    settingsViewModel = settingsViewModel
                )
            }
        }

        lifecycleScope.launch {
            try {
                SyncScheduler.schedulePeriodic(this@MainActivity)

                val available = HealthConnectClient.getSdkStatus(this@MainActivity)
                if (available == HealthConnectClient.SDK_AVAILABLE) {
                    val client = HealthConnectClient.getOrCreate(this@MainActivity)
                    val granted = client.permissionController.getGrantedPermissions()
                    if (!granted.containsAll(HealthConnectReader.PERMISSIONS)) {
                        requestPermissions.launch(HealthConnectReader.PERMISSIONS)
                    }
                }
                // Re-register activity transitions on each app start if permission is
                // already granted — Play Services doesn't persist registrations across reboots.
                if (hasActivityRecognitionPermission()) {
                    ActivityRecognitionManager.register(this@MainActivity)
                }

                // Same re-registration requirement for location updates.
                if (hasFineLocationPermission() && hasBackgroundLocationPermission()) {
                    JimboLocationManager.register(this@MainActivity)
                }

                statusViewModel.refreshStatus()
                settingsViewModel.refresh()
            } catch (e: Exception) {
                android.util.Log.e("JimboSync", "Error in permission check", e)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerDeviceReceiver()
        registerNetworkCallback()
        statusViewModel.refreshStatus()
        settingsViewModel.refresh()
    }

    override fun onStop() {
        if (deviceReceiverRegistered) {
            unregisterReceiver(deviceStateReceiver)
            deviceReceiverRegistered = false
        }
        unregisterNetworkCallback()
        super.onStop()
    }

    private fun registerDeviceReceiver() {
        if (deviceReceiverRegistered) {
            return
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(deviceStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(deviceStateReceiver, filter)
        }
        deviceReceiverRegistered = true
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) {
            return
        }
        connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        networkCallbackRegistered = true
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) {
            return
        }
        connectivityManager?.unregisterNetworkCallback(networkCallback)
        networkCallbackRegistered = false
    }

    fun hasActivityRecognitionPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun collectNetworkState() {
        lifecycleScope.launch {
            telemetryStore.collectBroadcast(ACTION_NETWORK_STATE_CHANGED)
        }
    }
}
