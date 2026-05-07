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
import androidx.work.*
import dev.marvinbarretto.steps.telemetry.TelemetryStore
import dev.marvinbarretto.steps.ui.StepsScreen
import dev.marvinbarretto.steps.ui.theme.StepsTheme
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val viewModel: StepsViewModel by viewModels()
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
            scheduleSync()
            viewModel.loadAndSync()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            StepsTheme {
                StepsScreen(viewModel)
            }
        }

        val available = HealthConnectClient.getSdkStatus(this)
        if (available != HealthConnectClient.SDK_AVAILABLE) {
            android.util.Log.e("StepsSync", "Health Connect not available: $available")
            return
        }

        lifecycleScope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(this@MainActivity)
                val granted = client.permissionController.getGrantedPermissions()

                if (granted.containsAll(HealthConnectReader.PERMISSIONS)) {
                    scheduleSync()
                    viewModel.loadAndSync()
                } else {
                    requestPermissions.launch(HealthConnectReader.PERMISSIONS)
                }
            } catch (e: Exception) {
                android.util.Log.e("StepsSync", "Error in permission check", e)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerDeviceReceiver()
    }

    override fun onStop() {
        if (deviceReceiverRegistered) {
            unregisterReceiver(deviceStateReceiver)
            deviceReceiverRegistered = false
        }
        super.onStop()
    }

    private fun scheduleSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            30, TimeUnit.MINUTES,
            15, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
        )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "steps_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
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
