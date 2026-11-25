package dev.marvinbarretto.jimbo.proposals

import dev.marvinbarretto.jimbo.widgets.WidgetLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ProposalsTest {
    private fun entry(id: String, at: String, text: String = "reply to Sam because he asked twice") =
        """{"id":"$id","at":"$at","text":"$text","action":{"type":"reply","target":null},"refs":[{"type":"task","id":"JIM-1"}]}"""

    private fun body(vararg e: String) = """{"entries":[${e.joinToString(",")}],"total":${e.size}}"""

    private val t0 = Instant.parse("2026-11-25T10:00:00Z")

    @Test fun parsesEntries() {
        val p = Proposals.parse(body(entry("a", "2026-11-25T10:05:00Z"), entry("b", "2026-11-25T10:01:00Z")))
        assertEquals(listOf("a", "b"), p.map { it.id })
        assertEquals("reply", p[0].actionType)
        assertEquals(Instant.parse("2026-11-25T10:05:00Z"), p[0].at)
    }

    @Test fun nullActionIsFine() {
        val p = Proposals.parse("""{"entries":[{"id":"a","at":"2026-11-25T10:05:00Z","text":"x","action":null,"refs":[]}]}""")
        assertNull(p.single().actionType)
    }

    @Test fun skipsMalformedEntries() {
        val p = Proposals.parse(body(entry("a", "not a date"), """{"id":"","at":"2026-11-25T10:05:00Z","text":"x"}""", entry("ok", "2026-11-25T10:05:00Z")))
        assertEquals(listOf("ok"), p.map { it.id })
    }

    @Test fun unreadableBodyIsEmpty() {
        assertTrue(Proposals.parse("<html>nope</html>").isEmpty())
        assertTrue(Proposals.parse("""{"error":"not found"}""").isEmpty())
        assertTrue(Proposals.parse("").isEmpty())
    }

    @Test fun firstRunLooksBackTwoHours() = assertEquals(t0.minusSeconds(7200), Proposals.since(null, t0))
    @Test fun laterRunsUseTheCursor() = assertEquals(t0, Proposals.since(t0, t0.plusSeconds(900)))

    @Test fun onlyEntriesAfterTheCursorAreNew() {
        val entries = Proposals.parse(body(entry("c", "2026-11-25T10:10:00Z"), entry("b", "2026-11-25T10:00:00Z"), entry("a", "2026-11-25T09:50:00Z")))
        val plan = Proposals.plan(entries, t0)
        assertEquals(listOf("c"), plan.fresh.map { it.id })
        assertEquals(Instant.parse("2026-11-25T10:10:00Z"), plan.cursor)
    }

    @Test fun freshComesOldestFirstAndDeduped() {
        val entries = Proposals.parse(body(entry("c", "2026-11-25T10:10:00Z"), entry("b", "2026-11-25T10:05:00Z"), entry("b", "2026-11-25T10:05:00Z")))
        assertEquals(listOf("b", "c"), Proposals.plan(entries, t0).fresh.map { it.id })
    }

    @Test fun nothingNewKeepsNoCursor() {
        val plan = Proposals.plan(Proposals.parse(body(entry("a", "2026-11-25T09:00:00Z"))), t0)
        assertTrue(plan.fresh.isEmpty()); assertNull(plan.cursor); assertEquals(false, plan.summary)
    }

    @Test fun threeAreIndividualFourAreASummary() {
        fun planOf(n: Int) = Proposals.plan(Proposals.parse(body(*(1..n).map { entry("p$it", "2026-11-25T10:0${it}:00Z") }.toTypedArray())), t0)
        assertEquals(false, planOf(3).summary)
        assertEquals(true, planOf(4).summary)
        assertEquals(4, planOf(4).fresh.size)
    }

    @Test fun verdictBodyCarriesTrimmedNoteOnlyWhenPresent() {
        fun fields(v: Verdict, note: String?) = org.json.JSONObject(Proposals.verdictBody(v, note)).let { j -> j.keys().asSequence().associateWith { j.getString(it) } }
        assertEquals(mapOf("verdict" to "right"), fields(Verdict.RIGHT, null))
        assertEquals(mapOf("verdict" to "do_it"), fields(Verdict.DO_IT, "  "))
        assertEquals(mapOf("verdict" to "wrong", "note" to "he already replied"), fields(Verdict.WRONG, " he already replied "))
    }

    @Test fun verdictOutcomes() {
        assertEquals(VerdictOutcome.DONE, VerdictOutcome.of(200))
        assertEquals(VerdictOutcome.DONE, VerdictOutcome.of(409))
        assertEquals(VerdictOutcome.REJECTED, VerdictOutcome.of(404))
        assertEquals(VerdictOutcome.REJECTED, VerdictOutcome.of(401))
        assertEquals(VerdictOutcome.RETRY, VerdictOutcome.of(503))
        assertEquals(VerdictOutcome.RETRY, VerdictOutcome.of(429))
    }

    @Test fun confirmationsAndBrief() {
        assertEquals("Marked right", Proposals.confirmation(Verdict.RIGHT))
        assertEquals("Marked wrong", Proposals.confirmation(Verdict.WRONG))
        assertEquals("Jimbo will do it next tick", Proposals.confirmation(Verdict.DO_IT))
        assertEquals(80, Proposals.brief("x".repeat(200)).length)
    }

    @Test fun jimboPageIsAnAllowedLink() = assertEquals("/m/jimbo", WidgetLinks.sanitize(WidgetLinks.JIMBO))
}
