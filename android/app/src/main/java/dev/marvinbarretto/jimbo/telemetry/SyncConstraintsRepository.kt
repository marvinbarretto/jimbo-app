package dev.marvinbarretto.jimbo.telemetry

import android.content.Context
import dev.marvinbarretto.jimbo.data.StepsDatabase
import dev.marvinbarretto.jimbo.data.SyncConstraintEntity

data class SyncConstraints(
    val wifiOnly: Boolean = true,
    val minBatteryPercent: Int = 15
)

class SyncConstraintsRepository(context: Context) {
    private val dao = StepsDatabase.getInstance(context).syncConstraintDao()

    suspend fun get(): SyncConstraints {
        val entity = dao.get() ?: SyncConstraintEntity()
        if (dao.get() == null) {
            dao.upsert(entity)
        }
        return SyncConstraints(
            wifiOnly = entity.wifiOnly,
            minBatteryPercent = entity.minBatteryPercent
        )
    }

    suspend fun updateWifiOnly(enabled: Boolean) {
        val current = dao.get() ?: SyncConstraintEntity()
        dao.upsert(current.copy(wifiOnly = enabled))
    }

    suspend fun updateMinBatteryPercent(percent: Int) {
        val current = dao.get() ?: SyncConstraintEntity()
        dao.upsert(current.copy(minBatteryPercent = percent))
    }
}
