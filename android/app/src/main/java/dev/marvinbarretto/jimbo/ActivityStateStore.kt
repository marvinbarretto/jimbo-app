package dev.marvinbarretto.jimbo

import android.content.Context

private const val PREFS = "activity_state"
private const val KEY_STATE = "current_activity"
private const val KEY_SINCE = "activity_since"

data class ActivityState(
    val state: String,
    val since: Long?
)

object ActivityStateStore {

    // Called from ActivityTransitionReceiver on ENTER transitions only.
    // EXIT transitions are intentionally ignored — we hold the prior state
    // until the next ENTER rather than resetting to unknown, which prevents
    // the home card from flickering on every transition boundary.
    fun onEnter(context: Context, activityTypeName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, activityTypeName)
            .putLong(KEY_SINCE, System.currentTimeMillis())
            .apply()
    }

    fun getCurrent(context: Context): ActivityState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = prefs.getString(KEY_STATE, "unknown") ?: "unknown"
        val since = if (prefs.contains(KEY_SINCE)) prefs.getLong(KEY_SINCE, 0L) else null
        return ActivityState(state, since)
    }
}
