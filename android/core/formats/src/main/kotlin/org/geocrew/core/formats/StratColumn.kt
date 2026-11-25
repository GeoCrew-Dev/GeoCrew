package org.geocrew.core.formats

import org.geocrew.core.model.MeasuredSection

/**
 * The fill drawn for a lithology in a stratigraphic column, after the usual
 * Chinese and international chart conventions. Picked from the lithology's
 * name by keyword. A Chinese rock name ends in its head noun, so the keyword
 * that ends last wins, the longer one on a tie: 泥质粉砂岩 is a siltstone,
 * 砂砾岩 a conglomerate, 白云质灰岩 a limestone and 凝灰岩 not a limestone.
 */
enum class LithoPattern(val label: String) {
    COAL("煤"),
    CONGLOMERATE("砾岩"),
    SILTSTONE("粉砂岩"),
    SANDSTONE("砂岩"),
    MARL("泥灰岩"),
    DOLOMITE("白云岩"),
    LIMESTONE("灰岩"),
    MUDSTONE("泥岩、页岩"),
    INTRUSIVE("侵入岩"),
    VOLCANIC("火山岩"),
    METAMORPHIC("变质岩"),
    BLANK("其他"),
    ;

    companion object {
        private val KEYS: List<Pair<LithoPattern, List<String>>> = listOf(
            COAL to listOf("煤", "coal"),
            CONGLOMERATE to listOf("砾", "conglomerate", "breccia"),
            SILTSTONE to listOf("粉砂", "silt"),
            SANDSTONE to listOf("砂", "sand"),
            MARL to listOf("泥灰", "泥灰岩", "marl"),
            DOLOMITE to listOf("白云", "dolo"),
            LIMESTONE to listOf("灰岩", "大理岩", "lime", "marble"),
            MUDSTONE to listOf("泥", "页岩", "黏土", "粘土", "shale", "mud", "clay"),
            INTRUSIVE to listOf("花岗", "闪长", "辉长", "二长", "正长", "斑岩", "辉绿", "橄榄岩", "granit", "diorite", "gabbro", "porphyr"),
            VOLCANIC to listOf("玄武", "安山", "流纹", "英安", "粗面", "凝灰", "凝灰岩", "火山", "basalt", "andesite", "rhyolite", "tuff"),
            METAMORPHIC to listOf("片岩", "片麻", "千枚", "板岩", "石英岩", "混合岩", "变质", "schist", "gneiss", "phyllite", "slate", "quartzite"),
        )

        fun of(lithology: String): LithoPattern {
            val s = lithology.trim().lowercase()
            if (s.isEmpty()) return BLANK
            var best = BLANK
            var bestEnd = -1
            var bestLen = 0
            for ((pattern, keys) in KEYS) for (k in keys) {
                val i = s.lastIndexOf(k)
                if (i < 0) continue
                val end = i + k.length
                if (end > bestEnd || (end == bestEnd && k.length > bestLen)) {
                    best = pattern
                    bestEnd = end
                    bestLen = k.length
                }
            }
            return best
        }
    }
}

/**
 * A measured section laid out as a stratigraphic column (实测剖面柱状图):
 * layers stacked by true thickness at one chart scale, youngest at the top.
 * Pure layout; the app draws it. Thin layers get a minimum height so their
 * number stays readable, which the chart notes.
 */
