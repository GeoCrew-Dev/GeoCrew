package org.geocrew.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * One bed (分层) of a measured section. Layers follow each other along the
 * traverse from its start; [top] is where this layer ends, as slope distance
 * along the whole traverse (m), so layer i spans the previous top to its own.
 * [attitude] is the bedding measured in this layer; layers without one
 * borrow the nearest measured attitude.
 */
data class SectionLayer(
    val no: String,
    val top: Double,
    val lithology: String = "",
    val attitude: Attitude? = null,
    val note: String = "",
)

/**
 * A measured stratigraphic section (实测剖面, protocol v0 §6.5): the traverse
 * as legs measured with tape and compass, and the layers met along it. Each
 * layer's true thickness is the traverse's component along the bedding
 * normal over the layer's stretch, the same dot product as [ThicknessResult],
 * so slope, obliquity and bends in the traverse are all handled alike.
 */
data class MeasuredSection(
    val legs: List<TraverseLeg> = emptyList(),
    val layers: List<SectionLayer> = emptyList(),
) {
    val length: Double get() = legs.sumOf { it.slopeDistance }

    data class LayerResult(
        val layer: SectionLayer,
        val from: Double,
        val to: Double,
        /** True thickness, m; null when no attitude is known anywhere in the section. */
        val thickness: Double?,
        val attitude: Attitude?,
        /** The attitude came from another layer. */
        val borrowed: Boolean,
        /** The traverse turned back across the bedding within this layer. */
        val mixed: Boolean,
    )

    data class Result(
        val layers: List<LayerResult>,
        val total: Double,
        /** Leg ends as (horizontal distance along the traverse, height above the start), m. */
        val profile: List<Pair<Double, Double>>,
        val problems: List<String>,
    )

    fun compute(): Result {
        val problems = mutableListOf<String>()
        val total = length
        val known = layers.mapIndexedNotNull { i, l -> l.attitude?.let { i to it } }
        var from = 0.0
        val out = layers.mapIndexed { i, l ->
            if (l.top <= from) problems += "分层 ${l.no} 的终点（${"%.1f".fmt(l.top)} m）不在上一层之后"
            if (l.top > total + 1e-6) problems += "分层 ${l.no} 的终点超出导线总长 ${"%.1f".fmt(total)} m"
            val a = from
            val b = l.top.coerceAtMost(total)
            from = maxOf(from, l.top)
            val att = l.attitude ?: known.minByOrNull { (j, _) -> abs(j - i) * 2 + if (j < i) 0 else 1 }?.second
            if (att == null || b <= a) {
                LayerResult(l, a, l.top, if (att == null) null else 0.0, att, l.attitude == null && att != null, false)
            } else {
                val n = att.normal()
                val parts = pieces(a, b).map { (leg, len) -> leg.vector().let { v -> (v[0] * n[0] + v[1] * n[1] + v[2] * n[2]) * len / leg.slopeDistance } }
                val sum = parts.sum()
                LayerResult(
                    l, a, l.top, abs(sum), att, l.attitude == null,
                    mixed = parts.any { it > 0.01 } && parts.any { it < -0.01 },
                )
            }
        }
        if (layers.isNotEmpty() && known.isEmpty()) problems += "还没有任何分层的产状，无法计算厚度"
        var x = 0.0
        var z = 0.0
        val profile = mutableListOf(0.0 to 0.0)
        for (leg in legs) {
            val v = leg.vector()
            x += kotlin.math.hypot(v[0], v[1])
            z += v[2]
            profile += x to z
        }
        return Result(out, out.sumOf { it.thickness ?: 0.0 }, profile, problems.distinct())
    }

    /**
     * The point [s] metres of slope distance along the traverse, as
     * (horizontal distance, height above the start) on the profile; clamped
     * to the traverse's ends.
     */
    fun profileAt(s: Double): Pair<Double, Double> {
        var x = 0.0
        var z = 0.0
        var start = 0.0
        for (leg in legs) {
            val v = leg.vector()
            val h = kotlin.math.hypot(v[0], v[1])
            val end = start + leg.slopeDistance
            if (s < end && leg.slopeDistance > 0) {
                val f = ((s - start) / leg.slopeDistance).coerceIn(0.0, 1.0)
                return (x + h * f) to (z + v[2] * f)
            }
            x += h
            z += v[2]
            start = end
        }
        return x to z
    }

    /** Legs and the slope length of each that falls within [a, b]. */
    private fun pieces(a: Double, b: Double): List<Pair<TraverseLeg, Double>> {
        var start = 0.0
        val out = mutableListOf<Pair<TraverseLeg, Double>>()
        for (leg in legs) {
            val end = start + leg.slopeDistance
            val len = minOf(end, b) - maxOf(start, a)
            if (len > 0 && leg.slopeDistance > 0) out += leg to len
            start = end
        }
        return out
    }

    /** The traverse on the map from [start], with heights when the start has one. */
    fun path(start: LngLat): List<LngLat> {
        val out = mutableListOf(start)
        var p = start
        for (leg in legs) {
            val v = leg.vector()
            val h = kotlin.math.hypot(v[0], v[1])
            val next = Geodesy.destination(p, leg.azimuth, h)
            p = LngLat(next.lng, next.lat, p.alt?.plus(v[2]))
            out += p
        }
        return out
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("legs", JsonArray(legs.map { l -> buildJsonObject { put("azimuth", l.azimuth); put("slope", l.slopeAngle); put("distance", l.slopeDistance) } }))
        put(
            "layers",
            JsonArray(
                layers.map { l ->
                    buildJsonObject {
                        put("no", l.no)
                        put("top", l.top)
                        if (l.lithology.isNotBlank()) put("lithology", l.lithology)
                        l.attitude?.let { put("dip_direction", it.dipDirection); put("dip", it.dip) }
                        if (l.note.isNotBlank()) put("note", l.note)
                    }
                },
            ),
        )
    }

    companion object {
        fun fromJson(o: JsonObject?): MeasuredSection {
            if (o == null) return MeasuredSection()
            val legs = (o["legs"] as? JsonArray).orEmpty().mapNotNull { e ->
                val l = e as? JsonObject ?: return@mapNotNull null
                val az = l.double("azimuth") ?: return@mapNotNull null
                val d = l.double("distance")?.takeIf { it > 0 && it.isFinite() } ?: return@mapNotNull null
                TraverseLeg(Geodesy.normalizeDegrees(az), (l.double("slope") ?: 0.0).coerceIn(-90.0, 90.0), d)
            }
            val layers = (o["layers"] as? JsonArray).orEmpty().mapNotNull { e ->
                val l = e as? JsonObject ?: return@mapNotNull null
                val top = l.double("top")?.takeIf { it.isFinite() } ?: return@mapNotNull null
                SectionLayer(
                    no = l.string("no").orEmpty(),
                    top = top,
                    lithology = l.string("lithology").orEmpty(),
                    attitude = Attitude.fromProperties(l),
                    note = l.string("note").orEmpty(),
                )
            }
            return MeasuredSection(legs, layers)
        }
    }
}

/** The measured section stored on a record of kind [FeatureKind.SECTION]. */
val GeoFeature.measuredSection: MeasuredSection
    get() = MeasuredSection.fromJson(properties[Props.SECTION] as? JsonObject)

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

