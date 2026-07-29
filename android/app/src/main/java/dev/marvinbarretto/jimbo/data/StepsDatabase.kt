package dev.marvinbarretto.jimbo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        EventEntity::class,
        CollectorSettingEntity::class,
        SyncConstraintEntity::class,
        GymSessionPushEntity::class,
        ScreenPostEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class StepsDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao
    abstract fun collectorSettingDao(): CollectorSettingDao
    abstract fun syncConstraintDao(): SyncConstraintDao
    abstract fun gymSessionPushDao(): GymSessionPushDao
    abstract fun screenPostDao(): ScreenPostDao

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
                    // No destructive fallback. A missing migration should fail
                    // loudly on launch rather than quietly wipe gym_session_pushes
                    // (→ duplicate gym sessions server-side) and the user's
                    // collector toggles. See Migrations.kt.
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
