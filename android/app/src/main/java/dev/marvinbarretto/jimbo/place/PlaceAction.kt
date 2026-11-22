package dev.marvinbarretto.jimbo.place

import org.json.JSONArray

/**
 * What the phone does on a visit (22 Nov 2026): set on the dashboard's /places, fetched with the fences
 * (jimbo-api PlaceActionSchema). [When] is arrive (entering), stay (the fence's 3-minute dwell) or leave;
 * [Do] is a notification, one unseen line from a Jimbo list (marked shown), or the gym session offer.
 */
data class PlaceAction(val whenever: When, val action: Do, val title: String? = null, val text: String? = null, val list: String? = null) {
    enum class When(val wire: String) { ARRIVE("arrive"), STAY("stay"), LEAVE("leave") }
    enum class Do(val wire: String) { NOTIFY("notify"), QUIP("quip"), GYM_OFFER("gym_offer") }

    companion object {
        /** The API's list. An action the phone doesn't know (a newer kind) is skipped, never guessed at. */
        fun parseAll(array: JSONArray?): List<PlaceAction> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val whenever = When.values().firstOrNull { it.wire == o.optString("when") } ?: return@mapNotNull null
                val action = Do.values().firstOrNull { it.wire == o.optString("do") } ?: return@mapNotNull null
                val list = o.optString("list").takeIf { it.isNotBlank() }
                if (action == Do.QUIP && list == null) return@mapNotNull null
                PlaceAction(whenever, action, o.optString("title").takeIf { it.isNotBlank() }, o.optString("text").takeIf { it.isNotBlank() }, list)
            }
        }
    }
}
