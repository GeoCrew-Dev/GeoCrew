package org.geocrew.core.network

/**
 * Local storage for comments (protocol v0 §8). A comment the server has not
 * stored yet has `seq == 0`; it is posted on the next [CommentSync] run, so
 * people can write while out of signal.
 */
interface CommentStore {
    suspend fun pending(projectId: String): List<Comment>

    /** Stores a server copy, replacing a pending local one with the same id. */
    suspend fun save(projectId: String, c: Comment)

    suspend fun remove(projectId: String, id: String)

    suspend fun commentSeq(projectId: String): Long

    suspend fun setCommentSeq(projectId: String, seq: Long)
}

data class CommentSyncReport(val posted: Int = 0, val received: Int = 0, val removed: Int = 0, val failed: Int = 0)

/**
 * Posts pending comments, then pulls everything after the saved cursor.
 * Posting is idempotent by comment id, so a run cut short is simply
 * repeated. A server without the `comments` capability answers 404, which
 * the caller treats as "no comments here".
 */
class CommentSync(
    private val api: GeoCrewApi,
    private val store: CommentStore,
    private val workspaceId: String,
    private val projectId: String,
) {
    suspend fun run(): CommentSyncReport {
        var posted = 0
        var failed = 0
        for (c in store.pending(projectId)) {
            try {
                store.save(projectId, api.addComment(workspaceId, c.id, c.body, c.featureId, c.at))
                posted++
            } catch (e: ApiException) {
                // The server refused this comment for good (too long, or its id
                // was taken); keeping it would block every later sync.
                if (e.status in 400..499 && !e.isAuth && e.status != 404 && e.status != 429) {
                    store.remove(projectId, c.id)
                    failed++
                } else {
                    throw e
                }
            }
        }
        var received = 0
        var removed = 0
        var since = store.commentSeq(projectId)
        do {
            val page = api.comments(workspaceId, since)
            for (c in page.comments) {
                if (c.deleted) {
                    store.remove(projectId, c.id)
                    removed++
                } else {
                    store.save(projectId, c)
                    received++
                }
            }
            since = page.seq
            store.setCommentSeq(projectId, since)
        } while (page.more && page.comments.isNotEmpty())
        return CommentSyncReport(posted, received, removed, failed)
    }
}
