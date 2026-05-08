package dev.marvinbarretto.jimbo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.marvinbarretto.jimbo.data.DeadLetterEventSummary
import dev.marvinbarretto.jimbo.telemetry.CollectorDescriptor
import dev.marvinbarretto.jimbo.telemetry.SyncConstraints
import dev.marvinbarretto.jimbo.telemetry.TelemetryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DeadLetterUi(
    val id: String,
    val title: String,
    val json: String
)

data class SettingsUiState(
    val collectors: List<CollectorDescriptor> = emptyList(),
    val pendingEvents: Int = 0,
    val pendingQueuedBytes: Long = 0,
    val deadLetterCount: Int = 0,
    val deadLetters: List<DeadLetterUi> = emptyList(),
    val syncConstraints: SyncConstraints = SyncConstraints()
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    private val repository = TelemetryRepository(application)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = SettingsUiState(
                collectors = repository.collectorDescriptors(),
                pendingEvents = repository.pendingCount(),
                pendingQueuedBytes = repository.pendingQueuedBytes(),
                deadLetterCount = repository.deadLetterCount(),
                deadLetters = repository.deadLetters().map(::toDeadLetterUi),
                syncConstraints = repository.syncConstraints()
            )
        }
    }

    fun setCollectorEnabled(collectorId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setCollectorEnabled(collectorId, enabled)
            refresh()
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateWifiOnly(enabled)
            SyncScheduler.schedulePeriodic(getApplication())
            refresh()
        }
    }

    fun setMinBatteryPercent(percent: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateMinBatteryPercent(percent)
            refresh()
        }
    }

    fun retryDeadLetter(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.retryDeadLetter(id)
            refresh()
        }
    }

    fun deleteDeadLetter(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteDeadLetter(id)
            refresh()
        }
    }

    private fun toDeadLetterUi(event: DeadLetterEventSummary): DeadLetterUi {
        val payloadJson = event.payload ?: "null"
        val json = buildString {
            append("{")
            append("\"id\":\"${event.id}\",")
            append("\"collector\":\"${event.collector}\",")
            append("\"type\":\"${event.type}\",")
            append("\"ts\":\"${event.ts}\",")
            append("\"value\":${event.value ?: "null"},")
            append("\"unit\":${event.unit?.let { "\"$it\"" } ?: "null"},")
            append("\"source\":${event.source?.let { "\"$it\"" } ?: "null"},")
            append("\"attempts\":${event.attempts},")
            append("\"payload\":$payloadJson")
            append("}")
        }
        return DeadLetterUi(
            id = event.id,
            title = "${event.collector} / ${event.type}",
            json = json
        )
    }
}
