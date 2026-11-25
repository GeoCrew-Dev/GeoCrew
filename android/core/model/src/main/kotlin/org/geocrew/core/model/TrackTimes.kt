package org.geocrew.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * Per-fix times of a track, synced with it (protocol v0 §6.13): property
 * `times_s` holds, for each coordinate of the LineString in order, whole
 * seconds since `started_at`. With it a teammate's track can be exported
 * to GPX with times or used to place photos, not only the recorder's own.
 */
object TrackTimes {
    /** Writes [times] (one per coordinate) relative to [startedAt]; removes the key when there is nothing usable. */
    fun write(b: PropertiesBuilder, startedAt: Instant, times: List<Instant>) {
        if (times.size < 2) { b.remove(Props.TIMES_S); return }
        var last = Long.MIN_VALUE
        val secs = times.map { t ->
            // A clock stepped back mid-track must not make the list go backwards.
            (t.epochSecond - startedAt.epochSecond).coerceAtLeast(last).also { last = it }
        }
        b.put(Props.TIMES_S, JsonArray(secs.map(::JsonPrimitive)))
    }

    /**
     * The fix times of [f], or null when it has none, they do not match its
     * coordinates one to one, or they go backwards. Only a single
     * LineString can carry them.
     */
    fun of(f: GeoFeature): List<Instant>? {
        val line = (f.geometry as? Geometry.LineString)?.positions ?: return null
        val started = f.properties.string(Props.STARTED_AT)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val arr = f.properties[Props.TIMES_S] as? JsonArray ?: return null
        if (arr.size != line.size) return null
        var last = Long.MIN_VALUE
        val out = ArrayList<Instant>(arr.size)
        for (e in arr) {
            val s = (e as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
            if (s < last) return null
            last = s
            out += started.plusSeconds(s)
        }
        return out
    }

    /** The track as timed fixes, from its synced times; null as for [of]. */
    fun fixes(f: GeoFeature): List<TrackPoint>? {
        val times = of(f) ?: return null
        val line = (f.geometry as Geometry.LineString).positions
        return line.indices.map { TrackPoint(line[it], times[it]) }
    }
}
