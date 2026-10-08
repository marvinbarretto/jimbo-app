package dev.marvinbarretto.jimbo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MorningCardStateTest {

    private val full = """
        {"id":1,"session":"morning","analysis":{
          "day_plan":[{"time":"09:00","suggestion":"Ship the home card","source":"vault","reasoning":"r"}],
          "vault_tasks":[{"title":"A","priority":1,"actionability":"clear","note":"n"},
                         {"title":"B","priority":2,"actionability":"clear","note":"n"}]}}
    """.trimIndent()

    @Test
    fun loadedBriefingCarriesPlanAndTasks() {
        val state = MorningCard.stateFromBody(full)
        assertTrue(state is MorningCardState.Loaded)
        val b = (state as MorningCardState.Loaded).briefing
        assertEquals("Ship the home card", b.firstPlanItem)
        assertEquals(listOf("A", "B"), b.taskTitles)
        assertEquals("2 tasks in focus", MorningCard.taskLine(b))
    }

    @Test
    fun emptyBriefingIsEmptyState() {
        assertEquals(
            MorningCardState.Empty,
            MorningCard.stateFromBody("""{"analysis":{"day_plan":[],"vault_tasks":[]}}""")
        )
    }

    @Test
    fun malformedOrMissingAnalysisIsEmptyState() {
        assertEquals(MorningCardState.Empty, MorningCard.stateFromBody("not json"))
        assertEquals(MorningCardState.Empty, MorningCard.stateFromBody("{}"))
    }

    @Test
    fun noTasksMeansNoTaskLine() {
        val b = BriefingSummary("x", emptyList())
        assertNull(MorningCard.taskLine(b))
        assertEquals("1 task in focus", MorningCard.taskLine(BriefingSummary(null, listOf("a"))))
    }
}
