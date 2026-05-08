package dev.marvinbarretto.jimbo.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.marvinbarretto.jimbo.ActivityRecognitionManager
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
