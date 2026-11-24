package org.geocrew.core.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.geocrew.core.data.db.TrackPointDao
import org.geocrew.core.data.db.TrackPointEntity
import org.geocrew.core.data.db.toModel
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Props
import org.geocrew.core.model.TrackPoint
import org.geocrew.core.model.lengthMeters
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A track is a [FeatureKind.TRACK] feature whose geometry is rebuilt from
 * its recorded fixes; the fixes keep per-point time and accuracy for GPX.
 */
@Singleton
class TrackRepository @Inject constructor(
    private val features: FeatureRepository,
    private val points: TrackPointDao,
    private val clock: Clock,
) {
    suspend fun start(projectId: String): GeoFeature {
        val now = clock.instant()
        val name = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(now)
        return features.create(projectId, FeatureKind.TRACK, null) {
            this[Props.TITLE] = "轨迹 $name"
            this[Props.STARTED_AT] = now.toString()
        }
    }

    suspend fun append(trackId: String, p: TrackPoint) {
        points.insert(
            TrackPointEntity(
                trackId = trackId, idx = points.nextIndex(trackId),
                lng = p.position.lng, lat = p.position.lat, alt = p.position.alt,
                time = p.time.toEpochMilli(), accuracy = p.accuracy, speed = p.speed, bearing = p.bearing,
            ),
        )
    }

    suspend fun points(trackId: String): List<TrackPoint> = points.points(trackId).map { it.toModel() }

    fun observePoints(trackId: String): Flow<List<TrackPoint>> =
        points.observePoints(trackId).map { list -> list.map { it.toModel() } }

    /** Writes the recorded fixes into the feature geometry (call periodically and on stop). */
    suspend fun commit(trackId: String, finished: Boolean) {
        val pts = points(trackId)
        val geometry = if (pts.size >= 2) Geometry.LineString(pts.map { it.position }) else null
        features.edit(trackId, geometry) {
            geometry?.let { this[Props.LENGTH_M] = Math.round(it.lengthMeters() * 10) / 10.0 }
            if (finished) this[Props.ENDED_AT] = (pts.lastOrNull()?.time ?: clock.instant()).toString()
        }
        if (finished && pts.size < 2) features.delete(trackId)
    }
}
