package org.geocrew.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One layer of a borehole or pit log, from the bottom of the layer above
 * (the collar for the first) down to [bottom] metres below the collar.
 */
data class BoreLayer(
    val no: String,
    val bottom: Double,
    val lithology: String = "",
    val note: String = "",
)

/**
 * A borehole, test pit or shaft logged by depth (钻孔 / 浅井编录, protocol
 * v0 §6.11), stored on a record of kind [FeatureKind.BOREHOLE] whose point
 * is the collar. Depths are metres along the hole from the collar; holes are
 * taken as vertical, which is how most field logs are drawn.
 */
data class Borehole(
    val layers: List<BoreLayer> = emptyList(),
    /** Final depth; null means the bottom of the last layer. */
    val depth: Double? = null,
    /** Depth of the water table, if met. */
    val water: Double? = null,
) {
    data class Interval(val layer: BoreLayer, val top: Double, val bottom: Double) {
        val thickness: Double get() = bottom - top
    }

    /** The final depth: as recorded, else the deepest layer bottom. */
    val total: Double get() = depth ?: layers.maxOfOrNull { it.bottom } ?: 0.0

    /** Layers with their top and bottom depths, top first, skipping ones that do not go deeper. */
    fun intervals(): List<Interval> {
        var top = 0.0
        val out = mutableListOf<Interval>()
        for (l in layers) {
            if (!(l.bottom > top)) continue
            out += Interval(l, top, l.bottom)
            top = l.bottom
        }
        return out
    }

    /** Things worth fixing before the log is printed, in Chinese. */
    fun problems(): List<String> = buildList {
        var top = 0.0
        for (l in layers) {
            if (!(l.bottom > top)) add("第 ${l.no.ifBlank { "?" }} 层的层底深度 ${l.bottom.fmt()} m 不比上一层深，已跳过")
            else top = l.bottom
        }
        depth?.let { d -> if (top > d + 1e-6) add("分层已到 ${top.fmt()} m，比终孔深度 ${d.fmt()} m 还深") }
        water?.let { w -> if (w > total + 1e-6) add("水位 ${w.fmt()} m 比终孔还深") }
    }

    fun toJson(): JsonObject = buildJsonObject {
        depth?.let { put("depth", it) }
        water?.let { put("water", it) }
        put(
            "layers",
            JsonArray(
                layers.map { l ->
                    buildJsonObject {
                        put("no", l.no)
                        put("bottom", l.bottom)
                        if (l.lithology.isNotBlank()) put("lithology", l.lithology)
                        if (l.note.isNotBlank()) put("note", l.note)
                    }
                },
            ),
        )
    }

    companion object {
        /** Deepest hole accepted, so a typo cannot make a page kilometres long. */
        const val MAX_DEPTH = 5000.0

        fun fromJson(o: JsonObject?): Borehole {
            if (o == null) return Borehole()
            fun ok(v: Double?) = v?.takeIf { it.isFinite() && it >= 0 && it <= MAX_DEPTH }
            val layers = ((o["layers"] as? JsonArray) ?: emptyList<JsonElement>()).mapNotNull { e ->
                val l = e as? JsonObject ?: return@mapNotNull null
                val bottom = ok(l.double("bottom"))?.takeIf { it > 0 } ?: return@mapNotNull null
                BoreLayer(l.string("no").orEmpty(), bottom, l.string("lithology").orEmpty(), l.string("note").orEmpty())
            }
            return Borehole(layers, ok(o.double("depth"))?.takeIf { it > 0 }, ok(o.double("water")))
        }

        private fun Double.fmt() = if (this == Math.rint(this)) String.format(java.util.Locale.ROOT, "%.0f", this) else String.format(java.util.Locale.ROOT, "%.2f", this).trimEnd('0').trimEnd('.')
    }
}

/** The log stored on a record of kind [FeatureKind.BOREHOLE]. */
val GeoFeature.borehole: Borehole
    get() = Borehole.fromJson(properties[Props.BOREHOLE] as? JsonObject)
