package dev.marvinbarretto.jimbo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One Instagram post harvested off the screen, queued for LocalShout.
 *
 * CONCEPT MAPPING (JS)
 * Room is an **ORM over SQLite** — this class is the table definition and the
 * row type at once, like a Prisma model. `@PrimaryKey` on [contentHash] is what
 * makes the queue self-deduplicating: inserting with OnConflictStrategy.IGNORE
 * means the same post seen across twenty settles writes exactly one row, with no
 * "have I seen this?" bookkeeping in the caller.
 *
 * WHY A QUEUE AND NOT A DIRECT POST
 * The phone is often offline or on a bad connection when he is out. Writing to
 * SQLite first and draining later is the same reason a web app queues mutations
 * in IndexedDB rather than firing fetch() straight from a click handler: the
 * capture must not be lost because the network was not there at that instant.
 *
 * Rows are deleted once the server confirms them, so the table stays small.
 */
@Entity(tableName = "screen_posts")
data class ScreenPostEntity(
    /** SHA-1 prefix of (handle, kind, date-label) — becomes `screen-<hash>` server-side. */
    @PrimaryKey val contentHash: String,
    val handle: String,
    /** 'story' | 'feed_post' | 'reel' | 'unknown' */
    val kindHint: String,
    val capturedAt: Long,
    val caption: String?,
    val captionTruncated: Boolean,
    val altText: String?,
    val locationName: String?,
    /** Date exactly as Instagram showed it; resolved to a timestamp server-side. */
    val postedAtLabel: String?,
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
