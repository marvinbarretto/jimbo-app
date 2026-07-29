package dev.marvinbarretto.jimbo.telemetry

import java.security.MessageDigest

/**
 * InstagramPostHarvester — turns a settled node tree into post records.
 *
 * WHAT IT KEYS OFF, AND WHY
 * Measured on device 2026-07-29 across 6,654 nodes: **not one carried a view id**.
 * `viewIdResourceName` is null throughout Instagram, so the usual Android way of
 * finding a widget is unavailable. What is there instead is `contentDescription`,
 * and it is richer than view ids would have been:
 *
 *   ViewGroup desc="adamspubquiz posted a photo in Watford, United Kingdom on 6 days ago"
 *   ViewGroup desc="churchofclown posted a carousel in Church of Clown on 19 July"
 *   Button    desc="thefalteringfullback's story, 31 minutes ago"
 *
 * Handle, media kind, location and date, pre-parsed by Instagram. Better still,
 * these strings are their **screen-reader contract** — they cannot churn them
 * without breaking the app for blind users, which makes them a far more stable
 * key than an obfuscated view id ever was.
 *
 * CONCEPT MAPPING (JS)
 * This is a **scraper over a DOM you cannot use selectors on**. No ids, no
 * classes worth matching, so it works the way you would parse messy HTML: find
 * the container nodes by a text pattern, then walk their subtree for the parts.
 * `harvest()` is pure — tree in, data out — so tests feed it hand-built trees.
 *
 * ONE SETTLE IS NOT ONE POST
 * A settled screen is a scroll window, not a post. The 135-node tree measured on
 * device held a finished reel *and* the top of the next post. So this returns a
 * list, and the caller dedupes by [HarvestedPost.contentHash] — 93 sightings
 * collapsed to 53 unique posts in the pilot, a ratio of about 1.7:1.
 */

/** What kind of thing we harvested. Mirrors the `kind_hint` in the ingest contract. */
internal enum class PostKind {
    FEED_PHOTO,
    FEED_VIDEO,
    FEED_CAROUSEL,
    REEL,
    STORY,
    UNKNOWN;

    fun wireValue(): String = when (this) {
        FEED_PHOTO -> "feed_post"
        FEED_VIDEO -> "feed_post"
        FEED_CAROUSEL -> "feed_post"
        REEL -> "reel"
        STORY -> "story"
        UNKNOWN -> "unknown"
    }
}

internal data class HarvestedPost(
    val handle: String,
    val kind: PostKind,
    /** Caption with the leading handle stripped; null when the post has none. */
    val caption: String?,
    /**
     * True when Instagram itself collapsed the caption behind "… more".
     * Measured at 51% of captions, median 74 chars — and expanding one needs a
     * tap, which the posture forbids. The server uses this to route to vision
     * rather than trusting a stub.
     */
    val captionTruncated: Boolean,
    /**
     * Instagram's generated image description ("May be an image of text that
     * says …"). This is their own OCR, and for poster-announced events it often
     * carries the date and venue verbatim — a free deterministic signal that
     * lands in `instagram_posts.alt_text`.
     */
    val altText: String?,
    val locationName: String?,
    /** Date exactly as shown ("6 days ago", "19 July"). Resolved server-side. */
    val postedAtLabel: String?,
    /** Idempotency key → shortcode `screen-<hash>` server-side. */
    val contentHash: String,
)

// "handle posted a photo in Some Place on 6 days ago" / "handle posted a video 25 May"
private val FEED_CONTAINER = Regex(
    """^([A-Za-z0-9_.]+) posted an? (photo|video|carousel|reel)(?: in (.+?) on)? (.+)$"""
)

// "thefalteringfullback's story, 31 minutes ago"
private val STORY_CONTAINER = Regex("""^([A-Za-z0-9_.]+)'s story, (.+)$""")

/** Instagram's alt text always opens with one of these. Chrome descriptions do not. */
private val ALT_TEXT_PREFIXES = listOf(
    "May be an image of",
    "May be a graphic of",
    "Photo by",
    "Image may contain",
)

/** Caption nodes use this custom class — a stable, distinctive hook. */
private const val CAPTION_CLASS = "IgTextLayoutView"

/** How Instagram marks a collapsed caption. */
private val TRUNCATION_SUFFIX = Regex("""\s*…\s*mo?r?e?$""")

