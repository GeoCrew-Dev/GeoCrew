package org.geocrew.core.network

import org.geocrew.core.model.GeoFeature
import java.io.File

/** Local storage the sync engine reads and writes. Implemented by the data layer. */
interface SyncStore {
    suspend fun dirty(projectId: String, limit: Int): List<GeoFeature>

    /** Clears the dirty flag if the local revision is still [f]'s version. */
    suspend fun markSynced(f: GeoFeature, seq: Long, by: String?)

    /** Applies a server revision unless an unsynced local edit is newer. Returns true if applied. */
    suspend fun mergeRemote(f: GeoFeature): Boolean

    suspend fun lastSeq(projectId: String): Long

    suspend fun setLastSeq(projectId: String, seq: Long)
}

/** Where attachment files live locally, by SHA-256. */
interface AttachmentStore {
    fun file(sha256: String): File
    fun exists(sha256: String): Boolean
}

data class SyncReport(
    val pushed: Int = 0,
    val pulled: Int = 0,
    /** Local edits replaced by a newer server revision. */
    val overwritten: Int = 0,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val seq: Long = 0,
    /** Non-fatal problems, e.g. a photo that failed to upload. */
    val warnings: List<String> = emptyList(),
)

/**
 * One round of the protocol v0 client flow (§4.5): upload attachments of
 * changed features, push local changes, then pull everything after the
 * last seen seq. Safe to run repeatedly; every step is idempotent.
 */
class SyncEngine(
    private val api: GeoCrewApi,
    private val store: SyncStore,
    private val files: AttachmentStore,
    private val workspaceId: String,
    private val projectId: String,
    private val memberId: String,
    private val batchSize: Int = 200,
) {
    suspend fun run(): SyncReport {
        val warnings = mutableListOf<String>()
        var pushed = 0
        var overwritten = 0
        var uploaded = 0
        var seq: Long

        // Push in batches until nothing dirty is left (or nothing progresses).
        var rounds = 0
        while (true) {
            val batch = store.dirty(projectId, batchSize)
            if (batch.isEmpty() || rounds++ > 10_000) break
            uploaded += uploadAttachments(batch, warnings)
            val r = api.push(workspaceId, projectId, batch)
            val byId = batch.associateBy { it.id }
            var progressed = false
            for (o in r.outcomes) {
                val local = byId[o.id] ?: continue
                when (o) {
                    is PushOutcome.Applied -> {
                        store.markSynced(local, o.seq, memberId)
                        pushed++
                        progressed = true
                    }
                    is PushOutcome.Duplicate -> {
                        store.markSynced(local, o.seq, local.sync.updatedBy ?: memberId)
                        progressed = true
                    }
                    is PushOutcome.Stale -> {
                        // The server has a newer revision; adopt it (protocol: last writer by version/time wins).
                        val current = o.current
                        if (current != null && store.mergeRemote(current)) {
                            overwritten++
                            progressed = true
                        } else {
                            warnings += "「${local.title ?: local.id}」与服务器冲突，暂未同步"
                        }
                    }
                }
            }
            if (batch.size < batchSize || !progressed) break
        }

        // Pull.
        var pulled = 0
        var downloaded = 0
        seq = store.lastSeq(projectId)
        while (true) {
            val page = api.pull(workspaceId, projectId, seq)
            for (f in page.features) {
                if (store.mergeRemote(f)) pulled++
            }
            downloaded += downloadAttachments(page.features, warnings)
            if (page.seq > seq) {
                seq = page.seq
                store.setLastSeq(projectId, seq)
            }
            if (!page.more || page.features.isEmpty()) break
        }
        return SyncReport(pushed, pulled, overwritten, uploaded, downloaded, seq, warnings)
    }

    private suspend fun uploadAttachments(batch: List<GeoFeature>, warnings: MutableList<String>): Int {
        var n = 0
        for (sha in batch.filterNot { it.deleted }.flatMap { it.photos }.distinct()) {
            if (!files.exists(sha)) continue
            try {
                if (!api.hasFile(workspaceId, sha)) {
                    api.uploadFile(workspaceId, sha, files.file(sha))
                    n++
                }
            } catch (e: ApiException) {
                if (e.isAuth || e.isForbidden) throw e
                warnings += "照片 ${sha.take(8)} 上传失败：${e.message}"
            }
        }
        return n
    }

    private suspend fun downloadAttachments(features: List<GeoFeature>, warnings: MutableList<String>): Int {
        var n = 0
        for (sha in features.filterNot { it.deleted }.flatMap { it.photos }.distinct()) {
            if (files.exists(sha)) continue
            try {
                api.downloadFile(workspaceId, sha, files.file(sha))
                n++
            } catch (e: ApiException) {
                if (e.status != 404) warnings += "照片 ${sha.take(8)} 下载失败：${e.message}"
            } catch (e: java.io.IOException) {
                warnings += "照片 ${sha.take(8)} 下载失败：${e.message}"
            }
        }
        return n
    }
}
