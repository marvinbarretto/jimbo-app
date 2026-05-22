package dev.marvinbarretto.jimbo.data

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
        tsEnd?.let { put("ts_end", it) }
        value?.let { put("value", it) }
        unit?.let { put("unit", it) }
        source?.let { put("source", it) }
        payload
            ?.let(::JSONObject)
            ?.let(::removeNulls)
            ?.takeIf { it.length() > 0 }
            ?.let { put("payload", it) }
    }

    private fun removeNulls(jsonObject: JSONObject): JSONObject = JSONObject().apply {
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObject.get(key)
            if (value != JSONObject.NULL) {
                put(key, value)
            }
        }
    }
}
