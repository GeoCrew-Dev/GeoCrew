package org.geocrew.core.model

/**
 * Elevation along a path, e.g. a recorded track: what a traverse climbed and
 * descended, and the samples to draw it. Vertices without an altitude still
 * count toward distance but are not sampled.
 */
data class ElevationProfile(
    val samples: List<Sample>,
    /** Total climb in metres, ignoring wiggles smaller than the threshold used. */
    val ascent: Double,
    val descent: Double,
    val min: Double,
    val max: Double,
    /** Length of the whole path in metres. */
    val length: Double,
) {
    data class Sample(val distance: Double, val elevation: Double)

    /** At most [n] samples for drawing, keeping each bucket's lowest and highest so peaks survive. */
    fun downsampled(n: Int): List<Sample> {
        if (samples.size <= n || n < 4) return samples
        val buckets = n / 2
        val step = length / buckets
        if (step <= 0) return samples.take(n)
        val out = ArrayList<Sample>(n + 2)
        samples.groupBy { minOf(buckets - 1, (it.distance / step).toInt()) }.toSortedMap().values.forEach { b ->
            val lo = b.minBy { it.elevation }
            val hi = b.maxBy { it.elevation }
            if (lo === hi) out += lo else out += listOf(lo, hi).sortedBy { it.distance }
        }
        if (out.first() !== samples.first()) out.add(0, samples.first())
        if (out.last() !== samples.last()) out += samples.last()
        return out
    }

    companion object {
        /**
         * Builds the profile of [path], or null when fewer than two vertices
         * carry an altitude. [threshold] (metres) filters GPS altitude noise:
         * a climb only counts once the path has risen that far above its
         * last low point, and likewise for descents.
         */
        fun of(path: List<LngLat>, threshold: Double = 5.0): ElevationProfile? {
            val samples = ArrayList<Sample>()
            var d = 0.0
            path.forEachIndexed { i, p ->
                if (i > 0) d += Geodesy.distance(path[i - 1], p)
                val a = p.alt
                if (a != null && a.isFinite()) samples += Sample(d, a)
            }
            if (samples.size < 2) return null
            var ascent = 0.0
            var descent = 0.0
            var ref = samples.first().elevation
            for (s in samples) {
                val diff = s.elevation - ref
                if (diff >= threshold) { ascent += diff; ref = s.elevation }
                else if (diff <= -threshold) { descent -= diff; ref = s.elevation }
            }
            return ElevationProfile(
                samples = samples,
                ascent = ascent,
                descent = descent,
                min = samples.minOf { it.elevation },
                max = samples.maxOf { it.elevation },
                length = d,
            )
        }
    }
}
