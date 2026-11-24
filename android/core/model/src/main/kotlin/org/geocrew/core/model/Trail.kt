package org.geocrew.core.model

import java.time.Duration
import java.time.Instant

/** One fix of a teammate's recent path. */
data class TrailPoint(val position: LngLat, val at: Instant)

/**
 * Recent paths of moving things (teammates on the map), kept short on
 * purpose: only fixes from the last [maxAge], at least [minSpacing] metres
 * apart so a teammate standing still does not pile up points, and never more
 * than [maxPoints]. Fixes that arrive out of order or jump implausibly
 * ([maxSpeed]) are dropped rather than drawn as a spike. Immutable: [add]
 * returns the new map.
 */
class TrailBuffer(
    private val maxAge: Duration = Duration.ofMinutes(30),
    private val minSpacing: Double = 5.0,
    private val maxPoints: Int = 300,
    private val maxSpeed: Double = 60.0,
) {
    fun add(trails: Map<String, List<TrailPoint>>, id: String, p: TrailPoint, now: Instant = p.at): Map<String, List<TrailPoint>> {
        val cur = trails[id].orEmpty().filter { Duration.between(it.at, now) <= maxAge }
        val last = cur.lastOrNull()
        val next = when {
            Duration.between(p.at, now) > maxAge -> cur
            last == null -> listOf(p)
            !p.at.isAfter(last.at) -> cur
            Geodesy.distance(last.position, p.position).let { d ->
                d < minSpacing || d / (Duration.between(last.at, p.at).toMillis() / 1000.0).coerceAtLeast(1.0) > maxSpeed
            } -> cur
            else -> (cur + p).takeLast(maxPoints)
        }
        return if (next.isEmpty()) trails - id else trails + (id to next)
    }

    /** Drops fixes older than [maxAge] and members left with none. */
    fun prune(trails: Map<String, List<TrailPoint>>, now: Instant): Map<String, List<TrailPoint>> =
        trails.mapValues { (_, l) -> l.filter { Duration.between(it.at, now) <= maxAge } }.filterValues { it.isNotEmpty() }
}
