package dev.marvinbarretto.jimbo.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Harvester tests. The fixtures are transcribed from trees actually measured on
 * device on 2026-07-29 — real handles, real captions, real contentDescriptions —
 * so a passing test means the parser handles Instagram as it really is, not as
 * we imagined it.
 */
class InstagramPostHarvesterTest {

    private class Node(
        override val packageName: String? = INSTAGRAM_PACKAGE,
        override val className: String? = "android.view.ViewGroup",
        override val viewId: String? = null,
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val isClickable: Boolean = false,
        private val children: List<NodeView?> = emptyList(),
    ) : NodeView {
        override val childCount: Int get() = children.size
        override fun child(index: Int): NodeView? = children[index]
    }

    private fun caption(text: String) = Node(
        className = "com.instagram.ui.text.IgTextLayoutView",
        text = text,
    )

    @Test
    fun `harvests handle, kind, location and date from the container description`() {
        val tree = Node(
            children = listOf(
                Node(contentDescription = "adamspubquiz posted a photo in Watford, United Kingdom on 6 days ago")
            )
        )

        val posts = harvestPosts(tree)

        assertEquals(1, posts.size)
        assertEquals("adamspubquiz", posts[0].handle)
        assertEquals(PostKind.FEED_PHOTO, posts[0].kind)
        assertEquals("Watford, United Kingdom", posts[0].locationName)
        assertEquals("6 days ago", posts[0].postedAtLabel)
    }

    @Test
    fun `handles a post with no location`() {
        val tree = Node(children = listOf(Node(contentDescription = "aeg_presents posted a photo 1 day ago")))

        val post = harvestPosts(tree).single()

        assertNull(post.locationName)
        assertEquals("1 day ago", post.postedAtLabel)
    }

    @Test
    fun `maps every observed media word to a kind`() {
        val descs = mapOf(
            "a posted a photo 1 day ago" to PostKind.FEED_PHOTO,
            "b posted a video 25 May" to PostKind.FEED_VIDEO,
            "c posted a carousel 4 days ago" to PostKind.FEED_CAROUSEL,
            "d posted a reel 2 days ago" to PostKind.REEL,
        )

        descs.forEach { (desc, expected) ->
            val post = harvestPosts(Node(children = listOf(Node(contentDescription = desc)))).single()
            assertEquals(desc, expected, post.kind)
        }
    }

    @Test
    fun `absolute dates parse as readily as relative ones`() {
        val tree = Node(
            children = listOf(Node(contentDescription = "churchofclown posted a carousel in Church of Clown on 19 July"))
        )

        val post = harvestPosts(tree).single()

        assertEquals("Church of Clown", post.locationName)
        assertEquals("19 July", post.postedAtLabel)
    }

    @Test
    fun `attaches the caption and strips the handle prefix`() {
        val tree = Node(
            children = listOf(
                Node(contentDescription = "daypartyuk posted a photo 2 days ago"),
                caption("daypartyuk Saturday 22nd August • Zoo Watford • 3PM–8PM • Book now before tickets sell out!"),
            )
        )

        val post = harvestPosts(tree).single()

        assertEquals(
            "Saturday 22nd August • Zoo Watford • 3PM–8PM • Book now before tickets sell out!",
            post.caption,
        )
        assertFalse(post.captionTruncated)
    }

    @Test
    fun `flags a caption Instagram collapsed behind more`() {
        val tree = Node(
            children = listOf(
                Node(contentDescription = "the_zoo_watford posted a photo 1 day ago"),
                caption("the_zoo_watford Introducing at The Zoo 🦓… more"),
            )
        )

        val post = harvestPosts(tree).single()

        assertTrue(post.captionTruncated)
        // The "… more" affordance itself is not part of the caption.
        assertEquals("Introducing at The Zoo 🦓", post.caption)
    }

