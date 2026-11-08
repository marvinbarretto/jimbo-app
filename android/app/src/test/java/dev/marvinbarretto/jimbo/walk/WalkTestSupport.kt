package dev.marvinbarretto.jimbo.walk

import java.time.Instant

/** Metres per degree of latitude on the sphere WalkGeo uses. */
private val METRES_PER_DEGREE_LAT = WalkGeo.EARTH_RADIUS_M * Math.PI / 180.0

/** A fix [metres] due north of [spot]. */
internal fun northOf(spot: WalkSpot, metres: Double, accuracyMetres: Float? = 5f, at: Instant = Instant.EPOCH) =
    WalkFix(spot.latitude + metres / METRES_PER_DEGREE_LAT, spot.longitude, accuracyMetres, at)
