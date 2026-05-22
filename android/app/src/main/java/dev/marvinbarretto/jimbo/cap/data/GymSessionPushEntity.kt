package dev.marvinbarretto.jimbo.cap.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// Tracks Health Connect exercise sessions that have already been materialized
// into the gym API as gym_sessions, so the periodic backfill doesn't double-post.
// hcUid is the stable record.metadata.id from HealthConnect.
@Entity(tableName = "gym_session_pushes")
data class GymSessionPushEntity(
    @PrimaryKey val hcUid: String,
    val gymSessionId: String,
    val pushedAt: Long = System.currentTimeMillis()
)
