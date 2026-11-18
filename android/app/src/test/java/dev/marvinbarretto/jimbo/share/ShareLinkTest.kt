package dev.marvinbarretto.jimbo.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinkTest {
    @Test fun xShareStripsTrackingAndKeepsFullTextAsBody() {
        val text = "Great thread https://x.com/someone/status/123?s=20&t=abc"
        val item = ShareLink.extract(text)!!
        assertEquals("https://x.com/someone/status/123", item.title)
        assertEquals(text, item.body)
    }

    @Test fun twitterHostIsStrippedToo() =
        assertEquals("https://twitter.com/a/status/1", ShareLink.clean("https://twitter.com/a/status/1?s=46&t=zz"))

    @Test fun otherHostsKeepTheirQuery() =
        assertEquals("https://example.com/a?id=7&utm=x", ShareLink.clean("https://example.com/a?id=7&utm=x"))

    @Test fun lookalikeHostIsNotStripped() =
        assertEquals("https://notx.com/a?s=1", ShareLink.clean("https://notx.com/a?s=1"))

    @Test fun fragmentSurvivesStripping() =
        assertEquals("https://x.com/a/status/1#top", ShareLink.clean("https://x.com/a/status/1?s=20#top"))

    @Test fun trailingPunctuationIsDropped() =
        assertEquals("https://example.com/post", ShareLink.extract("see (https://example.com/post).")!!.title)

    @Test fun firstUrlWins() =
        assertEquals("https://a.com/1", ShareLink.extract("https://a.com/1 and https://b.com/2")!!.title)

    @Test fun subjectJoinsBodyAndCanSupplyUrl() {
        val item = ShareLink.extract("read this", "Title https://a.com/x")!!
        assertEquals("https://a.com/x", item.title)
        assertEquals("Title https://a.com/x\nread this", item.body)
    }

    @Test fun noUrlUsesTrimmedTextCappedAt500() {
        assertEquals("hello", ShareLink.extract("  hello  ")!!.title)
        assertEquals(500, ShareLink.extract("x".repeat(900))!!.title.length)
    }

    @Test fun emptyShareIsNull() {
        assertNull(ShareLink.extract(null))
        assertNull(ShareLink.extract("   ", " "))
    }

    @Test fun doorJsonEscapesAndNullBody() {
        assertEquals(
            """{"source":"url","source_ids":["u"],"title":"u","body":null}""",
            ShareLink.doorJson(SharedItem("u", null)),
        )
        assertEquals(
            """{"source":"url","source_ids":["u"],"title":"u","body":"a\"b\nc"}""",
            ShareLink.doorJson(SharedItem("u", "a\"b\nc")),
        )
    }

    private val item = SharedItem("https://example.com/a", "Subject\nhttps://example.com/a")

    @Test fun noteAndResearch() = assertEquals(
        "Why (Marvin): good for the Watford idea\nResearch: yes\n---\nSubject\nhttps://example.com/a",
        ShareLink.withNote(item, "good for the Watford idea", true).body,
    )

    @Test fun noteOnly() = assertEquals(
        "Why (Marvin): because\n---\nSubject\nhttps://example.com/a",
        ShareLink.withNote(item, "  because \n", false).body,
    )

    @Test fun researchOnly() = assertEquals(
        "Research: yes\n---\nSubject\nhttps://example.com/a",
        ShareLink.withNote(item, "   ", true).body,
    )

    @Test fun neitherLeavesBodyAlone() = assertEquals(item, ShareLink.withNote(item, "", false))

    @Test fun titleStaysBareUrl() =
        assertEquals("https://example.com/a", ShareLink.withNote(item, "x", true).title)

    @Test fun noteSurvivesJsonEscaping() {
        val json = ShareLink.doorJson(ShareLink.withNote(item, "say \"hi\"", false))
        assert(json.contains("Why (Marvin): say \\\"hi\\\"\\n---"))
    }
}
