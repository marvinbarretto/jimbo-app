package dev.marvinbarretto.jimbo

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Startup permission flow (Health Connect + activity recognition + location,
 * incl. the two-step background-location dance), shared by whichever activity
 * is the launcher. Lived inline in HomeActivity until MainActivity took over
 * as launcher — the WebView shell must run the same bootstrap or a fresh
 * install collects nothing.
 *
 * Construct as an activity field (contracts must register before STARTED),
 * then call [requestIfNeeded] from onCreate.
 */
class PermissionBootstrap(private val activity: ComponentActivity) {

    private val requestHealthPermissions = activity.registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectReader.PERMISSIONS)) {
            SyncScheduler.enqueueManualSync(activity)
        }
    }

    private val requestRuntimePermissions = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACTIVITY_RECOGNITION] == true) {
            ActivityRecognitionManager.register(activity)
        }
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true && !hasBackgroundLocation()) {
            requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else if (hasFineLocation() && hasBackgroundLocation()) {
            JimboLocationManager.register(activity)
        }
    }

    private val requestBackgroundLocation = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && hasFineLocation()) {
            JimboLocationManager.register(activity)
        }
    }

    fun requestIfNeeded() {
        activity.lifecycleScope.launch {
            requestHealthPermsIfNeeded()
            requestRuntimePermsIfNeeded()
        }
    }

    private suspend fun requestHealthPermsIfNeeded() {
        if (HealthConnectClient.getSdkStatus(activity) != HealthConnectClient.SDK_AVAILABLE) return
        val client = HealthConnectClient.getOrCreate(activity)
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
            requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun hasActivityRecognition() = hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    private fun hasFineLocation() = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun hasBackgroundLocation() = hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(activity, p) == PackageManager.PERMISSION_GRANTED
}
