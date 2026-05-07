package dev.marvinbarretto.steps

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import dev.marvinbarretto.steps.data.CollectorEventSummary
import dev.marvinbarretto.steps.data.StepsDatabase
import dev.marvinbarretto.steps.telemetry.currentBatterySnapshot
import dev.marvinbarretto.steps.telemetry.currentNetworkSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

data class CollectorStatus(
    val collectorId: String,
    val lastSeenAt: Long?,
    val eventCount: Int
)

data class BatteryStatus(
    val levelPercent: Double?,
    val isCharging: Boolean,
    val chargerType: String?
)

data class NetworkStatus(
    val kind: String,
    val detail: String?
)

enum class SyncState {
    IDLE,
    SYNCING,
    BACKING_OFF
}

data class StatusUiState(
    val lastSyncTime: Long? = null,
    val pendingEvents: Int = 0,
    val deadLetterEvents: Int = 0,
    val collectors: List<CollectorStatus> = emptyList(),
    val syncState: SyncState = SyncState.IDLE,
    val battery: BatteryStatus = BatteryStatus(null, false, null),
    val network: NetworkStatus = NetworkStatus("unknown", null),
    val syncRequestInFlight: Boolean = false
)

class StatusViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(StatusUiState())
    val state: StateFlow<StatusUiState> = _state

    private val prefs = application.getSharedPreferences("steps_sync", Context.MODE_PRIVATE)
    private val database = StepsDatabase.getInstance(application)
    private val workManager = WorkManager.getInstance(application)

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val eventDao = database.eventDao()
            val pending = eventDao.pendingCount()
            val deadLetters = eventDao.deadLetterCount()
            val collectorSummaries = eventDao.collectorSummaries().map(::toCollectorStatus)
            val workInfos = workManager
                .getWorkInfos(
                    WorkQuery.Builder
                        .fromUniqueWorkNames(listOf(SyncScheduler.periodicWorkName(), SyncScheduler.manualWorkName()))
                        .build()
                )
                .get()

            val batterySnapshot = currentBatterySnapshot(getApplication(), Instant.now())
            val networkSnapshot = currentNetworkSnapshot(getApplication())

            _state.value = StatusUiState(
                lastSyncTime = prefs.getLong("last_sync", 0).takeIf { it > 0 },
                pendingEvents = pending,
                deadLetterEvents = deadLetters,
                collectors = collectorSummaries,
                syncState = deriveSyncState(workInfos),
                battery = BatteryStatus(
                    levelPercent = batterySnapshot?.levelPercent,
                    isCharging = batterySnapshot?.isCharging == true,
                    chargerType = batterySnapshot?.chargerType
                ),
                network = NetworkStatus(
                    kind = networkSnapshot?.kind ?: "unknown",
                    detail = networkSnapshot?.ssidHash
                ),
                syncRequestInFlight = workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            )
        }
    }

    fun syncNow() {
        viewModelScope.launch(Dispatchers.IO) {
            SyncScheduler.enqueueManualSync(getApplication())
            refreshStatus()
        }
    }

    private fun deriveSyncState(workInfos: List<WorkInfo>): SyncState = when {
        workInfos.any { it.state == WorkInfo.State.RUNNING } -> SyncState.SYNCING
        workInfos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 } -> SyncState.BACKING_OFF
        else -> SyncState.IDLE
    }

    private fun toCollectorStatus(summary: CollectorEventSummary): CollectorStatus =
        CollectorStatus(
            collectorId = summary.collector,
            lastSeenAt = summary.lastSeenAt,
            eventCount = summary.eventCount
        )
}
