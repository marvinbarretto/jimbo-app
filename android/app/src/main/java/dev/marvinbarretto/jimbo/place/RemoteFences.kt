package dev.marvinbarretto.jimbo.place

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import dev.marvinbarretto.jimbo.JimboClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "JimboSync"

/**
 * Every saved place is a phone fence (21 Nov 2026). Jimbo holds the places and their circles
 * (jimbo-api `GET /api/entities/places/fences`, set on the dashboard's /places); the phone keeps the
 * nearest ones, since Android allows an app 100 geofences, and asks again when it has moved or the
 * list is old. Each fence brings its role and actions (PlaceAction), set on /places. The settings-file
 * fences (home, gym, the Moons) are only a fallback until Jimbo's pick names a Home or Gym place
 * ([merge]); until then a remote fence within [DEDUPE_M] of one of them is dropped.
 *
 * The pick is kept in shared prefs as the API's own JSON, with where and when it was asked for.
 */
object RemoteFences {
    private const val PREFS = "remote_fences"
    const val ANDROID_MAX = 100
    /** Ask again after this long even without moving: a circle changed on /places reaches the phone. */
    const val REFRESH_AFTER_MS = 2 * 60 * 60 * 1000L
    /** Ask again after moving this far from where the last pick was made. */
    const val REPICK_AFTER_M = 2_000.0
    const val DEDUPE_M = 75.0

    data class Pick(val fences: List<PlaceGeofence>, val atMs: Long, val lat: Double, val lng: Double)

    /** The API's answer as fences. A malformed entry is skipped, never guessed at. */
    fun parse(json: String): List<PlaceGeofence> {
        val list = JSONObject(json).optJSONArray("fences") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val f = list.optJSONObject(i) ?: return@mapNotNull null
            val id = f.optString("id").lowercase()
            val lat = f.optDouble("lat", Double.NaN)
            val lng = f.optDouble("lng", Double.NaN)
            val radius = f.optDouble("radius_m", Double.NaN)
            if (!PlaceGeofence.isValidId(id) || lat.isNaN() || lng.isNaN() || radius.isNaN() || radius <= 0) return@mapNotNull null
            val role = when (f.optString("role")) { "home" -> Place.HOME; "gym" -> Place.GYM; else -> null }
            PlaceGeofence(
                id, lat, lng, radius.toFloat(), name = f.optString("name").takeIf { it.isNotBlank() },
                role = role, remoteActions = PlaceAction.parseAll(f.optJSONArray("actions")),
            )
        }
    }

    /**
     * What the phone fences. Once Jimbo's pick names a Home or Gym place, Jimbo owns every fence and the settings
     * file is ignored (22 Nov 2026: the three fences folded into Jimbo, their behaviour now actions there). Before
     * that (an older API, no pick yet, offline), the configured fences, then the remote ones not covered by one.
     */
    fun merge(configured: List<PlaceGeofence>, remote: List<PlaceGeofence>): List<PlaceGeofence> {
        if (remote.any { it.role != null }) return remote.distinctBy { it.id }.take(ANDROID_MAX)
        val extra = remote.filter { r ->
            configured.none { it.id == r.id || metresBetween(it.latitude, it.longitude, r.latitude, r.longitude) < DEDUPE_M }
        }
        return (configured + extra).distinctBy { it.id }.take(ANDROID_MAX)
    }

    fun isDue(nowMs: Long, last: Pick?, lat: Double, lng: Double): Boolean =
        last == null || nowMs - last.atMs >= REFRESH_AFTER_MS || metresBetween(last.lat, last.lng, lat, lng) >= REPICK_AFTER_M

    fun load(context: Context): Pick? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = p.getString("json", null) ?: return null
        return try {
            Pick(parse(json), p.getLong("at", 0), p.getFloat("lat", 0f).toDouble(), p.getFloat("lng", 0f).toDouble())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fetch a fresh pick when one is due, then re-register every fence. Called from the sync worker, so it rides
     * the 30-minute sync and the run on app open. Uses the phone's last known fix; never starts one.
     */
    @SuppressLint("MissingPermission")
    fun refreshIfDue(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        val here = try {
            Tasks.await(LocationServices.getFusedLocationProviderClient(context).lastLocation, 10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Remote fences: no last location", e); null
        } ?: return false
        val last = load(context)
        if (!isDue(nowMs, last, here.latitude, here.longitude)) return false

        // Rounded to about 100 m: enough to rank the nearest places, no finer than that leaves the phone.
        val lat = "%.3f".format(java.util.Locale.ROOT, here.latitude)
        val lng = "%.3f".format(java.util.Locale.ROOT, here.longitude)
        val room = ANDROID_MAX - PlaceGeofence.configured().size
        val (code, body) = JimboClient.getJson("/api/entities/places/fences?lat=$lat&lng=$lng&limit=$room")
        if (code !in 200..299) {
            Log.w(TAG, "Remote fences: HTTP $code"); return false
        }
        val fences = try { parse(body) } catch (e: Exception) { Log.w(TAG, "Remote fences: bad JSON", e); return false }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("json", body).putLong("at", nowMs)
            .putFloat("lat", here.latitude.toFloat()).putFloat("lng", here.longitude.toFloat())
            .apply()
        Log.d(TAG, "Remote fences: ${fences.size} picked")
        PlaceGeofenceManager.register(context)
        return true
    }

    /** Great-circle distance in metres (haversine). */
    fun metresBetween(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val rad = Math.PI / 180
        val h = sin((bLat - aLat) * rad / 2).pow(2) + cos(aLat * rad) * cos(bLat * rad) * sin((bLng - aLng) * rad / 2).pow(2)
        return 2 * 6_371_000 * asin(sqrt(h))
    }
}
