package org.geocrew.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProjectEntity::class, FeatureEntity::class, TrackPointEntity::class, CommentEntity::class],
    version = 6,
    exportSchema = true,
)
abstract class GeoCrewDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun features(): FeatureDao
    abstract fun trackPoints(): TrackPointDao
    abstract fun comments(): CommentDao

    companion object {
        const val NAME = "geocrew.db"

        /** Schema 2: per-project configuration and "recently opened". */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN config TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE projects ADD COLUMN openedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Schema 3: team discussion (protocol v0 §8). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `comments` (`projectId` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                        "`featureId` TEXT, `body` TEXT NOT NULL, `atLng` REAL, `atLat` REAL, `authorId` TEXT NOT NULL, " +
                        "`nickname` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `seq` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`projectId`, `id`), FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_comments_projectId_featureId` ON `comments` (`projectId`, `featureId`)")
            }
        }

        /** Schema 4: urgent team messages (protocol v0 §8.1). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE comments ADD COLUMN urgent INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Schema 5: roll calls and their answers (protocol v0 §8.2). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE comments ADD COLUMN rollCall INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE comments ADD COLUMN answerTo TEXT")
            }
        }

        /** Schema 6: check-outs with an expected return time (protocol v0 §8.3). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE comments ADD COLUMN backBy INTEGER")
                db.execSQL("ALTER TABLE comments ADD COLUMN backFrom TEXT")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
    }
}
