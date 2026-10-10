package dev.marvinbarretto.jimbo

import org.json.JSONObject

/** What the morning card shows from `GET /api/briefing/latest`. */
data class BriefingSummary(
    val firstPlanItem: String?,
    val taskTitles: List<String>,
) {
    val taskCount: Int get() = taskTitles.size
}

sealed interface MorningCardState {
    data object Loading : MorningCardState
    data class Loaded(val briefing: BriefingSummary) : MorningCardState
    /** No fresh briefing (404, offline, or a briefing with nothing to show). */
    data object Empty : MorningCardState
}

/** Where a card action goes. Every action lands on an /m tab or a native surface. */
sealed interface CardAction {
    data class OpenTab(val tab: String) : CardAction
    data object OpenShell : CardAction
}

object MorningCard {
    const val TAB_TODAY = "today"
    const val TAB_LOG = "log"
    const val TAB_TRAIN = "train"

    /**
     * Reads the BriefingAnalysis body. Returns [MorningCardState.Empty] when the
     * JSON is malformed or carries neither a day-plan item nor a vault task —
     * an empty card beats a card of blanks.
     */
    fun stateFromBody(body: String): MorningCardState {
        val analysis = try {
            JSONObject(body).optJSONObject("analysis")
        } catch (_: Exception) {
            null
        } ?: return MorningCardState.Empty

        val plan = analysis.optJSONArray("day_plan")
        val firstPlan = plan?.optJSONObject(0)?.optString("suggestion")?.takeIf { it.isNotBlank() }
        val tasksJson = analysis.optJSONArray("vault_tasks")
        val tasks = buildList {
            for (i in 0 until (tasksJson?.length() ?: 0)) {
                tasksJson?.optJSONObject(i)?.optString("title")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        if (firstPlan == null && tasks.isEmpty()) return MorningCardState.Empty
        return MorningCardState.Loaded(BriefingSummary(firstPlan, tasks))
    }

    fun taskLine(briefing: BriefingSummary): String? = when (briefing.taskCount) {
        0 -> null
        1 -> "1 task in focus"
        else -> "${briefing.taskCount} tasks in focus"
    }
}
