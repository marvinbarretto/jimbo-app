package dev.marvinbarretto.steps

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

private const val TAG = "StepsViewModel"

enum class SyncStatus { IDLE, LOADING, SYNCING, SUCCESS, ERROR }

data class StepsUiState(
    val todayData: TodayData? = null,
    val syncStatus: SyncStatus = SyncStatus.IDLE,
    val lastSyncTime: Long? = null,
    val errorMessage: String? = null
)

class StepsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(StepsUiState())
    val state: StateFlow<StepsUiState> = _state

    private val prefs = application.getSharedPreferences("steps_sync", Context.MODE_PRIVATE)

    init {
        _state.value = _state.value.copy(lastSyncTime = prefs.getLong("last_sync", 0).takeIf { it > 0 })
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
                val start = end.minus(Duration.ofHours(2))
                val payload = HealthConnectReader.readAll(getApplication(), start, end)
                val count = payload.getJSONArray("records").length()

                if (count == 0) {
                    _state.value = _state.value.copy(syncStatus = SyncStatus.SUCCESS)
                    return@launch
                }

                val (code, body) = JimboClient.postSync(payload.toString())
                if (code in 200..299) {
                    val now = System.currentTimeMillis()
                    prefs.edit().putLong("last_sync", now).apply()
                    _state.value = _state.value.copy(
                        syncStatus = SyncStatus.SUCCESS,
                        lastSyncTime = now
                    )
                } else {
                    _state.value = _state.value.copy(
                        syncStatus = SyncStatus.ERROR,
                        errorMessage = "Sync failed (HTTP $code): $body"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "syncNow failed", e)
                _state.value = _state.value.copy(
                    syncStatus = SyncStatus.ERROR,
                    errorMessage = "Sync error: ${e.message}"
                )
            }

            try {
                val data = HealthConnectReader.readToday(getApplication())
                _state.value = _state.value.copy(todayData = data)
            } catch (_: Exception) {}
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
                val lastSync = prefs.getLong("last_sync", 0)
                val end = Instant.now()
                val start = if (lastSync == 0L) {
                    end.minus(Duration.ofDays(30))
                } else {
                    end.minus(Duration.ofHours(2))
                }

                val payload = HealthConnectReader.readAll(getApplication(), start, end)
                val count = payload.getJSONArray("records").length()

                if (count > 0) {
                    val (code, _) = JimboClient.postSync(payload.toString())
                    if (code in 200..299) {
                        val now = System.currentTimeMillis()
                        prefs.edit().putLong("last_sync", now).apply()
                        _state.value = _state.value.copy(syncStatus = SyncStatus.SUCCESS, lastSyncTime = now)
                    } else {
                        _state.value = _state.value.copy(syncStatus = SyncStatus.ERROR, errorMessage = "Sync HTTP $code")
                    }
                } else {
                    _state.value = _state.value.copy(syncStatus = SyncStatus.SUCCESS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadAndSync sync failed", e)
                _state.value = _state.value.copy(
                    syncStatus = SyncStatus.ERROR,
                    errorMessage = "Sync error: ${e.message}"
                )
            }
        }
    }
}
