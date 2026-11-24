package org.geocrew.core.data.team

import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.PhotoStore
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.network.AttachmentStore
import org.geocrew.core.network.SyncStore
import java.io.File
import javax.inject.Inject

/** The sync engine's view of the local database. */
class RoomSyncStore @Inject constructor(
    private val features: FeatureRepository,
    private val projects: ProjectRepository,
) : SyncStore {
    override suspend fun dirty(projectId: String, limit: Int) = features.dirty(projectId, limit)
    override suspend fun markSynced(f: GeoFeature, seq: Long, by: String?) = features.markSynced(f, seq, by)
    override suspend fun mergeRemote(f: GeoFeature) = features.mergeRemote(f)
    override suspend fun lastSeq(projectId: String) = projects.get(projectId)?.team?.lastSeq ?: 0L
    override suspend fun setLastSeq(projectId: String, seq: Long) = projects.setLastSeq(projectId, seq)
}

/** Photos are already stored under their SHA-256, the attachment id of the protocol. */
class PhotoAttachments @Inject constructor(private val photos: PhotoStore) : AttachmentStore {
    override fun file(sha256: String): File = photos.file(sha256)
    override fun exists(sha256: String) = photos.exists(sha256)
}
