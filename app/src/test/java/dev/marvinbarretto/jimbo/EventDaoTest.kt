package dev.marvinbarretto.jimbo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.marvinbarretto.jimbo.data.EventEntity
import dev.marvinbarretto.jimbo.data.StepsDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventDaoTest {

    private lateinit var database: StepsDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun pendingBatchReturnsOldestUnsyncedEvents() = runBlocking {
        val dao = database.eventDao()
        dao.insertAll(
            listOf(
                event(id = "a", createdAt = 10),
                event(id = "b", createdAt = 20, deadLetter = true),
                event(id = "c", createdAt = 30),
                event(id = "d", createdAt = 40, syncedAt = 100)
            )
        )

        val pending = dao.pendingBatch(limit = 10)

        assertEquals(listOf("a", "c"), pending.map { it.id })
    }

    @Test
    fun markSyncedAndCountersReflectQueueState() = runBlocking {
        val dao = database.eventDao()
        dao.insertAll(listOf(event(id = "a"), event(id = "b")))

        dao.markSynced(listOf("a"))

        val pending = dao.pendingBatch(limit = 10)

        assertEquals(listOf("b"), pending.map { it.id })
        assertEquals(1, dao.pendingCount())
        assertEquals(0, dao.deadLetterCount())
    }

    @Test
    fun incrementAttemptsAndDeadLetterArePersisted() = runBlocking {
        val dao = database.eventDao()
        dao.insertAll(listOf(event(id = "retry-me", attempts = 9)))

        dao.incrementAttempts(listOf("retry-me"))
        dao.markDeadLetter("retry-me")

        val pending = dao.pendingBatch(limit = 10)

        assertEquals(0, pending.size)
        assertEquals(0, dao.pendingCount())
        assertEquals(1, dao.deadLetterCount())
    }

    private fun event(
        id: String,
        createdAt: Long = 1,
        attempts: Int = 0,
        syncedAt: Long? = null,
        deadLetter: Boolean = false
    ): EventEntity = EventEntity(
        id = id,
        collector = "test",
        type = "steps",
        ts = "2026-05-07T13:00:00Z",
        tsEnd = null,
        value = 123.0,
        unit = "count",
        source = "test-source",
        payload = null,
        createdAt = createdAt,
        syncedAt = syncedAt,
        attempts = attempts,
        deadLetter = deadLetter
    )
}
