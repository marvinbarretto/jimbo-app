package dev.marvinbarretto.jimbo.telemetry

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dev.marvinbarretto.jimbo.data.CollectorRecentSummary
import dev.marvinbarretto.jimbo.data.DeadLetterEventSummary
import dev.marvinbarretto.jimbo.data.StepsDatabase
import java.time.Duration

data class CollectorDescriptor(
    val id: String,
    val label: String,
    val cadenceLabel: String,
    val enabled: Boolean,
    val lastCollectedAt: Long?,
    val eventsLast24h: Int,
    val permissionRequired: Boolean,
    val permissionGranted: Boolean
)

class TelemetryRepository(private val context: Context) {
    private val database = StepsDatabase.getInstance(context)
    private val eventDao = database.eventDao()
    private val collectorRegistry = CollectorRegistry(
        settingDao = database.collectorSettingDao(),
        collectors = buildCollectors(context)
    )
    private val syncConstraintsRepository = SyncConstraintsRepository(context)

    suspend fun collectorDescriptors(): List<CollectorDescriptor> {
        val since = System.currentTimeMillis() - Duration.ofHours(24).toMillis()
        val summaries = eventDao.collectorRecentSummaries(since).associateBy { it.collector }
        val enabledStates = collectorRegistry.collectorStates()

        return collectorRegistry.allCollectors().map { collector ->
            val summary = summaries[collector.id]
            val permissionGranted = permissionGranted(context, collector.id)
            CollectorDescriptor(
                id = collector.id,
                label = collectorLabel(collector.id),
                cadenceLabel = cadenceLabel(collector.cadence),
                enabled = enabledStates[collector.id] ?: collector.defaultEnabled,
                lastCollectedAt = summary?.lastSeenAt,
                eventsLast24h = summary?.eventCountLast24h ?: 0,
                permissionRequired = collector.id in setOf("usage", "notifications"),
                permissionGranted = permissionGranted
            )
        }
    }

    suspend fun setCollectorEnabled(collectorId: String, enabled: Boolean) {
        collectorRegistry.setEnabled(collectorId, enabled)
    }

    suspend fun pendingCount(): Int = eventDao.pendingCount()

    suspend fun pendingQueuedBytes(): Long = eventDao.pendingQueuedBytes()

    suspend fun deadLetterCount(): Int = eventDao.deadLetterCount()

    suspend fun deadLetters(): List<DeadLetterEventSummary> = eventDao.deadLetterEvents()

    suspend fun retryDeadLetter(id: String) {
        eventDao.retryDeadLetter(id)
    }

    suspend fun deleteDeadLetter(id: String) {
        eventDao.deleteEvent(id)
    }

    suspend fun syncConstraints(): SyncConstraints = syncConstraintsRepository.get()

    suspend fun updateWifiOnly(enabled: Boolean) {
        syncConstraintsRepository.updateWifiOnly(enabled)
    }

    suspend fun updateMinBatteryPercent(percent: Int) {
        syncConstraintsRepository.updateMinBatteryPercent(percent)
    }
}

internal fun buildCollectors(context: Context): List<Collector> = listOf(
    HealthConnectCollector(context),
    DeviceCollector(context),
    UsageCollector(context),
    NotificationCollector()
)

internal fun cadenceLabel(cadence: CollectorCadence): String = when (cadence) {
    is CollectorCadence.Periodic -> {
        val hours = cadence.interval.toHours()
        val minutes = cadence.interval.toMinutes()
        when {
            hours > 0 -> "every $hours hour${if (hours == 1L) "" else "s"}"
            else -> "every $minutes minutes"
        }
    }
    is CollectorCadence.OnBroadcast -> "on broadcast"
    CollectorCadence.Realtime -> "realtime"
}

internal fun collectorLabel(id: String): String = when (id) {
    "health_connect" -> "Health Connect"
    "device" -> "Device state"
    "usage" -> "Usage stats"
    "notifications" -> "Notifications"
    else -> id
}

internal fun permissionGranted(context: Context, collectorId: String): Boolean = when (collectorId) {
    "usage" -> hasUsageAccess(context)
    "notifications" -> hasNotificationListenerAccess(context)
    else -> true
}

internal fun hasNotificationListenerAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
