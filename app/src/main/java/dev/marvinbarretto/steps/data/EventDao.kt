package dev.marvinbarretto.steps.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<EventEntity>)

    @Query(
        """
        SELECT * FROM events
        WHERE syncedAt IS NULL AND deadLetter = 0
        ORDER BY createdAt ASC
        LIMIT :limit
        """
    )
    suspend fun pendingBatch(limit: Int): List<EventEntity>

    @Query("UPDATE events SET syncedAt = :syncedAt WHERE id IN (:ids)")
    suspend fun markSyncedAt(ids: List<String>, syncedAt: Long)

    suspend fun markSynced(ids: List<String>) {
        if (ids.isNotEmpty()) {
            markSyncedAt(ids, System.currentTimeMillis())
        }
    }

    @Query("UPDATE events SET attempts = attempts + 1 WHERE id IN (:ids)")
    suspend fun incrementAttempts(ids: List<String>)

    @Query("UPDATE events SET deadLetter = 1 WHERE id = :id")
    suspend fun markDeadLetter(id: String)

    @Query("SELECT COUNT(*) FROM events WHERE syncedAt IS NULL AND deadLetter = 0")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM events WHERE deadLetter = 1")
    suspend fun deadLetterCount(): Int
}
