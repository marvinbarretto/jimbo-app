package dev.marvinbarretto.jimbo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScreenPostDao {

    /**
     * IGNORE, not REPLACE. The same post is harvested on every settle it stays
     * on screen — measured at 1.7 sightings per post — and REPLACE would reset
     * `attempts` each time, so a post that always fails to send would retry
     * forever instead of dead-lettering.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(posts: List<ScreenPostEntity>): List<Long>

    @Query("SELECT * FROM screen_posts ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pendingBatch(limit: Int): List<ScreenPostEntity>

    @Query("DELETE FROM screen_posts WHERE contentHash IN (:hashes)")
    suspend fun deleteSent(hashes: List<String>)

    @Query("UPDATE screen_posts SET attempts = attempts + 1 WHERE contentHash IN (:hashes)")
    suspend fun incrementAttempts(hashes: List<String>)

    /**
     * Give up on posts that have failed too often. Without this a permanently
     * malformed record would block the head of the queue forever — the drain is
     * ordered oldest-first, so one poison row starves everything behind it.
     */
    @Query("DELETE FROM screen_posts WHERE attempts >= :maxAttempts")
    suspend fun dropExhausted(maxAttempts: Int): Int

    @Query("SELECT COUNT(*) FROM screen_posts")
    suspend fun pendingCount(): Int
}
