package org.geocrew.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProjectEntity::class, FeatureEntity::class, TrackPointEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class GeoCrewDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun features(): FeatureDao
    abstract fun trackPoints(): TrackPointDao

    companion object {
        const val NAME = "geocrew.db"

        /** Schema 2: per-project configuration and "recently opened". */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN config TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE projects ADD COLUMN openedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
