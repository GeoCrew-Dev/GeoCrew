package org.geocrew.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ProjectEntity::class, FeatureEntity::class, TrackPointEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class GeoCrewDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun features(): FeatureDao
    abstract fun trackPoints(): TrackPointDao

    companion object {
        const val NAME = "geocrew.db"
    }
}
