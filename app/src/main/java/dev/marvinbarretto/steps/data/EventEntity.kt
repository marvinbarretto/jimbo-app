package dev.marvinbarretto.steps.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONObject

@Entity(
    tableName = "events",
    indices = [
        Index("syncedAt"),
        Index(value = ["collector", "type"])
    ]
)
data class EventEntity(
    @PrimaryKey val id: String,
    val collector: String,
    val type: String,
    val ts: String,
    val tsEnd: String?,
    val value: Double?,
    val unit: String?,
    val source: String?,
    val payload: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
    val attempts: Int = 0,
    val deadLetter: Boolean = false
) {
    fun toRequestJson(): JSONObject = JSONObject().apply {
        put("client_event_id", id)
        put("collector", collector)
        put("type", type)
        put("ts", ts)
        put("ts_end", tsEnd ?: JSONObject.NULL)
        put("value", value ?: JSONObject.NULL)
        put("unit", unit ?: JSONObject.NULL)
        put("source", source ?: JSONObject.NULL)
        put("payload", payload?.let(::JSONObject) ?: JSONObject.NULL)
    }
}
