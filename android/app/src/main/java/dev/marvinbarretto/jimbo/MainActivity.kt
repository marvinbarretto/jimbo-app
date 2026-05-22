package dev.marvinbarretto.jimbo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.getcapacitor.BridgeActivity
import dev.marvinbarretto.jimbo.plugins.TelemetryPlugin
import kotlinx.coroutines.launch

class MainActivity : BridgeActivity() {

    private val requestHealthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectReader.PERMISSIONS)) {
            // Kick a manual sync so GymSessionBridge runs immediately rather than
            // waiting for the next periodic tick.
            SyncScheduler.enqueueManualSync(this)
        }
    }

    // Activity recognition + fine location requested together in one dialog.
    // Background location must be a separate request AFTER fine is granted (Android 11+).
    private val requestRuntimePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACTIVITY_RECOGNITION] == true) {
            ActivityRecognitionManager.register(this)
        }
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true && !hasBackgroundLocation()) {
            requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else if (hasFineLocation() && hasBackgroundLocation()) {
            JimboLocationManager.register(this)
        }
    }

    private val requestBackgroundLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && hasFineLocation()) {
            JimboLocationManager.register(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Plugins must be registered before super.onCreate() so they're available
        // when the WebView loads and JS calls Capacitor.Plugins.<Name>.
        registerPlugin(TelemetryPlugin::class.java)
        super.onCreate(savedInstanceState)

        BridgeRegistry.getInstance(this).apply {
            registerCapability("telemetry", 1)
            attachToBridge(bridge)
        }

        // WorkManager handles its own scheduling persistence; UPDATE policy makes this
        // a cheap re-arm on every launch. BootReceiver also calls this after reboot.
        SyncScheduler.schedulePeriodic(this)
        SyncScheduler.enqueueManualSync(this)

        // Re-register Play Services managers on launch — they don't persist across
        // process restarts. Safe to call when already registered (PendingIntent is
        // the same key).
        if (hasActivityRecognition()) {
            ActivityRecognitionManager.register(this)
        }
        if (hasFineLocation() && hasBackgroundLocation()) {
            JimboLocationManager.register(this)
        }

        lifecycleScope.launch {
            requestHealthPermsIfNeeded()
            requestRuntimePermsIfNeeded()
        }
    }

    private suspend fun requestHealthPermsIfNeeded() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) return
        val client = HealthConnectClient.getOrCreate(this)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(HealthConnectReader.PERMISSIONS)) {
            requestHealthPermissions.launch(HealthConnectReader.PERMISSIONS)
        }
    }

    private fun requestRuntimePermsIfNeeded() {
        val missing = buildList {
            if (!hasActivityRecognition()) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (!hasFineLocation()) add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (missing.isNotEmpty()) {
            requestRuntimePermissions.launch(missing.toTypedArray())
        } else if (hasFineLocation() && !hasBackgroundLocation()) {
            // Already have fine but missing background — request the separate dialog.
            requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun hasActivityRecognition(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
