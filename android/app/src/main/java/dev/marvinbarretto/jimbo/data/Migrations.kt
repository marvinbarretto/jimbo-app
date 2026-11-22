package dev.marvinbarretto.jimbo.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migrations for [StepsDatabase].
 *
 * WHY THESE EXIST (they did not, until now)
 * The database was previously built with `fallbackToDestructiveMigration`, which
 * drops every table whenever the version changes. That is cheap until it isn't:
 * `gym_session_pushes` is the ledger of which Health Connect sessions have
 * already been posted to the Jimbo API, so wiping it locally makes the next
 * backfill re-post them all — server-side duplicates caused by a local reset.
 * `collector_settings` holds the user's collector toggles, which would silently
 * revert to defaults.
 *
 * CONCEPT MAPPING (JS)
 * A Room migration is a numbered, forward-only schema step, the same shape as a
 * Prisma or Knex migration: "from version N to N+1, run this SQL". Room checks
 * the resulting schema against the one generated from the `@Entity` classes and
 * throws on open if they differ — so the SQL below is not hand-written, it is
 * copied verbatim from `app/schemas/…/5.json`, which the compiler produces.
 */

/**
 * 4 → 5: add `screen_posts`, the on-device queue of Instagram posts harvested
 * from the screen. Purely additive — no existing table is touched, so nothing
 * that was already collected is at risk.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `screen_posts` (" +
                "`contentHash` TEXT NOT NULL, " +
                "`handle` TEXT NOT NULL, " +
                "`kindHint` TEXT NOT NULL, " +
                "`capturedAt` INTEGER NOT NULL, " +
                "`caption` TEXT, " +
                "`captionTruncated` INTEGER NOT NULL, " +
                "`altText` TEXT, " +
                "`locationName` TEXT, " +
                "`postedAtLabel` TEXT, " +
                "`attempts` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`contentHash`))"
        )
    }
}

/**
 * 5 → 6: sync on any connection. No schema change; it turns off the stored Wi-Fi-only flag, which nothing on the
 * phone can change and which held every event taken away from Wi-Fi until the next Wi-Fi (22 Nov 2026: a walk
 * from 13:48 to 15:32 reached Jimbo at 15:32).
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE `sync_constraints` SET `wifiOnly` = 0")
    }
}

/** Every migration, in order. Add new ones here as the schema grows. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_4_5, MIGRATION_5_6)
