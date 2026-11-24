package org.geocrew.core.data.repo

import org.geocrew.core.data.db.FeatureDao
import javax.inject.Inject
import javax.inject.Singleton

/** Housekeeping for files the database no longer points at. */
@Singleton
class StorageMaintenance @Inject constructor(
    private val features: FeatureDao,
    private val photos: PhotoStore,
) {
    /**
     * Removes photo files no feature references any more, e.g. after a
     * project was deleted or a photo was taken off a record. Tombstones
     * still count as references until they are purged, so an undo or a
     * sync conflict can bring the photo back.
     */
    suspend fun sweepPhotos(): Int {
        val keep = HashSet<String>()
        for (props in features.allProperties()) {
            SHA256.findAll(props).forEach { keep += it.value }
        }
        return photos.sweep(keep)
    }

    private companion object {
        // Photos are content-addressed; any hash in a record's properties keeps its file.
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