data class StratColumn(
    val title: String,
    /** Top of the column first. */
    val rows: List<Row>,
    /** Sum of the known true thicknesses (m). */
    val total: Double,
    /** Chart scale denominator, e.g. 500 for 1:500. */
    val scale: Int,
    /** Whether the traverse started at the base, so layer 1 is at the bottom. */
    val startIsBase: Boolean,
    /** Layers whose thickness couldn't be worked out (no attitude anywhere). */
    val unknown: Int,
    /** A measured section stacked by thickness, or a borehole drawn by depth. */
    val kind: Kind = Kind.SECTION,
    /** Depth of the water table in a borehole (m below the collar). */
    val water: Double? = null,
) {
    enum class Kind { SECTION, BOREHOLE }

    data class Row(
        val no: String,
        val lithology: String,
        val pattern: LithoPattern,
        /** True thickness (m); null when unknown. */
        val thickness: Double?,
        /**
         * Height from the column's base to this row's base and top (m), by
         * known thicknesses. In a borehole they are depths instead: [base] is
         * the depth of the layer's top and [top] the depth of its bottom.
         */
        val base: Double,
        val top: Double,
        /** Drawn height on paper (mm). */
        val heightMm: Double,
        /** Bedding as written, e.g. 120°∠35°; null when there is none. */
        val attitude: String?,
        /** True when the attitude was borrowed from a neighbouring layer. */
        val borrowed: Boolean,
        val note: String,
        /** True when the row is drawn taller than its scale height. */
        val stretched: Boolean,
    )

    val heightMm: Double get() = rows.sumOf { it.heightMm }

    companion object {
        /**
         * Lays a borehole log out as a column, collar at the top, at the finest
         * scale that fits about [maxHeightMm]; the hole is drawn to its final
         * depth, so a stretch below the last layer stays blank.
         */
        fun borehole(title: String, hole: org.geocrew.core.model.Borehole, maxHeightMm: Double = 230.0, minRowMm: Double = 3.0): StratColumn {
            val iv = hole.intervals()
            fun heights(scale: Int) = iv.map { i ->
                val h = i.thickness * 1000 / scale
                maxOf(h, minRowMm) to (h < minRowMm)
            }
            val scale = SCALES.firstOrNull { sc -> heights(sc).sumOf { it.first } <= maxHeightMm } ?: SCALES.last()
            val hs = heights(scale)
            val rows = iv.mapIndexed { k, i ->
                Row(
                    no = i.layer.no,
                    lithology = i.layer.lithology.trim(),
                    pattern = LithoPattern.of(i.layer.lithology),
                    thickness = i.thickness,
                    base = i.top,
                    top = i.bottom,
                    heightMm = hs[k].first,
                    attitude = null,
                    borrowed = false,
                    note = i.layer.note.trim(),
                    stretched = hs[k].second,
                )
            }
            return StratColumn(title, rows, hole.total, scale, startIsBase = false, unknown = 0, kind = Kind.BOREHOLE, water = hole.water)
        }

        /** Scales a chart is usually drawn at, finest first. */
        val SCALES = listOf(50, 100, 200, 500, 1000, 2000, 5000, 10_000, 20_000, 50_000)

        /**
         * Lays [section] out to fit about [maxHeightMm] of paper. [startIsBase]
         * says the traverse was walked up the sequence (the usual way), so the
         * first layer is the oldest and goes at the bottom.
         */
        fun build(title: String, section: MeasuredSection, startIsBase: Boolean = true, maxHeightMm: Double = 230.0, minRowMm: Double = 3.0): StratColumn {
            val r = section.compute()
            val ordered = if (startIsBase) r.layers else r.layers.reversed() // base first
            val known = ordered.mapNotNull { it.thickness?.takeIf { t -> t > 0 } }
            val total = known.sum()

            fun heights(scale: Int) = ordered.map { l ->
                val t = l.thickness?.takeIf { it > 0 }
                val h = t?.let { it * 1000 / scale } ?: 0.0
                maxOf(h, minRowMm) to (h < minRowMm)
            }
            // The finest scale that fits; very thin layers may still push past the page, which the app pages.
            val scale = SCALES.firstOrNull { s -> heights(s).sumOf { it.first } <= maxHeightMm } ?: SCALES.last()
            val hs = heights(scale)

            var at = 0.0
            val rows = ordered.mapIndexed { i, l ->
                val t = l.thickness?.takeIf { it > 0 }
                val base = at
                at += t ?: 0.0
                Row(
                    no = l.layer.no,
                    lithology = l.layer.lithology.trim(),
                    pattern = LithoPattern.of(l.layer.lithology),
                    thickness = t,
                    base = base,
                    top = at,
                    heightMm = hs[i].first,
                    attitude = l.attitude?.format(),
                    borrowed = l.borrowed,
                    note = l.layer.note.trim(),
                    stretched = hs[i].second,
                )
            }
            return StratColumn(title, rows.reversed(), total, scale, startIsBase, ordered.count { (it.thickness ?: 0.0) <= 0 })
        }
    }
}
