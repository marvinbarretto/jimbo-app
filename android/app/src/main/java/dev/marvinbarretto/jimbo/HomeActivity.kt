package dev.marvinbarretto.jimbo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class HomeActivity : ComponentActivity() {

    private val requestHealthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectReader.PERMISSIONS)) {
            SyncScheduler.enqueueManualSync(this)
        }
    }

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
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            requestHealthPermsIfNeeded()
            requestRuntimePermsIfNeeded()
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeScreen(
                    onOpenGym = {
                        startActivity(Intent(this@HomeActivity, MainActivity::class.java))
                    }
                )
            }
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
            requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun hasActivityRecognition() = hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    private fun hasFineLocation() = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun hasBackgroundLocation() = hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun HomeScreen(onOpenGym: () -> Unit) {
    val context = LocalContext.current
    var healthData by remember { mutableStateOf<TodayData?>(null) }
    val activityState = remember { ActivityStateStore.getCurrent(context) }
    // LocalDateTime, not LocalTime: the header formats "EEE d MMM", and a
    // time-only value carries no day or month, so formatting it threw
    // UnsupportedTemporalTypeException on every launch. `.hour` still works.
    val now = remember { LocalDateTime.now() }

    LaunchedEffect(Unit) {
        try {
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                healthData = withContext(Dispatchers.IO) { HealthConnectReader.readToday(context) }
            }
        } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .statusBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = now.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())),
            color = Color(0xFF888888),
            fontSize = 13.sp
        )

        ContextCard(hour = now.hour, activityState = activityState, healthData = healthData)

        healthData?.let { StatsRow(it) }

        Spacer(modifier = Modifier.weight(1f))

        QuickActions(onOpenGym = onOpenGym)
    }
}

@Composable
private fun ContextCard(hour: Int, activityState: ActivityState, healthData: TodayData?) {
    val (headline, subline) = when {
        activityState.state in setOf("running", "on_bicycle") ->
            "You're active" to activityState.state.replace("_", " ").replaceFirstChar { it.uppercase() }

        hour in 5..9 -> {
            val sleepLine = healthData?.sleepSessions
                ?.sumOf { it.durationMinutes }
                ?.takeIf { it > 0 }
                ?.let { mins -> "Sleep est: ${(mins / 60).toInt()}h ${(mins % 60).toInt()}m" }
            "Good morning" to (sleepLine ?: "Ready for the day")
        }

        hour >= 21 || hour < 4 ->
            "How was today?" to "Reflect, journal, or log"

        activityState.state == "in_vehicle" ->
            "On the move" to "Commuting"

        else -> {
            val steps = healthData?.steps?.takeIf { it > 0 }
            "Today so far" to (steps?.let { "${it.toLocaleString()} steps" } ?: "No data yet")
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(headline, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subline, color = Color(0xFF999999), fontSize = 14.sp)
        }
    }
}

@Composable
private fun StatsRow(data: TodayData) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatChip(
            label = "Steps",
            value = data.steps.takeIf { it > 0 }?.toLocaleString() ?: "—",
            modifier = Modifier.weight(1f)
        )
        StatChip(
            label = "Active kcal",
            value = data.caloriesActive.takeIf { it > 0 }?.let { "${it.toInt()}" } ?: "—",
            modifier = Modifier.weight(1f)
        )
        StatChip(
            label = "HR avg",
            value = data.heartRateAvg?.let { "${it.toInt()} bpm" } ?: "—",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(label, color = Color(0xFF666666), fontSize = 11.sp)
        }
    }
}

@Composable
private fun QuickActions(onOpenGym: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onOpenGym,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Open gym", color = Color.White, modifier = Modifier.padding(vertical = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {},
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                enabled = false
            ) {
                Text("Focus", color = Color(0xFF555555))
            }
            OutlinedButton(
                onClick = {},
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                enabled = false
            ) {
                Text("Reflect", color = Color(0xFF555555))
            }
        }
    }
}

private fun Long.toLocaleString(): String = String.format(Locale.getDefault(), "%,d", this)
