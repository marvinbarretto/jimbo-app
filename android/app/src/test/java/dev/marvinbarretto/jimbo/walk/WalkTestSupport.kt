package dev.marvinbarretto.jimbo.walk

import java.time.Instant

/** Metres per degree of latitude on the sphere WalkGeo uses. */
private val METRES_PER_DEGREE_LAT = WalkGeo.EARTH_RADIUS_M * Math.PI / 180.0

/** A fix [metres] due north of [spot]. */
internal fun northOf(spot: WalkSpot, metres: Double, accuracyMetres: Float? = 5f, at: Instant = Instant.EPOCH) =
    WalkFix(spot.latitude + metres / METRES_PER_DEGREE_LAT, spot.longitude, accuracyMetres, at)

/** A manual clock for WalkSchedule: [advance] runs whatever falls due, in order. */
internal class FakeSchedule : WalkSchedule {
    private class Task(val due: Long, val action: () -> Unit) { var cancelled = false }
    private val tasks = mutableListOf<Task>()
    var now = 0L
        private set

    override fun after(delayMs: Long, action: () -> Unit): () -> Unit {
        val task = Task(now + delayMs, action)
        tasks += task
        return { task.cancelled = true }
    }

    fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.due <= until }.minByOrNull { it.due } ?: break
            tasks.remove(next)
            now = next.due
            next.action()
        }
        now = until
    }

    val pending: Int get() = tasks.count { !it.cancelled }
}
