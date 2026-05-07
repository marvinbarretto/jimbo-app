package dev.marvinbarretto.steps

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.marvinbarretto.steps.telemetry.TelemetryDrainOutcome
import dev.marvinbarretto.steps.telemetry.TelemetryStore
import dev.marvinbarretto.steps.telemetry.TelemetrySyncer
import dev.marvinbarretto.steps.telemetry.TimeWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

private const val TAG = "StepsViewModel"
private const val SYNC_WINDOW_HOURS = 2L

enum class SyncStatus { IDLE, LOADING, SYNCING, SUCCESS, ERROR }

data class StepsUiState(
    val todayData: TodayData? = null,
    val syncStatus: SyncStatus = SyncStatus.IDLE,
    val lastSyncTime: Long? = null,
    val errorMessage: String? = null,
    val pendingRecords: Int = 0
)

class StepsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(StepsUiState())
    val state: StateFlow<StepsUiState> = _state

    private val prefs = application.getSharedPreferences("steps_sync", Context.MODE_PRIVATE)
    private val telemetryStore = TelemetryStore(application)
    private val telemetrySyncer = TelemetrySyncer(application)

    init {
        _state.value = _state.value.copy(lastSyncTime = prefs.getLong("last_sync", 0).takeIf { it > 0 })
        viewModelScope.launch(Dispatchers.IO) {
            val pending = telemetryStore.pendingCount()
            _state.value = _state.value.copy(pendingRecords = pending)
        }
    }

    fun loadToday() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(syncStatus = SyncStatus.LOADING, errorMessage = null)
            try {
                val data = HealthConnectReader.readToday(getApplication())
                _state.value = _state.value.copy(todayData = data, syncStatus = SyncStatus.IDLE)
            } catch (e: Exception) {
                Log.e(TAG, "loadToday failed", e)
                _state.value = _state.value.copy(
                    syncStatus = SyncStatus.ERROR,
                    errorMessage = "Failed to read health data: ${e.message}"
                )
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(syncStatus = SyncStatus.SYNCING, errorMessage = null)
            try {
                val end = Instant.now()
                val start = end.minus(Duration.ofHours(SYNC_WINDOW_HOURS))
                collectAndSync(TimeWindow(start, end))
            } catch (e: Exception) {
                Log.e(TAG, "syncNow failed", e)
                _state.value = _state.value.copy(
                    syncStatus = SyncStatus.ERROR,
                    errorMessage = "Sync error: ${e.message}",
                    pendingRecords = telemetryStore.pendingCount()
                )
            }

            refreshTodaySilently()
        }
    }

    fun loadAndSync() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(syncStatus = SyncStatus.LOADING, errorMessage = null)

            try {
                val data = HealthConnectReader.readToday(getApplication())
                _state.value = _state.value.copy(todayData = data)
            } catch (e: Exception) {
                Log.e(TAG, "loadAndSync read failed", e)
                _state.value = _state.value.copy(
                    syncStatus = SyncStatus.ERROR,
                    errorMessage = "Failed to read: ${e.message}"
                )
                return@launch
            }

            _state.value = _state.value.copy(syncStatus = SyncStatus.SYNCING)
            try {
                val end = Instant.now()
                val start = end.minus(Duration.ofHours(SYNC_WINDOW_HOURS))
                collectAndSync(TimeWindow(start, end))
            } catch (e: Exception) {
                Log.e(TAG, "loadAndSync sync failed", e)
                _state.value = _state.value.copy(
                    syncStatus = SyncStatus.ERROR,
                    errorMessage = "Sync error: ${e.message}",
                    pendingRecords = telemetryStore.pendingCount()
                )
            }
        }
    }

    private suspend fun collectAndSync(window: TimeWindow) {
        telemetryStore.collect(window)
        val pendingAfterCollect = telemetryStore.pendingCount()
        Log.d(TAG, "Telemetry pending after collect: $pendingAfterCollect")

        when (val outcome = telemetrySyncer.drainPending()) {
            is TelemetryDrainOutcome.Success -> {
                val now = System.currentTimeMillis()
                prefs.edit().putLong("last_sync", now).apply()
                _state.value = _state.value.copy(
                    syncStatus = SyncStatus.SUCCESS,
                    lastSyncTime = now,
                    pendingRecords = telemetryStore.pendingCount()
                )
            }

            is TelemetryDrainOutcome.RetryableFailure -> {
                _state.value = _state.value.copy(
                    syncStatus = SyncStatus.ERROR,
                    errorMessage = "Sync will retry later",
                    pendingRecords = outcome.pendingCount
                )
            }

            is TelemetryDrainOutcome.PermanentFailure -> {
                _state.value = _state.value.copy(
                    syncStatus = SyncStatus.ERROR,
                    errorMessage = "Sync failed; some events moved to dead-letter",
                    pendingRecords = outcome.pendingCount
                )
            }
        }
    }

    private suspend fun refreshTodaySilently() {
        try {
            val data = HealthConnectReader.readToday(getApplication())
            _state.value = _state.value.copy(todayData = data)
        } catch (_: Exception) {
        }
    }
}
