package org.geocrew.core.model

import kotlin.math.roundToInt

/**
 * Slips that are cheap to fix in the field and expensive afterwards: two
 * stations with the same number, a sample number used twice (the lab result
 * would land on the wrong bag), a point that never got a position, a fix too
 * poor to plot at map scale, a record far away from all the others (swapped
 * or mistyped coordinates), route points that were deleted, and records a
 * leader sent back to be fixed. Only reads;
 * the user decides what to change.
 */
object RecordCheck {
    enum class Code(val label: String) {
        /** A leader sent the record back (protocol §6.9). */
        REDO("组长退回"),
        DUPLICATE_NUMBER("编号重复"),
        DUPLICATE_SAMPLE("样品编号重复"),
        NO_POSITION("没有位置"),
        POOR_ACCURACY("定位精度差"),
        FAR_AWAY("位置离群"),
        MISSING_STOP("路线缺点"),
    }

    data class Issue(val featureId: String, val code: Code, val message: String)

    /** Kinds that are meant to have a place on the map. */
    private val located = setOf(FeatureKind.POINT, FeatureKind.GEO_POINT, FeatureKind.LINE, FeatureKind.POLYGON, FeatureKind.TRACK, FeatureKind.PHOTO, FeatureKind.BOREHOLE)

    /** Kinds numbered like D001 in the field book. */
    private val numbered = setOf(FeatureKind.POINT, FeatureKind.GEO_POINT, FeatureKind.PHOTO, FeatureKind.SECTION, FeatureKind.ROUTE, FeatureKind.BOREHOLE)

    /** Least records before an outlier means anything. */
    private const val MIN_FOR_OUTLIER = 5

    /**
     * Issues of the live records in [features], by record id, in a fixed
     * order of [Code]. A fix poorer than [accuracyLimit] metres and a record
     * more than [farKm] from the middle of the others are reported.
     */
    fun of(features: List<GeoFeature>, accuracyLimit: Double = 30.0, farKm: Double = 50.0): Map<String, List<Issue>> {
        val live = features.filterNot { it.deleted }
        val out = mutableListOf<Issue>()

        // Same number on two records of numbered kinds; text-only titles like 「断层」 are left alone.
        live.filter { it.kind in numbered && it.title?.any(Char::isDigit) == true }
            .groupBy { it.title!!.trim().lowercase() }
            .values.filter { it.size > 1 }
            .forEach { group -> group.forEach { f -> out += Issue(f.id, Code.DUPLICATE_NUMBER, "编号「${f.title!!.trim()}」还用在另外 ${group.size - 1} 条记录上") } }

        // Sample numbers, across the whole project.
        val owners = mutableMapOf<String, MutableList<GeoFeature>>()
        live.forEach { f -> f.samples.forEach { s -> owners.getOrPut(s.no.trim().lowercase()) { mutableListOf() } += f } }
        live.forEach { f ->
            f.samples.map { it.no.trim() }.distinctBy { it.lowercase() }.forEach { no ->
                val all = owners.getValue(no.lowercase())
                if (all.size > 1) {
                    val others = all.filter { it.id != f.id }.distinctBy { it.id }.map { it.title ?: "未命名" }
                    out += Issue(f.id, Code.DUPLICATE_SAMPLE, if (others.isEmpty()) "样品编号「$no」在这条记录里用了两次" else "样品编号「$no」也在 ${others.joinToString("、")}")
                }
            }
        }

        live.forEach { f ->
            val r = f.review?.takeIf { it.state == Review.State.REDO } ?: return@forEach
            out += Issue(f.id, Code.REDO, r.note.ifEmpty { "需要补充或改正" })
        }

        live.filter { it.kind in located && it.geometry == null }.forEach { out += Issue(it.id, Code.NO_POSITION, "没有位置，地图和导出里都不会出现") }

        live.forEach { f ->
            val acc = f.properties.double(Props.ACCURACY_M) ?: return@forEach
            if (acc > accuracyLimit) out += Issue(f.id, Code.POOR_ACCURACY, "定位精度约 ±${acc.roundToInt()} 米")
        }

        // Far from the median position of the rest: usually swapped or mistyped coordinates.
        val anchors = live.mapNotNull { f -> MapDisplay.anchor(f.geometry)?.let { f to it } }
        if (anchors.size >= MIN_FOR_OUTLIER) {
            val mid = LngLat(median(anchors.map { it.second.lng }), median(anchors.map { it.second.lat }))
            val dist = anchors.map { (f, p) -> f to Geodesy.distance(mid, p) }
            val typical = median(dist.map { it.second })
            dist.filter { (_, d) -> d > farKm * 1000 && d > typical * 10 }
                .forEach { (f, d) -> out += Issue(f.id, Code.FAR_AWAY, "离其他记录约 ${(d / 1000).roundToInt()} 千米，坐标可能输错了") }
        }

        val byId = features.associateBy { it.id }
        live.filter { it.kind == FeatureKind.ROUTE }.forEach { r ->
            val gone = r.geoRoute.resolve(byId::get).count { it.feature == null }
            if (gone > 0) out += Issue(r.id, Code.MISSING_STOP, "有 $gone 个路线点已被删除")
        }

        return out.sortedBy { it.code.ordinal }.groupBy { it.featureId }
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
