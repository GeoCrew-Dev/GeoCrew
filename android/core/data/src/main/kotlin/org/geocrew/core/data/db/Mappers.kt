package org.geocrew.core.data.db

import kotlinx.serialization.json.jsonObject
import org.geocrew.core.formats.GeoJson
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Project
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.TeamLink
import org.geocrew.core.model.TrackPoint
import org.geocrew.core.model.bounds
import java.time.Instant

fun FeatureEntity.toModel(): GeoFeature = GeoFeature(
    id = id,
    projectId = projectId,
    kind = FeatureKind(kind),
    geometry = geometry?.let { GeoJson.decodeGeometry(GeoJson.json.parseToJsonElement(it)) },
    properties = GeoJson.json.parseToJsonElement(properties).jsonObject,
    createdAt = Instant.ofEpochMilli(createdAt),
    sync = SyncInfo(
        version = version,
        updatedAt = Instant.ofEpochMilli(updatedAt),
        updatedBy = updatedBy,
        deleted = deleted,
        seq = seq,
        dirty = dirty,
    ),
)

fun GeoFeature.toEntity(): FeatureEntity {
    val b = geometry?.bounds()
    return FeatureEntity(
        id = id,
        projectId = projectId,
        kind = kind.id,
        geometry = geometry?.let { GeoJson.encodeGeometry(it).toString() },
        properties = properties.toString(),
        createdAt = createdAt.toEpochMilli(),
        version = sync.version,
        updatedAt = sync.updatedAt.toEpochMilli(),
        updatedBy = sync.updatedBy,
        deleted = sync.deleted,
        seq = sync.seq,
        dirty = sync.dirty,
        west = b?.west, south = b?.south, east = b?.east, north = b?.north,
    )
}

fun ProjectEntity.toModel() = Project(
    id = id,
    name = name,
    createdAt = Instant.ofEpochMilli(createdAt),
    team = if (serverUrl != null && workspaceId != null && memberId != null) {
        TeamLink(serverUrl, workspaceId, workspaceName.orEmpty(), memberId, nickname.orEmpty(), role ?: "member", lastSeq)
    } else null,
)

fun Project.toEntity() = ProjectEntity(
    id = id, name = name, createdAt = createdAt.toEpochMilli(),
    serverUrl = team?.serverUrl, workspaceId = team?.workspaceId, workspaceName = team?.workspaceName,
    memberId = team?.memberId, nickname = team?.nickname, role = team?.role, lastSeq = team?.lastSeq ?: 0,
)

fun TrackPointEntity.toModel() = TrackPoint(LngLat(lng, lat, alt), Instant.ofEpochMilli(time), accuracy, speed, bearing)
