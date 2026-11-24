package org.geocrew.core.model

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * Places photos taken without GPS (a separate camera, or a phone with
 * location off) on the tracks recorded at the same time: the photo's time,
 * corrected by the camera clock's [offset], is looked up on each track and
 * the position interpolated between the two fixes around it.
 */
object PhotoGeotag {
    data class Match(
        val position: LngLat,
        /** How far from a recorded fix in time the match is; zero when it lies between two close fixes. */
        val gap: Duration,
        val trackId: String,
    )

    /**
     * Where [taken] lies on [tracks] (track id to its fixes in any order).
     * [offset] is how far the camera's clock is ahead of the real time, so
     * the real time is `taken - offset`. Between two fixes at most [maxGap]
     * apart the position is interpolated; otherwise the nearer fix is used
     * if it is within [tolerance], which also allows a little before the
     * first or after the last fix. Returns the closest match over all tracks.
     */
    fun place(
        taken: Instant,
        tracks: Map<String, List<TrackPoint>>,
        offset: Duration = Duration.ZERO,
        tolerance: Duration = Duration.ofMinutes(2),
        maxGap: Duration = Duration.ofMinutes(10),
    ): Match? {
        val t = taken.minus(offset).toEpochMilli()
        var best: Match? = null
        for ((id, raw) in tracks) {
            val pts = raw.sortedBy { it.time }
            if (pts.isEmpty()) continue
            val m = onTrack(t, pts, tolerance.toMillis(), maxGap.toMillis(), id) ?: continue
            if (best == null || m.gap < best.gap) best = m
        }
        return best
    }

    private fun onTrack(t: Long, pts: List<TrackPoint>, tolerance: Long, maxGap: Long, id: String): Match? {
        val times = LongArray(pts.size) { pts[it].time.toEpochMilli() }
        fun near(i: Int): Match? {
            val d = abs(times[i] - t)
            return if (d <= tolerance) Match(pts[i].position, Duration.ofMillis(d), id) else null
        }
        if (t <= times.first()) return near(0)
        if (t >= times.last()) return near(pts.lastIndex)
        // First fix after t; the one before it is at or before t.
        var lo = 0
        var hi = pts.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (times[mid] <= t) lo = mid + 1 else hi = mid
        }
        val b = lo
        val a = b - 1
        val span = times[b] - times[a]
        if (span <= maxGap) {
            val f = if (span == 0L) 0.0 else (t - times[a]).toDouble() / span
            val pa = pts[a].position
            val pb = pts[b].position
            val alt = if (pa.alt != null && pb.alt != null) pa.alt + (pb.alt - pa.alt) * f else pa.alt ?: pb.alt
            return Match(LngLat(pa.lng + (pb.lng - pa.lng) * f, pa.lat + (pb.lat - pa.lat) * f, alt), Duration.ZERO, id)
        }
        // A pause in the track: only trust a fix close enough in time.
        return listOfNotNull(near(a), near(b)).minByOrNull { it.gap }
    }
}
