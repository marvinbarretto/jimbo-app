package dev.marvinbarretto.steps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.marvinbarretto.steps.BatteryStatus
import dev.marvinbarretto.steps.CollectorStatus
import dev.marvinbarretto.steps.NetworkStatus
import dev.marvinbarretto.steps.StatusViewModel
import dev.marvinbarretto.steps.SyncState
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    viewModel: StatusViewModel,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Telemetry Status") },
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
            StatusSummaryCard(
                syncState = state.syncState,
                lastSyncTime = state.lastSyncTime,
                pendingEvents = state.pendingEvents,
                deadLetterEvents = state.deadLetterEvents,
                syncRequestInFlight = state.syncRequestInFlight,
                onRefresh = { viewModel.refreshStatus() },
                onSyncNow = { viewModel.syncNow() },
                onOpenSettings = onOpenSettings
            )

            LocalStateCard(
                battery = state.battery,
                network = state.network
            )

            CollectorCard(state.collectors)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: dev.marvinbarretto.steps.SettingsUiState,
    onBack: () -> Unit,
    onSyncNow: () -> Unit,
    onRefresh: () -> Unit,
    onCollectorToggle: (String, Boolean) -> Unit,
    onOpenUsageAccess: () -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onBatteryThresholdChanged: (Int) -> Unit,
    onRetryDeadLetter: (String) -> Unit,
    onDeleteDeadLetter: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                }
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
            SyncConstraintCard(
                wifiOnly = state.syncConstraints.wifiOnly,
                minBatteryPercent = state.syncConstraints.minBatteryPercent,
                onWifiOnlyChanged = onWifiOnlyChanged,
                onBatteryThresholdChanged = onBatteryThresholdChanged
            )

            CollectorSettingsCard(
                collectors = state.collectors,
                onCollectorToggle = onCollectorToggle,
                onOpenUsageAccess = onOpenUsageAccess
            )

            QueueCard(
                pendingEvents = state.pendingEvents,
                pendingQueuedBytes = state.pendingQueuedBytes,
                deadLetterCount = state.deadLetterCount,
                onSyncNow = onSyncNow,
                onRefresh = onRefresh
            )

            DeadLetterCard(
                deadLetters = state.deadLetters,
                onRetry = onRetryDeadLetter,
                onDelete = onDeleteDeadLetter
            )
        }
    }
}

@Composable
private fun StatusSummaryCard(
    syncState: SyncState,
    lastSyncTime: Long?,
    pendingEvents: Int,
    deadLetterEvents: Int,
    syncRequestInFlight: Boolean,
    onRefresh: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = syncStateLabel(syncState), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = "Last sync ${relativeTime(lastSyncTime)}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "$pendingEvents pending events", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "$deadLetterEvents dead-letter events",
                style = MaterialTheme.typography.bodyMedium,
                color = if (deadLetterEvents > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSyncNow, enabled = !syncRequestInFlight) { Text("Sync now") }
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
            }
        }
    }
}

@Composable
private fun LocalStateCard(battery: BatteryStatus, network: NetworkStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Local state", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = "Battery: ${batteryLabel(battery)}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Network: ${networkLabel(network)}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CollectorCard(collectors: List<CollectorStatus>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "Collectors", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (collectors.isEmpty()) {
                Text(text = "No collector events yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                collectors.forEach { collector ->
                    Text(
                        text = "${collectorName(collector.collectorId)} - ${relativeTime(collector.lastSeenAt)}, ${collector.eventCount} events",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncConstraintCard(
    wifiOnly: Boolean,
    minBatteryPercent: Int,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onBatteryThresholdChanged: (Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "Sync constraints", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("WiFi only")
                androidx.compose.material3.Switch(checked = wifiOnly, onCheckedChange = onWifiOnlyChanged)
            }
            Text("Min battery threshold: $minBatteryPercent%")
            androidx.compose.material3.Slider(
                value = minBatteryPercent.toFloat(),
                onValueChange = { onBatteryThresholdChanged(it.toInt()) },
                valueRange = 0f..100f
            )
        }
    }
}

@Composable
private fun CollectorSettingsCard(
    collectors: List<dev.marvinbarretto.steps.telemetry.CollectorDescriptor>,
    onCollectorToggle: (String, Boolean) -> Unit,
    onOpenUsageAccess: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Collectors", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            collectors.forEach { collector ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(collector.label, fontWeight = FontWeight.SemiBold)
                            Text(collector.cadenceLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(
                            checked = collector.enabled,
                            onCheckedChange = { onCollectorToggle(collector.id, it) }
                        )
                    }
                    Text("Last collected ${relativeTime(collector.lastCollectedAt)}", style = MaterialTheme.typography.bodySmall)
                    Text("${collector.eventsLast24h} events in last 24h", style = MaterialTheme.typography.bodySmall)
                    if (collector.permissionRequired && !collector.permissionGranted) {
                        Text("Usage access required", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onOpenUsageAccess) { Text("Grant usage access") }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueCard(
    pendingEvents: Int,
    pendingQueuedBytes: Long,
    deadLetterCount: Int,
    onSyncNow: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Queue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("$pendingEvents pending events")
            Text("${pendingQueuedBytes} queued bytes")
            Text("$deadLetterCount dead-letter events")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSyncNow) { Text("Sync now") }
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
            }
        }
    }
}

@Composable
private fun DeadLetterCard(
    deadLetters: List<dev.marvinbarretto.steps.DeadLetterUi>,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Dead-letter", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (deadLetters.isEmpty()) {
                Text("No dead-letter events", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                deadLetters.forEach { event ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(event.title, fontWeight = FontWeight.SemiBold)
                        Text(event.json, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onRetry(event.id) }) { Text("Retry") }
                            OutlinedButton(onClick = { onDelete(event.id) }) { Text("Delete") }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

private fun collectorName(id: String): String = when (id) {
    "health_connect" -> "Health Connect"
    "device" -> "Device"
    "usage" -> "Usage Stats"
    else -> id
}

private fun syncStateLabel(state: SyncState): String = when (state) {
    SyncState.IDLE -> "Idle"
    SyncState.SYNCING -> "Syncing"
    SyncState.BACKING_OFF -> "Backing off"
}

private fun batteryLabel(battery: BatteryStatus): String {
    val level = battery.levelPercent?.let { "${it.toInt()}%" } ?: "unknown"
    val charging = if (battery.isCharging) battery.chargerType?.let { "charging via $it" } ?: "charging" else "not charging"
    return "$level, $charging"
}

private fun networkLabel(network: NetworkStatus): String {
    val kind = when (network.kind) {
        "wifi" -> "WiFi"
        "cellular" -> "Cellular"
        "none" -> "Offline"
        else -> network.kind
    }
    return network.detail?.let { "$kind ($it)" } ?: kind
}

private fun relativeTime(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0L) return "never"
    val duration = Duration.between(Instant.ofEpochMilli(epochMillis), Instant.now())
    val minutes = duration.toMinutes()
    val hours = duration.toHours()
    val days = duration.toDays()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        else -> "${days}d ago"
    }
}