    @Test
    fun `captures instagram's generated alt text as the free OCR signal`() {
        val tree = Node(
            children = listOf(
                Node(contentDescription = "madsquirrelwatford posted a photo 2 days ago"),
                Node(
                    className = "android.widget.ImageView",
                    contentDescription = "May be an image of text that says 'LIVE MUSIC SATURDAY 8PM THE MAD SQUIRREL'",
                ),
                caption("madsquirrelwatford KIDS EAT FREE🫨🍕… more"),
            )
        )

        val post = harvestPosts(tree).single()

        // The caption is a useless stub, but the poster's text came through free.
        assertTrue(post.captionTruncated)
        assertTrue(post.altText!!.contains("LIVE MUSIC SATURDAY 8PM"))
    }

    @Test
    fun `ignores chrome image descriptions that are not alt text`() {
        val tree = Node(
            children = listOf(
                Node(contentDescription = "pathan056056 posted a video 25 May"),
                Node(className = "android.widget.ImageView", contentDescription = "Profile picture of pathan056056"),
                Node(className = "android.widget.ImageView", contentDescription = "More actions for this post"),
            )
        )

        assertNull(harvestPosts(tree).single().altText)
    }

    @Test
    fun `harvests a story with its handle and age`() {
        // The real story tree: no caption anywhere, content lives in pixels.
        val tree = Node(
            children = listOf(
                Node(
                    contentDescription = "thefalteringfullback's story, 31 minutes ago",
                    children = listOf(
                        Node(className = "android.widget.TextView", text = "thefalteringfullback"),
                        Node(className = "android.widget.TextView", text = "31m"),
                    ),
                ),
                Node(className = "android.widget.ImageView", contentDescription = "Like Story"),
            )
        )

        val post = harvestPosts(tree).single()

        assertEquals("thefalteringfullback", post.handle)
        assertEquals(PostKind.STORY, post.kind)
        assertEquals("31 minutes ago", post.postedAtLabel)
        assertNull(post.caption)
    }

    @Test
    fun `one settle spanning two posts yields two records`() {
        // The measured 135-node tree: a finished reel plus the top of the next post.
        val tree = Node(
            children = listOf(
                Node(contentDescription = "pathan056056 posted a video 25 May"),
                Node(contentDescription = "aeg_presents posted a photo 1 day ago"),
            )
        )

        val posts = harvestPosts(tree)

        assertEquals(2, posts.size)
        assertEquals(listOf("pathan056056", "aeg_presents"), posts.map { it.handle })
    }

    @Test
    fun `the same post seen twice hashes identically so it dedupes`() {
        val first = harvestPosts(
            Node(children = listOf(Node(contentDescription = "churchofclown posted a photo 3 days ago")))
        ).single()
        // Same post re-rendered further up the screen, caption now expanded.
        val second = harvestPosts(
            Node(
                children = listOf(
                    Node(contentDescription = "churchofclown posted a photo 3 days ago"),
                    caption("churchofclown The Cure Ate Her | Molly McLean Saturday, August 29"),
                )
            )
        ).single()

        assertEquals(first.contentHash, second.contentHash)
    }

    @Test
    fun `different posts from one account hash differently`() {
        val a = harvestPosts(
            Node(children = listOf(Node(contentDescription = "churchofclown posted a photo 3 days ago")))
        ).single()
        val b = harvestPosts(
            Node(children = listOf(Node(contentDescription = "churchofclown posted a photo 5 days ago")))
        ).single()

        assertTrue(a.contentHash != b.contentHash)
    }

    @Test
    fun `refuses a tree belonging to another app`() {
        val shade = Node(
            packageName = "com.android.systemui",
            children = listOf(Node(packageName = "com.android.systemui", text = "overdraft notice")),
        )

        assertEquals(emptyList<HarvestedPost>(), harvestPosts(shade))
    }

    @Test
    fun `a tree with no posts yields nothing rather than a junk record`() {
        val tree = Node(
            children = listOf(
                Node(className = "android.widget.TextView", text = "Suggested for you"),
                Node(className = "android.widget.Button", contentDescription = "Like"),
            )
        )

        assertEquals(emptyList<HarvestedPost>(), harvestPosts(tree))
    }
}
