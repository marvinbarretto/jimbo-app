package dev.marvinbarretto.steps

import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import dev.marvinbarretto.steps.telemetry.TelemetryStore
import dev.marvinbarretto.steps.ui.StepsApp
import dev.marvinbarretto.steps.ui.theme.StepsTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val statusViewModel: StatusViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val telemetryStore by lazy { TelemetryStore(this) }
    private var deviceReceiverRegistered = false

    private val deviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            lifecycleScope.launch {
                telemetryStore.collectBroadcast(action)
            }
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
            StepsTheme {
                StepsApp(
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
                statusViewModel.refreshStatus()
                settingsViewModel.refresh()
            } catch (e: Exception) {
                android.util.Log.e("StepsSync", "Error in permission check", e)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerDeviceReceiver()
        statusViewModel.refreshStatus()
        settingsViewModel.refresh()
    }

    override fun onStop() {
        if (deviceReceiverRegistered) {
            unregisterReceiver(deviceStateReceiver)
            deviceReceiverRegistered = false
        }
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
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(deviceStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(deviceStateReceiver, filter)
        }
        deviceReceiverRegistered = true
    }
}
