package dev.marvinbarretto.jimbo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CollectorSettingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: CollectorSettingEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefaults(settings: List<CollectorSettingEntity>)

    @Query("SELECT * FROM collector_settings")
    suspend fun getAll(): List<CollectorSettingEntity>

    @Query("SELECT enabled FROM collector_settings WHERE collectorId = :collectorId")
    suspend fun isEnabled(collectorId: String): Boolean?
}
