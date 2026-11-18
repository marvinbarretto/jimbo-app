package dev.marvinbarretto.jimbo.share

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareOutcomeTest {
    @Test fun acceptedArrives() = assertEquals(ShareOutcome.ARRIVED, ShareOutcome.of(200, """{"outcome":"accepted","capture_id":"cap_1"}"""))
    @Test fun duplicateArrives() = assertEquals(ShareOutcome.ARRIVED, ShareOutcome.of(200, """{"outcome": "duplicate"}"""))
    @Test fun wouldRefuseIsFlagged() = assertEquals(ShareOutcome.FLAGGED, ShareOutcome.of(200, """{"outcome":"would_refuse"}"""))
    @Test fun refusedIsFlagged() = assertEquals(ShareOutcome.FLAGGED, ShareOutcome.of(200, """{"outcome":"refused"}"""))
    @Test fun unreadable200IsFlagged() = assertEquals(ShareOutcome.FLAGGED, ShareOutcome.of(200, "ok"))
    @Test fun missingKeyIsRejected() = assertEquals(ShareOutcome.REJECTED, ShareOutcome.of(401, ""))
    @Test fun serverErrorRetries() = assertEquals(ShareOutcome.RETRY, ShareOutcome.of(503, ""))
    @Test fun rateLimitRetries() = assertEquals(ShareOutcome.RETRY, ShareOutcome.of(429, ""))
}
