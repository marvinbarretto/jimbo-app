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
    const val FOCUS = "focus"
    const val PLACES = "places"
    const val WALK = "walk"
    const val SLEEP = "sleep"

    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(BRIEFING, "Briefing", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(NUDGES, "Nudges", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(GYM, "Gym", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(PLACES, "Places", NotificationManager.IMPORTANCE_DEFAULT),
                // The morning "were you asleep?" question (sleep-metric, SM-7959).
                NotificationChannel(SLEEP, "Sleep question", NotificationManager.IMPORTANCE_DEFAULT),
                // Walk mode's start/stop controls: silent, they only carry the buttons.
                NotificationChannel(WALK, "Walk mode", NotificationManager.IMPORTANCE_LOW),
                // High importance + vibration: a finished block is meant to be felt, not just seen.
                NotificationChannel(FOCUS, "Focus", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 120, 250)
                },
            )
        )
    }

    fun resolve(requested: String?): String =
        if (requested in setOf(BRIEFING, NUDGES, GYM, FOCUS, PLACES)) requested!! else NUDGES
}
