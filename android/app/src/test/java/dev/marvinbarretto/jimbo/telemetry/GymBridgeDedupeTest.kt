package dev.marvinbarretto.jimbo.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class GymBridgeDedupeTest {
    private val t = Instant.parse("2026-10-30T15:50:13Z")
    private fun r(uid: String, startOffset: Long, endOffset: Long, type: Int = 79) =
        BridgeRecord(uid, t.plusSeconds(startOffset), t.plusSeconds(endOffset), type)

    @Test fun aWalkWrittenTwiceIsPushedOnceAtItsLongest() =
        assertEquals(listOf("b"), sessionsToPush(listOf(r("a", 0, 1935), r("b", 0, 1995)), emptySet()).map { it.uid })

    @Test fun nothingIsPushedWhenATwinWasPushedBefore() =
        assertEquals(emptyList<String>(), sessionsToPush(listOf(r("a", 0, 1935), r("b", 0, 1995)), setOf("a")).map { it.uid })

    @Test fun differentStartsOrTypesStaySeparate() =
        assertEquals(
            setOf("a", "c", "d"),
            sessionsToPush(listOf(r("a", 0, 600), r("c", 900, 1500), r("d", 0, 600, type = 56)), emptySet()).map { it.uid }.toSet(),
        )
}
