package dev.marvinbarretto.steps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import dev.marvinbarretto.steps.ui.StepsScreen
import dev.marvinbarretto.steps.ui.theme.StepsTheme
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val viewModel: StepsViewModel by viewModels()

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

    private fun scheduleSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            1, TimeUnit.HOURS,
            15, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "steps_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
