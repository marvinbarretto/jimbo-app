package dev.marvinbarretto.jimbo.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.marvinbarretto.jimbo.SettingsViewModel
import dev.marvinbarretto.jimbo.StatusViewModel

@Composable
fun JimboApp(
    statusViewModel: StatusViewModel,
    settingsViewModel: SettingsViewModel
) {
    val navController = rememberNavController()
    val context = LocalContext.current

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
                onOpenUsageAccess = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                },
                onWifiOnlyChanged = settingsViewModel::setWifiOnly,
                onBatteryThresholdChanged = settingsViewModel::setMinBatteryPercent,
                onRetryDeadLetter = settingsViewModel::retryDeadLetter,
                onDeleteDeadLetter = settingsViewModel::deleteDeadLetter
            )
        }
    }
}
