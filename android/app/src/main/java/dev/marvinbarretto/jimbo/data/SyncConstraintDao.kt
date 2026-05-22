package dev.marvinbarretto.jimbo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncConstraintDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncConstraintEntity)

    @Query("SELECT * FROM sync_constraints WHERE id = :id LIMIT 1")
    suspend fun get(id: String = SyncConstraintEntity.DEFAULT_ID): SyncConstraintEntity?
}
