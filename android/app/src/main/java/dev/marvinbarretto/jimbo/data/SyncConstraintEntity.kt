package dev.marvinbarretto.jimbo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_constraints")
data class SyncConstraintEntity(
    @PrimaryKey val id: String = DEFAULT_ID,
    // Any connection, not Wi-Fi only (22 Nov 2026): out on mobile data a fence exit or stay waited hours for Wi-Fi.
    val wifiOnly: Boolean = false,
    val minBatteryPercent: Int = 15
) {
    companion object {
        const val DEFAULT_ID = "default"
    }
}
