package dev.marvinbarretto.jimbo.walk

import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One location fix, in plain Kotlin so the trigger tests run without a device. */
data class WalkFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float?,
    val at: Instant,
)

/** A spot reached: which one, and how far away the fix put you. */
data class WalkHit(val spot: WalkSpot, val distanceMetres: Double)

object WalkGeo {
    /** Mean Earth radius (IUGG). */
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance by haversine; at walking scale it is within centimetres of Android's. */
    fun distanceMetres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }
}

/**
 * Decides which spots a fix reaches: within [RADIUS_M], once per spot for the
 * life of this trigger (one walk session). A fix whose accuracy is worse than
 * [MAX_ACCURACY_M] cannot say you are within 30 m, so it fires nothing.
 */
class WalkTrigger(private val spots: List<WalkSpot>) {
    private val firedIds = linkedSetOf<String>()

    val fired: Set<String> get() = firedIds

    /** The spots this fix reaches for the first time, nearest first. */
    fun onFix(fix: WalkFix): List<WalkHit> {
        val accuracy = fix.accuracyMetres
        if (accuracy != null && accuracy > MAX_ACCURACY_M) return emptyList()
        val hits = spots
            .filter { it.id !in firedIds }
            .map { WalkHit(it, WalkGeo.distanceMetres(fix.latitude, fix.longitude, it.latitude, it.longitude)) }
            .filter { it.distanceMetres <= RADIUS_M }
            .sortedBy { it.distanceMetres }
        hits.forEach { firedIds += it.spot.id }
        return hits
    }

    companion object {
        const val RADIUS_M = 30.0
        const val MAX_ACCURACY_M = 50f
    }
}
