package org.geocrew.core.model

import java.time.Instant

/** A local field project ("工程"): a set of features, optionally shared with a team. */
data class Project(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val team: TeamLink? = null,
)

/** Connection of a project to a workspace on a self-hosted server. */
data class TeamLink(
    val serverUrl: String,
    val workspaceId: String,
    val workspaceName: String,
    val memberId: String,
    val nickname: String,
    val role: String,
    /** Last server seq merged locally (protocol v0 §4.4). */
    val lastSeq: Long = 0,
)

/** One recorded GPS fix of a track. */
data class TrackPoint(
    val position: LngLat,
    val time: Instant,
    val accuracy: Float? = null,
    val speed: Float? = null,
    val bearing: Float? = null,
)
