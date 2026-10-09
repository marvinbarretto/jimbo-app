package dev.marvinbarretto.jimbo

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Owns the focus block's Do Not Disturb state, shared by the plugin and the
 * alarm that restores it. The user's previous interruption filter is persisted
 * so a restore after the WebView (or the process) has gone still puts back
 * exactly what was there, rather than assuming "everything".
 */
object DoNotDisturbController {
    private const val PREFS = "do_not_disturb"
    private const val KEY_PREVIOUS = "previous_filter"
    private const val RESTORE_REQUEST = 7301

    fun hasAccess(context: Context): Boolean =
        manager(context).isNotificationPolicyAccessGranted

    fun isEngaged(context: Context): Boolean = prefs(context).contains(KEY_PREVIOUS)

    /** Priority-only: alarms and starred contacts still ring, so the phone stays safe to rely on. */
    fun enable(context: Context, restoreAtMillis: Long?) {
        val nm = manager(context)
        // Keep the first remembered filter: a second enable() mid-block must not
        // overwrite the user's real setting with our own.
        if (!isEngaged(context)) {
            prefs(context).edit().putInt(KEY_PREVIOUS, nm.currentInterruptionFilter).apply()
        }
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        if (restoreAtMillis != null) scheduleRestore(context, restoreAtMillis) else cancelRestore(context)
    }

    fun restore(context: Context) {
        cancelRestore(context)
        val previous = prefs(context).takeIf { it.contains(KEY_PREVIOUS) }
            ?.getInt(KEY_PREVIOUS, NotificationManager.INTERRUPTION_FILTER_ALL)
            ?: return
        if (hasAccess(context)) manager(context).setInterruptionFilter(previous)
        prefs(context).edit().remove(KEY_PREVIOUS).apply()
    }

    private fun scheduleRestore(context: Context, atMillis: Long) {
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, restoreIntent(context, PendingIntent.FLAG_UPDATE_CURRENT))
    }

    private fun cancelRestore(context: Context) {
        val pending = restoreIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }

    private fun restoreIntent(context: Context, flag: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context, RESTORE_REQUEST,
            Intent(context, DoNotDisturbRestoreReceiver::class.java),
            flag or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Fires at the block's planned end so DND is handed back even if the WebView is gone. */
class DoNotDisturbRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DoNotDisturbController.restore(context)
    }
}
