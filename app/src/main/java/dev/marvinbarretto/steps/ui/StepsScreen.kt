package dev.marvinbarretto.steps.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.marvinbarretto.steps.StepsViewModel
import dev.marvinbarretto.steps.SyncStatus
import dev.marvinbarretto.steps.TodayData
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepsScreen(viewModel: StepsViewModel) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Steps") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Date header
            Text(
                text = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Sync controls
            SyncCard(
                syncStatus = state.syncStatus,
                lastSyncTime = state.lastSyncTime,
                errorMessage = state.errorMessage,
                onSyncClick = { viewModel.syncNow() },
                onLoadAndSyncClick = { viewModel.loadAndSync() }
            )

            // Health data
            val data = state.todayData
            if (data != null) {
                Text(
                    text = "Today's Activity",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${data.recordCount} records from Health Connect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                StatRow("Steps", "%,d".format(data.steps))
                StatRow("Distance", "%.2f km".format(data.distanceMetres / 1000))
                StatRow("Active Calories", "%.0f kcal".format(data.caloriesActive))
                StatRow("Total Calories", "%.0f kcal".format(data.caloriesTotal))
                StatRow("Floors Climbed", "%.0f".format(data.floors))
                StatRow("Elevation Gained", "%.1f m".format(data.elevationMetres))

                if (data.exerciseSessions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Exercise Sessions",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    data.exerciseSessions.forEach { session ->
                        ExerciseCard(session)
                    }
                }
            } else if (state.syncStatus == SyncStatus.IDLE) {
                Text(
                    text = "Tap 'Load & Sync' to read health data",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SyncCard(
    syncStatus: SyncStatus,
    lastSyncTime: Long?,
    errorMessage: String?,
    onSyncClick: () -> Unit,
    onLoadAndSyncClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (syncStatus) {
                    SyncStatus.LOADING -> {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Reading health data...")
                    }
                    SyncStatus.SYNCING -> {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Syncing to Jimbo...")
                    }
                    SyncStatus.SUCCESS -> Text("Sync complete", color = MaterialTheme.colorScheme.primary)
                    SyncStatus.ERROR -> Text("Error", color = MaterialTheme.colorScheme.error)
                    SyncStatus.IDLE -> Text("Ready")
                }
            }

            if (errorMessage != null) {
                Text(text = errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (lastSyncTime != null && lastSyncTime > 0) {
                Text(
                    text = "Last sync: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastSyncTime))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onLoadAndSyncClick,
                    enabled = syncStatus != SyncStatus.LOADING && syncStatus != SyncStatus.SYNCING
                ) {
                    Text("Load & Sync")
                }
                OutlinedButton(
                    onClick = onSyncClick,
                    enabled = syncStatus != SyncStatus.LOADING && syncStatus != SyncStatus.SYNCING
                ) {
                    Text("Sync Only")
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ExerciseCard(session: dev.marvinbarretto.steps.ExerciseSession) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = session.type.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "%.0f min".format(session.durationMin),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "at ${session.startTime.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))} — ${session.sourceApp.substringAfterLast(".")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
