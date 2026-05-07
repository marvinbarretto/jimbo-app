package dev.marvinbarretto.steps.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

data class CollectorEventSummary(
    val collector: String,
    val lastSeenAt: Long,
    val eventCount: Int
)

data class CollectorRecentSummary(
    val collector: String,
    val lastSeenAt: Long?,
    val eventCountLast24h: Int
)

data class DeadLetterEventSummary(
    val id: String,
    val collector: String,
    val type: String,
    val ts: String,
    val payload: String?,
    val value: Double?,
    val unit: String?,
    val source: String?,
    val attempts: Int
)

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

    @Query(
        """
        SELECT
            collector,
            MAX(createdAt) AS lastSeenAt,
            SUM(CASE WHEN createdAt >= :since THEN 1 ELSE 0 END) AS eventCountLast24h
        FROM events
        GROUP BY collector
        ORDER BY collector ASC
        """
    )
    suspend fun collectorRecentSummaries(since: Long): List<CollectorRecentSummary>

    @Query(
        """
        SELECT collector, MAX(createdAt) AS lastSeenAt, COUNT(*) AS eventCount
        FROM events
        GROUP BY collector
        ORDER BY collector ASC
        """
    )
    suspend fun collectorSummaries(): List<CollectorEventSummary>

    @Query(
        """
        SELECT
            COALESCE(SUM(
                LENGTH(id) +
                LENGTH(collector) +
                LENGTH(type) +
                LENGTH(ts) +
                COALESCE(LENGTH(tsEnd), 0) +
                COALESCE(LENGTH(unit), 0) +
                COALESCE(LENGTH(source), 0) +
                COALESCE(LENGTH(payload), 0)
            ), 0)
        FROM events
        WHERE syncedAt IS NULL AND deadLetter = 0
        """
    )
    suspend fun pendingQueuedBytes(): Long

    @Query(
        """
        SELECT id, collector, type, ts, payload, value, unit, source, attempts
        FROM events
        WHERE deadLetter = 1
        ORDER BY createdAt DESC
        """
    )
    suspend fun deadLetterEvents(): List<DeadLetterEventSummary>

    @Query("UPDATE events SET deadLetter = 0 WHERE id = :id")
    suspend fun retryDeadLetter(id: String)

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteEvent(id: String)
}
