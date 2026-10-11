package dev.marvinbarretto.jimbo.nudge

interface FoodLogPoster {
    fun postFoodLog(jsonBody: String): Pair<Int, String>
}

sealed class ReplyOutcome {
    object Logged : ReplyOutcome()
    /** The typed text rides along so the retry notification can resend it. */
    data class RetryNeeded(val text: String) : ReplyOutcome()
}

/** Turns a notification's inline reply into one /api/coach/food-log entry. */
class FoodReplyHandler(private val poster: FoodLogPoster) {

    fun handle(text: String): ReplyOutcome {
        val body = """{"text":"${escape(text)}","source":"in_app"}"""
        return try {
            if (poster.postFoodLog(body).first == 201) ReplyOutcome.Logged else ReplyOutcome.RetryNeeded(text)
        } catch (e: java.io.IOException) {
            ReplyOutcome.RetryNeeded(text)
        }
    }

    // Hand-rolled: org.json is a stub in JVM unit tests.
    private fun escape(s: String): String = buildString {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}
