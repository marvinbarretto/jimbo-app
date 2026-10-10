package dev.marvinbarretto.jimbo.widgets

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the home-screen widgets show, and the pure parsing that produces it.
 *
 * Kept free of Android types beyond org.json so the day cutover and the
 * response shapes are unit-testable on the JVM.
 */
data class Macros(val kcal: Int, val proteinG: Int)

data class WidgetSnapshot(
    val macros: Macros?,
    val priority: String?,
    /** Epoch millis of the last refresh that reached the API; null = never. */
    val fetchedAtMs: Long?,
) {
    fun toJson(): String = JSONObject().apply {
        macros?.let { put("kcal", it.kcal); put("protein_g", it.proteinG) }
        priority?.let { put("priority", it) }
        fetchedAtMs?.let { put("fetched_at_ms", it) }
    }.toString()

    companion object {
        val EMPTY = WidgetSnapshot(null, null, null)

        fun fromJson(raw: String?): WidgetSnapshot {
            if (raw.isNullOrBlank()) return EMPTY
            return try {
                val o = JSONObject(raw)
                WidgetSnapshot(
                    macros = if (o.has("kcal")) Macros(o.getInt("kcal"), o.optInt("protein_g", 0)) else null,
                    priority = o.optString("priority").ifBlank { null },
                    fetchedAtMs = if (o.has("fetched_at_ms")) o.getLong("fetched_at_ms") else null,
                )
            } catch (_: Exception) {
                EMPTY
            }
        }
    }
}

object WidgetData {
    // Same goals as the dashboard's nutrition page (NutritionPage TARGETS) —
    // there is no targets endpoint, and v1 has no per-widget config.
    const val KCAL_TARGET = 2200
    const val PROTEIN_TARGET_G = 150

    /** A reading older than this is shown as stale rather than as today's truth. */
    const val STALE_AFTER_MS = 2 * 60 * 60 * 1000L

    private val LONDON = ZoneId.of("Europe/London")
    private const val CUTOVER_HOURS = 4L

    /** The logical day (04:00 London cutover) — the key `/food-log/daily` rows use. */
    fun logicalDay(now: Instant): LocalDate =
        now.atZone(LONDON).minusHours(CUTOVER_HOURS).toLocalDate()

    /**
     * Today's totals from a `/api/coach/food-log/daily` body. A day with no
     * entries has no row, which is a real zero — not a failure.
     */
    fun parseMacros(body: String, today: LocalDate): Macros {
        val days = JSONObject(body).getJSONArray("days")
        for (i in 0 until days.length()) {
            val row = days.getJSONObject(i)
            if (row.getString("date") == today.toString()) {
                return Macros(Math.round(row.optDouble("kcal", 0.0)).toInt(), Math.round(row.optDouble("protein_g", 0.0)).toInt())
            }
        }
        return Macros(0, 0)
    }

    /** The first priority title of a `/api/briefing/latest` body, or null if none. */
    fun parsePriority(body: String): String? {
        val priorities = JSONObject(body).optJSONObject("analysis")?.optJSONArray("priorities") ?: return null
        if (priorities.length() == 0) return null
        return priorities.getJSONObject(0).optString("title").trim().ifBlank { null }
    }

    fun fraction(value: Int, target: Int): Float =
        if (target <= 0) 0f else (value.toFloat() / target).coerceIn(0f, 1f)

    fun isStale(snapshot: WidgetSnapshot, nowMs: Long): Boolean {
        val at = snapshot.fetchedAtMs ?: return true
        return nowMs - at > STALE_AFTER_MS
    }
}

/**
 * Deep links the widgets hand to MainActivity. Only `/m/<tab>` with an
 * optional simple query is accepted — the path is evaluated as JS in the
 * WebView, so anything else is refused.
 */
object WidgetLinks {
    const val EXTRA_PATH = "jimbo_path"
    const val TODAY = "/m/today"
    const val LOG = "/m/log"
    // The dashboard's /m/log reads `focus=composer` to focus the quick-add input.
    const val LOG_COMPOSER = "/m/log?focus=composer"

    private val ALLOWED = Regex("/m/[a-z0-9-]+(\\?[a-z0-9=&_-]+)?")

    fun sanitize(raw: String?): String? = raw?.takeIf { ALLOWED.matches(it) }
}
