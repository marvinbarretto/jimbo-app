package dev.marvinbarretto.steps.telemetry

import dev.marvinbarretto.steps.data.CollectorSettingDao
import dev.marvinbarretto.steps.data.CollectorSettingEntity

class CollectorRegistry(
    private val settingDao: CollectorSettingDao,
    collectors: List<Collector> = emptyList()
) {
    private val collectorMap = collectors.associateBy { it.id }

    suspend fun seedDefaults() {
        settingDao.insertDefaults(
            collectorMap.values.map { collector ->
                CollectorSettingEntity(
                    collectorId = collector.id,
                    enabled = collector.defaultEnabled
                )
            }
        )
    }

    suspend fun enabledCollectors(): List<Collector> {
        seedDefaults()
        val settings = settingDao.getAll().associateBy { it.collectorId }
        return collectorMap.values.filter { collector ->
            settings[collector.id]?.enabled ?: collector.defaultEnabled
        }
    }

    suspend fun setEnabled(collectorId: String, enabled: Boolean) {
        settingDao.upsert(CollectorSettingEntity(collectorId = collectorId, enabled = enabled))
    }
}
