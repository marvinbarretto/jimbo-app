package dev.marvinbarretto.jimbo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * One channel per domain, so Android's per-channel mute does the frequency
 * preference work. Creation is idempotent.
 */
object NotificationChannels {
    const val BRIEFING = "briefing"
    const val NUDGES = "nudges"
    const val GYM = "gym"

    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(BRIEFING, "Briefing", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(NUDGES, "Nudges", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(GYM, "Gym", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
    }

    fun resolve(requested: String?): String =
        if (requested in setOf(BRIEFING, NUDGES, GYM)) requested!! else NUDGES
}