/**
 * Walk a settled tree and return every post visible on it.
 *
 * Refuses non-Instagram trees for the same reason [dumpTree] does — the active
 * window can belong to another app by the time a debounced harvest runs.
 */
internal fun harvestPosts(root: NodeView?, expectedPackage: String = INSTAGRAM_PACKAGE): List<HarvestedPost> {
    if (root == null || root.packageName != expectedPackage) return emptyList()

    // Flatten depth-first, which is document order — the order the nodes appear
    // down the screen. A post's caption is NOT nested inside its header container;
    // it is a later sibling. So parts are attached by position in this sequence,
    // the way you would parse a flat run of HTML rather than query a subtree.
    val flat = mutableListOf<NodeView>()
    flatten(root, flat)

    val posts = mutableListOf<PostBuilder>()
    // Alt text can arrive before its header — on a reel the media block is rendered
    // above the account row — so an orphan is held and given to the next post.
    var pendingAlt: String? = null

    for (node in flat) {
        val desc = node.contentDescription?.trim()

        val story = desc?.let { STORY_CONTAINER.matchEntire(it) }
        if (story != null) {
            posts += PostBuilder(
                handle = story.groupValues[1],
                kind = PostKind.STORY,
                dateLabel = story.groupValues[2],
            ).also { it.altText = pendingAlt.also { _ -> pendingAlt = null } }
            continue
        }

        val feed = desc?.let { FEED_CONTAINER.matchEntire(it) }
        if (feed != null) {
            posts += PostBuilder(
                handle = feed.groupValues[1],
                kind = mediaWordToKind(feed.groupValues[2]),
                dateLabel = feed.groupValues[4],
                locationName = feed.groupValues[3].ifBlank { null },
            ).also { it.altText = pendingAlt.also { _ -> pendingAlt = null } }
            continue
        }

        if (desc != null && ALT_TEXT_PREFIXES.any { desc.startsWith(it, ignoreCase = true) }) {
            val current = posts.lastOrNull()
            if (current != null && current.altText == null) current.altText = desc else pendingAlt = desc
            continue
        }

        if (node.className?.endsWith(CAPTION_CLASS) == true) {
            val text = node.text?.trim() ?: continue
            // The handle prefix is how a caption is tied to its own post rather
            // than the neighbouring one when two are on screen at once.
            val owner = posts.lastOrNull { text.startsWith("${it.handle} ") } ?: posts.lastOrNull()
            owner?.takeIf { it.caption == null }?.setCaption(text)
        }
    }

    return posts.map { it.build() }
}

private fun flatten(node: NodeView, into: MutableList<NodeView>) {
    into += node
    for (i in 0 until node.childCount) {
        flatten(node.child(i) ?: continue, into)
    }
}

private fun mediaWordToKind(word: String): PostKind = when (word) {
    "photo" -> PostKind.FEED_PHOTO
    "video" -> PostKind.FEED_VIDEO
    "carousel" -> PostKind.FEED_CAROUSEL
    "reel" -> PostKind.REEL
    else -> PostKind.UNKNOWN
}

/** Mutable accumulator — a post is assembled from nodes spread across the tree. */
private class PostBuilder(
    val handle: String,
    val kind: PostKind,
    val dateLabel: String,
    val locationName: String? = null,
) {
    var caption: String? = null
        private set
    var captionTruncated: Boolean = false
        private set
    var altText: String? = null

    fun setCaption(raw: String) {
        val withoutHandle = raw.removePrefix("$handle ").trim()
        captionTruncated = TRUNCATION_SUFFIX.containsMatchIn(withoutHandle)
        caption = TRUNCATION_SUFFIX.replace(withoutHandle, "").trim().takeIf { it.isNotBlank() }
    }

    fun build() = HarvestedPost(
        handle = handle,
        kind = kind,
        caption = caption,
        captionTruncated = captionTruncated,
        altText = altText,
        locationName = locationName,
        postedAtLabel = dateLabel,
        // Caption is deliberately out of the hash: Instagram re-renders the same
        // post with the caption collapsed or expanded depending on scroll position,
        // and hashing it would make one post look like two.
        contentHash = contentHash(handle, kind.wireValue(), dateLabel),
    )
}

/**
 * Stable identity for a post. Handle + kind + the date label as shown, which
 * together survive the same post being re-rendered across many settles.
 */
internal fun contentHash(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(parts.joinToString("|").toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}
