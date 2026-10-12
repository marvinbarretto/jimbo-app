package dev.marvinbarretto.jimbo.nudge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// AC1: the inline reply POSTs {text, source:"in_app"} to /api/coach/food-log.
// AC4: a non-201 answer yields a retry outcome that keeps the typed text,
// never silence.
class FoodReplyHandlerTest {

    private class FakePoster(private val status: Int) : FoodLogPoster {
        val bodies = mutableListOf<String>()
        override fun postFoodLog(jsonBody: String): Pair<Int, String> {
            bodies += jsonBody
            return status to ""
        }
    }

    @Test
    fun `posts the typed text with source in_app`() {
        val poster = FakePoster(201)

        FoodReplyHandler(poster).handle("two eggs and toast")

        assertEquals(1, poster.bodies.size)
        // Compared as text: org.json is a stub in JVM unit tests.
        val body = poster.bodies[0].replace(" ", "")
        assertTrue(body, body.contains("\"text\":\"twoeggsandtoast\"") || poster.bodies[0].contains("\"text\":\"two eggs and toast\""))
        assertTrue(body, body.contains("\"source\":\"in_app\""))
    }

    @Test
    fun `a 201 is reported as logged`() {
        assertEquals(ReplyOutcome.Logged, FoodReplyHandler(FakePoster(201)).handle("a banana"))
    }

    @Test
    fun `a 500 estimation failure asks for a retry and keeps the text`() {
        val outcome = FoodReplyHandler(FakePoster(500)).handle("a banana")

        assertTrue(outcome is ReplyOutcome.RetryNeeded)
        assertEquals("a banana", (outcome as ReplyOutcome.RetryNeeded).text)
    }

    @Test
    fun `a thrown network error also asks for a retry`() {
        val poster = object : FoodLogPoster {
            override fun postFoodLog(jsonBody: String): Pair<Int, String> = throw java.io.IOException("offline")
        }

        val outcome = FoodReplyHandler(poster).handle("soup")

        assertEquals(ReplyOutcome.RetryNeeded("soup"), outcome)
    }
}
