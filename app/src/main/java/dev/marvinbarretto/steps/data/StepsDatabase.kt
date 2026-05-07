package dev.marvinbarretto.steps.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        EventEntity::class,
        CollectorSettingEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class StepsDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao
    abstract fun collectorSettingDao(): CollectorSettingDao

    companion object {
        @Volatile
        private var INSTANCE: StepsDatabase? = null

        fun getInstance(context: Context): StepsDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StepsDatabase::class.java,
                    "steps_db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
