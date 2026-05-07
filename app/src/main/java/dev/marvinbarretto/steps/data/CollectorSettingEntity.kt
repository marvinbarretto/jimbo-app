package dev.marvinbarretto.steps.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "collector_settings")
data class CollectorSettingEntity(
    @PrimaryKey val collectorId: String,
    val enabled: Boolean
)
