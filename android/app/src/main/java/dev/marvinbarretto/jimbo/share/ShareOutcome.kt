package dev.marvinbarretto.jimbo.share

/**
 * What the door's answer means for one share. The door answers 200 even when it flags a capture
 * (would_refuse in shadow, refused live), so a 200 alone is not "arrived": only accepted or duplicate is.
 */
enum class ShareOutcome { ARRIVED, FLAGGED, REJECTED, RETRY;

    companion object {
        private val OUTCOME = Regex("\"outcome\"\\s*:\\s*\"(\\w+)\"")

        fun of(code: Int, body: String): ShareOutcome = when {
            code == 200 -> when (OUTCOME.find(body)?.groupValues?.get(1)) {
                "accepted", "duplicate" -> ARRIVED
                else -> FLAGGED
            }
            code == 408 || code == 429 || code >= 500 -> RETRY
            else -> REJECTED
        }
    }
}
