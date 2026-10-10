package dev.marvinbarretto.jimbo.widgets

import android.content.Context

/** Last good snapshot, so a failed refresh shows stale data rather than nothing. */
object WidgetStore {
    private const val PREFS = "jimbo_widgets"
    private const val KEY = "snapshot"

    fun read(context: Context): WidgetSnapshot =
        WidgetSnapshot.fromJson(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun write(context: Context, snapshot: WidgetSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, snapshot.toJson()).apply()
    }
}
