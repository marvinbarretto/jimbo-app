package dev.marvinbarretto.jimbo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.marvinbarretto.jimbo.ActivityRecognitionManager
import dev.marvinbarretto.jimbo.JimboLocationManager
import dev.marvinbarretto.jimbo.SettingsViewModel
import dev.marvinbarretto.jimbo.StatusViewModel

@Composable
fun JimboApp(
    statusViewModel: StatusViewModel,
    settingsViewModel: SettingsViewModel
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // Runtime permission launcher for ACTIVITY_RECOGNITION.
    // Unlike usage access and notification listener, this shows the standard
    // Android permission dialog rather than deep-linking to a settings screen.
    val activityPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ActivityRecognitionManager.register(context)
            settingsViewModel.refresh()
        }
    }

    // Location is a two-step grant on Android 11+: fine location first (dialog),
    // then background location separately (also a dialog, but opens the system
    // location permission screen where the user selects "Allow all the time").
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            JimboLocationManager.register(context)
            settingsViewModel.refresh()
        }
    }
    val fineLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) settingsViewModel.refresh()
        // Background location requested separately — user taps the button again
        // after fine is granted, which then launches backgroundLocationLauncher.
    }

    NavHost(navController = navController, startDestination = "status") {
        composable("status") {
            StatusScreen(
                viewModel = statusViewModel,
                onOpenSettings = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            val state by settingsViewModel.state.collectAsState()
            SettingsScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onSyncNow = { statusViewModel.syncNow() },
                onRefresh = {
                    settingsViewModel.refresh()
                    statusViewModel.refreshStatus()
                },
                onCollectorToggle = settingsViewModel::setCollectorEnabled,
                onOpenPermissionSettings = { collectorId ->
                    when (collectorId) {
                        "activity" -> activityPermissionLauncher.launch(
                            Manifest.permission.ACTIVITY_RECOGNITION
                        )
                        "location" -> {
                            val hasFine = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!hasFine) {
                                fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            } else {
                                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                        }
                        "notifications" -> context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                        else -> context.startActivity(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        )
                    }
                },
                onWifiOnlyChanged = settingsViewModel::setWifiOnly,
                onBatteryThresholdChanged = settingsViewModel::setMinBatteryPercent,
                onRetryDeadLetter = settingsViewModel::retryDeadLetter,
                onDeleteDeadLetter = settingsViewModel::deleteDeadLetter
            )
        }
    }
}
