package dev.marvinbarretto.jimbo.cap.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GymSessionPushDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: GymSessionPushEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM gym_session_pushes WHERE hcUid = :hcUid)")
    suspend fun exists(hcUid: String): Boolean

    @Query("SELECT hcUid FROM gym_session_pushes WHERE hcUid IN (:hcUids)")
    suspend fun pushedUids(hcUids: List<String>): List<String>
}
