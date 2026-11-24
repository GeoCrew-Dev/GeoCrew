package org.geocrew.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A field project ("工程"), the app's top-level container: its features,
 * tracks and photos, its own map setup, and at most one workspace it syncs
 * with. Account-like things (map keys, the tile source library, display
 * preferences) are global and live in the app settings instead.
 */
data class Project(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val team: TeamLink? = null,
    val config: ProjectConfig = ProjectConfig(),
    /** Last time the project was opened on this device; null if never. */
    val openedAt: Instant? = null,
)

/**
 * Per-project settings, stored as JSON so new fields need no database
 * migration. Unknown fields are ignored when reading, so older app versions
 * can open projects written by newer ones. Also the `project.json` of an
 * exported project package.
 */
@Serializable
data class ProjectConfig(
    /** Base map for this project; null means the app-wide default. */
    val baseSourceId: String? = null,
    /** Where the map was when the project was last closed. */
    val view: MapViewport? = null,
    /** Free-text description, e.g. survey area or purpose. */
    val description: String = "",
)

@Serializable
data class MapViewport(val lng: Double, val lat: Double, val zoom: Double, val bearing: Double = 0.0)

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
