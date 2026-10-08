package dev.marvinbarretto.jimbo

import android.Manifest
import android.content.Context
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

/** Fallback switch: when on, opening Home goes straight into the /m shell. */
object HomePrefs {
    private const val FILE = "home_prefs"
    private const val KEY_OPEN_INTO_SHELL = "open_into_shell"

    fun openIntoShell(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_OPEN_INTO_SHELL, false)

    fun setOpenIntoShell(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_OPEN_INTO_SHELL, value).apply()
    }
}

/**
 * Native home. Not the launcher (see AndroidManifest) — the launcher flip is a
 * later call made on real usage. Only the morning card exists: it is the one
 * card whose signal (the briefing) is live.
 */
class HomeActivity : ComponentActivity() {

    // Shared with MainActivity (the launcher since the /m cutover) — kept here
    // too so opening Home directly still completes any missing grants.
    private val permissions = PermissionBootstrap(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (HomePrefs.openIntoShell(this)) {
            open(CardAction.OpenShell)
            finish()
            return
        }

        permissions.requestIfNeeded()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeScreen(
                    onAction = ::open,
                    onSkipHome = {
                        HomePrefs.setOpenIntoShell(this, true)
                        open(CardAction.OpenShell)
                        finish()
                    }
                )
            }
        }
    }

    // Every action is a deep link into an /m tab; MainActivity turns EXTRA_TAB into the route.
    private fun open(action: CardAction) {
        val intent = Intent(this, MainActivity::class.java)
        if (action is CardAction.OpenTab) {
            intent.putExtra(NotificationTriggerReceiver.EXTRA_TAB, action.tab)
        }
        startActivity(intent)
    }
}

@Composable
private fun HomeScreen(onAction: (CardAction) -> Unit, onSkipHome: () -> Unit) {
    val context = LocalContext.current
    var healthData by remember { mutableStateOf<TodayData?>(null) }
    var cardState by remember { mutableStateOf<MorningCardState>(MorningCardState.Loading) }
    // LocalDateTime, not LocalTime: the header formats "EEE d MMM", and a
    // time-only value carries no day or month, so formatting it threw
    // UnsupportedTemporalTypeException on every launch.
    val now = remember { LocalDateTime.now() }

    LaunchedEffect(Unit) {
        try {
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                healthData = withContext(Dispatchers.IO) { HealthConnectReader.readToday(context) }
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(Unit) {
        cardState = withContext(Dispatchers.IO) {
            try {
                val (code, body) = JimboClient.getLatestBriefing()
                if (code in 200..299) MorningCard.stateFromBody(body) else MorningCardState.Empty
            } catch (_: Exception) {
                MorningCardState.Empty
            }
        }
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

        val sleepLine = healthData?.sleepSessions
            ?.sumOf { it.durationMinutes }
            ?.takeIf { it > 0 }
            ?.let { mins -> "Sleep est: ${(mins / 60).toInt()}h ${(mins % 60).toInt()}m" }

        MorningCardView(state = cardState, sleepLine = sleepLine, onAction = onAction)

        healthData?.let { StatsRow(it) }

        Spacer(modifier = Modifier.weight(1f))

        QuickActions(onAction = onAction)

        TextButton(onClick = onSkipHome, modifier = Modifier.fillMaxWidth()) {
            Text("Skip home, open straight into Jimbo", color = Color(0xFF777777), fontSize = 12.sp)
        }
    }
}

@Composable
internal fun MorningCardView(state: MorningCardState, sleepLine: String?, onAction: (CardAction) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Good morning", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            sleepLine?.let { Text(it, color = Color(0xFF999999), fontSize = 14.sp) }

            when (state) {
                MorningCardState.Loading ->
                    Text("Loading briefing…", color = Color(0xFF999999), fontSize = 14.sp)

                MorningCardState.Empty ->
                    Text("No briefing yet today", color = Color(0xFF999999), fontSize = 14.sp)

                is MorningCardState.Loaded -> {
                    state.briefing.firstPlanItem?.let {
                        Text(it, color = Color(0xFFCCCCCC), fontSize = 14.sp)
                    }
                    CardLink("Today's briefing") { onAction(CardAction.OpenTab(MorningCard.TAB_TODAY)) }
                    MorningCard.taskLine(state.briefing)?.let { line ->
                        CardLink(line) { onAction(CardAction.OpenTab(MorningCard.TAB_TODAY)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardLink(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Text("$label  ›", color = Color.White)
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
private fun QuickActions(onAction: (CardAction) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickAction("Capture", Modifier.weight(1f)) { onAction(CardAction.OpenTab(MorningCard.TAB_LOG)) }
        QuickAction("Gym", Modifier.weight(1f)) { onAction(CardAction.OpenTab(MorningCard.TAB_TRAIN)) }
        QuickAction("Open", Modifier.weight(1f)) { onAction(CardAction.OpenShell) }
    }
}

@Composable
private fun QuickAction(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(label, color = Color.White, modifier = Modifier.padding(vertical = 4.dp))
    }
}

private fun Long.toLocaleString(): String = String.format(Locale.getDefault(), "%,d", this)
