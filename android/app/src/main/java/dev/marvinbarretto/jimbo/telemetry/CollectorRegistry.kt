package dev.marvinbarretto.jimbo.telemetry

import dev.marvinbarretto.jimbo.data.CollectorSettingDao
import dev.marvinbarretto.jimbo.data.CollectorSettingEntity

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

    suspend fun allCollectors(): List<Collector> {
        seedDefaults()
        return collectorMap.values.toList()
    }

    suspend fun collectorStates(): Map<String, Boolean> {
        seedDefaults()
        return settingDao.getAll().associate { it.collectorId to it.enabled }
    }

    suspend fun setEnabled(collectorId: String, enabled: Boolean) {
        settingDao.upsert(CollectorSettingEntity(collectorId = collectorId, enabled = enabled))
    }
}
