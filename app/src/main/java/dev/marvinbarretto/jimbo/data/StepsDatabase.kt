package dev.marvinbarretto.jimbo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        EventEntity::class,
        CollectorSettingEntity::class,
        SyncConstraintEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class StepsDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao
    abstract fun collectorSettingDao(): CollectorSettingDao
    abstract fun syncConstraintDao(): SyncConstraintDao

    companion object {
        @Volatile
        private var INSTANCE: StepsDatabase? = null

        fun getInstance(context: Context): StepsDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StepsDatabase::class.java,
                    "jimbo_db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
