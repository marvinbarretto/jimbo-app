package dev.marvinbarretto.jimbo.proposals

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/** One thing Jimbo would do, from "Jimbo's log" (kind proposed, verdict pending). */
data class Proposal(val id: String, val at: Instant, val text: String, val actionType: String?)

/** What to post for a poll: the new proposals oldest first (so the newest ends on top), or one summary. */
data class NotifyPlan(val fresh: List<Proposal>, val summary: Boolean, val cursor: Instant?)

enum class Verdict(val wire: String) {
    RIGHT("right"), WRONG("wrong"), DO_IT("do_it");

    companion object { fun of(wire: String?): Verdict? = values().firstOrNull { it.wire == wire } }
}

/** What the verdict endpoint's answer means: 409 (already has a verdict) is done, not an error. */
enum class VerdictOutcome { DONE, RETRY, REJECTED;

    companion object {
        fun of(code: Int): VerdictOutcome = when {
            code in 200..299 || code == 409 -> DONE
            code == 408 || code == 429 || code >= 500 -> RETRY
            else -> REJECTED
        }
    }
}

/** Pure logic for the Jimbo proposals poller (ADR to come, 25 Nov 2026): parsing, what is new, summary or not. */
object Proposals {
    /** More than this many new at once collapses into one "Jimbo: N proposals" notification. */
    const val SUMMARY_ABOVE = 3
    const val LIMIT = 20
    val FIRST_RUN_WINDOW: Duration = Duration.ofHours(2)

    /** Entries the server sent, newest first; a malformed entry is skipped, an unreadable body is empty. */
    fun parse(body: String): List<Proposal> {
        val entries: JSONArray = try {
            JSONObject(body).optJSONArray("entries") ?: return emptyList()
        } catch (e: org.json.JSONException) {
            return emptyList()
        }
        return (0 until entries.length()).mapNotNull { i ->
            val e = entries.optJSONObject(i) ?: return@mapNotNull null
            val id = e.optString("id", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val text = e.optString("text", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val at = try { Instant.parse(e.optString("at", "")) } catch (ex: java.time.format.DateTimeParseException) { return@mapNotNull null }
            Proposal(id, at, text, e.optJSONObject("action")?.optString("type", "")?.takeIf { it.isNotBlank() })
        }
    }

    /** The `since` for the poll: the last `at` notified, or two hours back on the first run. */
    fun since(lastAt: Instant?, now: Instant): Instant = lastAt ?: now.minus(FIRST_RUN_WINDOW)

    fun plan(entries: List<Proposal>, since: Instant): NotifyPlan {
        val fresh = entries.filter { it.at.isAfter(since) }.distinctBy { it.id }.sortedBy { it.at }
        return NotifyPlan(fresh, fresh.size > SUMMARY_ABOVE, fresh.maxOfOrNull { it.at })
    }

    fun verdictBody(verdict: Verdict, note: String?): String =
        JSONObject().put("verdict", verdict.wire).apply { note?.trim()?.takeIf { it.isNotEmpty() }?.let { put("note", it) } }.toString()

    fun confirmation(verdict: Verdict): String = when (verdict) {
        Verdict.RIGHT -> "Marked right"
        Verdict.WRONG -> "Marked wrong"
        Verdict.DO_IT -> "Jimbo will do it next tick"
    }

    /** One line naming a proposal in a failure notice. */
    fun brief(text: String): String = text.trim().let { if (it.length <= 80) it else it.take(79) + "…" }
}
