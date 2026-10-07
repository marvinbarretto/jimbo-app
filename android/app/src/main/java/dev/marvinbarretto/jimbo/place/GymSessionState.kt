package dev.marvinbarretto.jimbo.place

import dev.marvinbarretto.jimbo.JimboClient
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** What the API says about gym sessions right now: one running, or when the last one ended. */
data class GymSessionState(val hasActive: Boolean, val lastEndedAt: Instant?) {
    companion object {
        /** Null when the API can't be read — the caller then stays quiet rather than guess. */
        fun fetch(): GymSessionState? {
            val (activeCode, activeBody) = JimboClient.getJson("/api/gym/sessions/active")
            if (activeCode !in 200..299) return null
            val hasActive = activeBody.isNotBlank() && activeBody.trim() != "null"
            if (hasActive) return GymSessionState(true, null)

            val (listCode, listBody) = JimboClient.getJson("/api/gym/sessions?limit=1")
            if (listCode !in 200..299) return null
            val ended = JSONArray(listBody).optJSONObject(0)?.optStringOrNull("ended_at")
            return GymSessionState(false, ended?.let { Instant.parse(it) })
        }

        /** Starts a session unless one is already running; true when one is running afterwards. */
        fun startIfNone(): Boolean {
            val state = fetch() ?: return false
            if (state.hasActive) return true
            val (code, _) = JimboClient.postGymSession(JSONObject().put("started_at", Instant.now().toString()).toString())
            return code in 200..299
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}
