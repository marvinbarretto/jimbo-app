package dev.marvinbarretto.jimbo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_constraints")
data class SyncConstraintEntity(
    @PrimaryKey val id: String = DEFAULT_ID,
    val wifiOnly: Boolean = true,
    val minBatteryPercent: Int = 15
) {
    companion object {
        const val DEFAULT_ID = "default"
    }
}
