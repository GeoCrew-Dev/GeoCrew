package org.geocrew.core.model

import java.time.Duration
import java.time.Instant

/**
 * Time on a track: when it ran, how long was spent walking, and where the
 * walker stood still. In field mapping a stop is usually an outcrop being
 * looked at, so stops with no record near them are worth a second look.
 */
data class TrackStats(
    val start: Instant,
    val end: Instant,
    /** Time between fixes, less stops and gaps with no fixes. */
    val moving: Duration,
    /** Gaps of more than [GAP] between two fixes, e.g. recording paused. */
    val unrecorded: Duration,
    val lengthM: Double,
    val stops: List<Stop>,
) {
    val total: Duration get() = Duration.between(start, end)

    /** Average walking speed in km/h, or null when there was no moving time. */
    val movingKmh: Double? get() = moving.seconds.takeIf { it > 0 }?.let { lengthM / it * 3.6 }

    data class Stop(val position: LngLat, val from: Instant, val to: Instant) {
        val duration: Duration get() = Duration.between(from, to)
    }

    companion object {
        /** Staying within this many metres of where a stop began counts as standing still. */
        const val STOP_RADIUS_M = 30.0
        val MIN_STOP: Duration = Duration.ofMinutes(5)
        val GAP: Duration = Duration.ofMinutes(10)

        /**
         * Stats for [fixes] (in any order); null with fewer than two. A stop
         * is a run of fixes all within [radiusM] of its first one that lasts
         * at least [minStop]; its position is their mean.
         */
        fun of(
            fixes: List<TrackPoint>,
            radiusM: Double = STOP_RADIUS_M,
            minStop: Duration = MIN_STOP,
            gap: Duration = GAP,
        ): TrackStats? {
            val pts = fixes.sortedBy { it.time }
            if (pts.size < 2) return null
            val stops = ArrayList<Stop>()
            var i = 0
            while (i < pts.size) {
                var j = i + 1
                // A gap ends a stop: nothing says the walker stayed put while not recording.
                while (j < pts.size && Geodesy.distance(pts[i].position, pts[j].position) <= radiusM &&
                    Duration.between(pts[j - 1].time, pts[j].time) <= gap
                ) j++
                val from = pts[i].time
                val to = pts[j - 1].time
                if (Duration.between(from, to) >= minStop) {
                    val run = pts.subList(i, j)
                    stops += Stop(
                        LngLat(run.sumOf { it.position.lng } / run.size, run.sumOf { it.position.lat } / run.size),
                        from, to,
                    )
                    i = j
                } else {
                    i++
                }
            }
            var unrecorded = Duration.ZERO
            for (k in 1 until pts.size) {
                val d = Duration.between(pts[k - 1].time, pts[k].time)
                if (d > gap) unrecorded += d
            }
            val total = Duration.between(pts.first().time, pts.last().time)
            val stopped = stops.fold(Duration.ZERO) { acc, s -> acc + s.duration }
            val moving = (total - stopped - unrecorded).takeIf { !it.isNegative } ?: Duration.ZERO
            return TrackStats(pts.first().time, pts.last().time, moving, unrecorded, Geodesy.length(pts.map { it.position }), stops)
        }

        /** Stops with no record of [others] within [nearM] metres: places looked at but maybe not written down. */
        fun unrecordedStops(stats: TrackStats, others: List<GeoFeature>, nearM: Double = 50.0): List<Stop> {
            val points = others.filter { !it.sync.deleted && it.kind != FeatureKind.TRACK }
                .mapNotNull { (it.geometry as? Geometry.Point)?.position }
            return stats.stops.filter { s -> points.none { Geodesy.distance(it, s.position) <= nearM } }
        }
    }
}
