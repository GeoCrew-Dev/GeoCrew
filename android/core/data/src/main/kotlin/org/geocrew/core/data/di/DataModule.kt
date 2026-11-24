package org.geocrew.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.geocrew.core.data.db.GeoCrewDatabase
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): GeoCrewDatabase =
        Room.databaseBuilder(context, GeoCrewDatabase::class.java, GeoCrewDatabase.NAME)
            .addMigrations(*GeoCrewDatabase.MIGRATIONS)
            .build()

    @Provides fun projectDao(db: GeoCrewDatabase) = db.projects()
    @Provides fun featureDao(db: GeoCrewDatabase) = db.features()
    @Provides fun trackPointDao(db: GeoCrewDatabase) = db.trackPoints()
    @Provides fun commentDao(db: GeoCrewDatabase) = db.comments()

    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemUTC()
}
