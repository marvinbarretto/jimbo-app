package dev.marvinbarretto.jimbo.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class WidgetDataTest {

    private val day = LocalDate.parse("2026-10-10")

    @Test
    fun `macros come from today's row`() {
        val body = """{"days":[{"date":"2026-10-09","kcal":900,"protein_g":10},
            {"date":"2026-10-10","kcal":1234.6,"protein_g":87.2}],"complete_days":[]}"""
        assertEquals(Macros(1235, 87), WidgetData.parseMacros(body, day))
    }

    @Test
    fun `a day with no row is a real zero`() {
        assertEquals(Macros(0, 0), WidgetData.parseMacros("""{"days":[],"complete_days":[]}""", day))
    }

    @Test
    fun `a malformed body throws so the refresh counts as failed`() {
        var threw = false
        try { WidgetData.parseMacros("<html>", day) } catch (_: Exception) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `top priority is the first title`() {
        val body = """{"analysis":{"priorities":[{"title":" Ship the widgets ","reasoning":"r","constraint":"anytime"},
            {"title":"Second","reasoning":"r","constraint":"anytime"}]}}"""
        assertEquals("Ship the widgets", WidgetData.parsePriority(body))
    }

    @Test
    fun `no priorities means no line`() {
        assertNull(WidgetData.parsePriority("""{"analysis":{"priorities":[]}}"""))
        assertNull(WidgetData.parsePriority("""{"analysis":{}}"""))
    }

    @Test
    fun `logical day rolls over at 04-00 London not midnight`() {
        // 2026-10-10 02:30 BST = 01:30Z -> still the 9th
        assertEquals(LocalDate.parse("2026-10-09"), WidgetData.logicalDay(Instant.parse("2026-10-10T01:30:00Z")))
        // 04:30 BST = 03:30Z -> the 10th
        assertEquals(LocalDate.parse("2026-10-10"), WidgetData.logicalDay(Instant.parse("2026-10-10T03:30:00Z")))
    }

    @Test
    fun `fraction clamps to the ring`() {
        assertEquals(0.5f, WidgetData.fraction(1100, 2200), 0.0001f)
        assertEquals(1f, WidgetData.fraction(3000, 2200), 0f)
        assertEquals(0f, WidgetData.fraction(10, 0), 0f)
    }

    @Test
    fun `snapshot survives a round trip and bad input falls back to empty`() {
        val s = WidgetSnapshot(Macros(500, 40), "Do it", 123L)
        assertEquals(s, WidgetSnapshot.fromJson(s.toJson()))
        assertEquals(WidgetSnapshot.EMPTY, WidgetSnapshot.fromJson("not json"))
        assertEquals(WidgetSnapshot.EMPTY, WidgetSnapshot.fromJson(null))
    }

    @Test
    fun `never fetched or old data is stale`() {
        val now = 10_000_000_000L
        assertTrue(WidgetData.isStale(WidgetSnapshot.EMPTY, now))
        assertTrue(WidgetData.isStale(WidgetSnapshot(null, null, now - WidgetData.STALE_AFTER_MS - 1), now))
        assertFalse(WidgetData.isStale(WidgetSnapshot(null, null, now - 60_000), now))
    }

    @Test
    fun `only shell tab paths are accepted as deep links`() {
        assertEquals("/m/today", WidgetLinks.sanitize(WidgetLinks.TODAY))
        assertEquals("/m/log?focus=composer", WidgetLinks.sanitize(WidgetLinks.LOG_COMPOSER))
        assertNull(WidgetLinks.sanitize("/m/log');alert(1);('"))
        assertNull(WidgetLinks.sanitize("https://evil.example/m/log"))
        assertNull(WidgetLinks.sanitize(null))
    }
}
