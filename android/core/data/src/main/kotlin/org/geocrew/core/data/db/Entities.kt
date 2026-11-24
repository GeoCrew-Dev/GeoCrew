package org.geocrew.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    // Team link (all null when the project is local only).
    val serverUrl: String? = null,
    val workspaceId: String? = null,
    val workspaceName: String? = null,
    val memberId: String? = null,
    val nickname: String? = null,
    val role: String? = null,
    val lastSeq: Long = 0,
    /** [org.geocrew.core.model.ProjectConfig] as JSON (added in schema 2). */
    @ColumnInfo(defaultValue = "{}") val config: String = "{}",
    /** Epoch millis of the last time it was opened, 0 if never (added in schema 2). */
    @ColumnInfo(defaultValue = "0") val openedAt: Long = 0,
)

@Entity(
    tableName = "features",
    foreignKeys = [ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId"), Index("projectId", "dirty")],
)
data class FeatureEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val kind: String,
    /** GeoJSON geometry object, or null. */
    val geometry: String?,
    /** GeoJSON properties object (without "kind"). */
    val properties: String,
    val createdAt: Long,
    val version: Long,
    val updatedAt: Long,
    val updatedBy: String?,
    val deleted: Boolean,
    val seq: Long?,
    val dirty: Boolean,
    // Denormalised bounds for viewport queries.
    val west: Double?,
    val south: Double?,
    val east: Double?,
    val north: Double?,
)

@Entity(
    tableName = "track_points",
    primaryKeys = ["trackId", "idx"],
    foreignKeys = [ForeignKey(FeatureEntity::class, ["id"], ["trackId"], onDelete = ForeignKey.CASCADE)],
)
data class TrackPointEntity(
    val trackId: String,
    val idx: Int,
    val lng: Double,
    val lat: Double,
    val alt: Double?,
    val time: Long,
    val accuracy: Float?,
    val speed: Float?,
    val bearing: Float?,
)

/**
 * A comment on a record, or a team message when [featureId] is null
 * (protocol v0 §8). [seq] is 0 until the server has stored it.
 */
@Entity(
    tableName = "comments",
    primaryKeys = ["projectId", "id"],
    foreignKeys = [ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId", "featureId")],
)
data class CommentEntity(
    val projectId: String,
    val id: String,
    val featureId: String?,
    val body: String,
    val atLng: Double?,
    val atLat: Double?,
    val authorId: String,
    val nickname: String,
    val createdAt: Long,
    val seq: Long,
)
