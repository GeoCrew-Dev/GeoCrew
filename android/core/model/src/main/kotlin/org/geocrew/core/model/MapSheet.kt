package org.geocrew.core.model

import kotlin.math.floor

/**
 * China's national topographic map sheets (GB/T 13989-2012): which sheet
 * a position lies on, and where a sheet is. A 1:1 000 000 sheet is 4° of
 * latitude by 6° of longitude, named by its row letter (A from the equator)
 * and column (31 at 0°E), e.g. J50; larger scales divide it and add a scale
 * letter, a three-digit row counted from the north and a three-digit
 * column, e.g. J50E001001 for 1:50 000. Covers the northern and eastern
 * hemispheres, i.e. all of China.
 */
object MapSheet {
    /** A scale of the series and how many of its sheets fit along each side of a 1:1 000 000 sheet. */
    enum class Scale(val letter: Char?, val label: String, val perMillion: Int) {
        M1000K(null, "1:100万", 1),
        M500K('B', "1:50万", 2),
        M250K('C', "1:25万", 4),
        M100K('D', "1:10万", 12),
        M50K('E', "1:5万", 24),
        M25K('F', "1:2.5万", 48),
        M10K('G', "1:1万", 96),
        M5K('H', "1:5000", 192),
        ;

        val latSize: Double get() = 4.0 / perMillion
        val lngSize: Double get() = 6.0 / perMillion

        companion object {
            fun byLetter(c: Char) = entries.firstOrNull { it.letter == c.uppercaseChar() }
        }
    }

    data class Sheet(val code: String, val scale: Scale, val bounds: Bounds)

    /** The sheet at [scale] that [p] lies on; null outside the northern-eastern quarter of the globe. */
    fun of(p: LngLat, scale: Scale): Sheet? {
        if (p.lat < 0 || p.lat >= 88 || p.lng < 0 || p.lng >= 180) return null
        val row = floor(p.lat / 4).toInt()
        val col = floor(p.lng / 6).toInt()
        val million = "${'A' + row}${col + 31}"
        if (scale == Scale.M1000K) return Sheet(million, scale, Bounds(col * 6.0, row * 4.0, col * 6.0 + 6, row * 4.0 + 4))
        // Work in units of the sheet size so a point on an edge goes to one sheet only.
        val latIn = p.lat - row * 4
        val lngIn = p.lng - col * 6
        val n = scale.perMillion
        val fromSouth = floor(latIn / scale.latSize + 1e-9).toInt().coerceIn(0, n - 1)
        val c = floor(lngIn / scale.lngSize + 1e-9).toInt().coerceIn(0, n - 1)
        return sheet(row, col, scale, n - fromSouth, c + 1)
    }

    /** The sheet a code names (with or without dashes, any case), or null if it is not a valid code. */
    fun parse(code: String): Sheet? {
        val s = code.trim().uppercase().replace("-", "").replace(" ", "")
        val m = CODE.matchEntire(s) ?: return null
        val row = m.groupValues[1][0] - 'A'
        val col = m.groupValues[2].toInt() - 31
        if (row !in 0..21 || col !in 0..29) return null
        if (m.groupValues[3].isEmpty()) return of(LngLat(col * 6.0 + 3, row * 4.0 + 2), Scale.M1000K)
        val scale = Scale.byLetter(m.groupValues[3][0]) ?: return null
        val r = m.groupValues[4].toInt()
        val c = m.groupValues[5].toInt()
        if (r !in 1..scale.perMillion || c !in 1..scale.perMillion) return null
        return sheet(row, col, scale, r, c)
    }

    /**
     * Sheets at [scale] covering [view], or null when there would be more
     * than [max] of them (zoomed too far out to draw that scale usefully).
     */
    fun covering(view: Bounds, scale: Scale, max: Int = 400): List<Sheet>? {
        val south = view.south.coerceIn(0.0, 87.999)
        val north = view.north.coerceIn(0.0, 87.999)
        val west = view.west.coerceIn(0.0, 179.999)
        val east = view.east.coerceIn(0.0, 179.999)
        if (north <= south || east <= west) return emptyList()
        val r0 = floor(south / scale.latSize).toLong(); val r1 = floor(north / scale.latSize).toLong()
        val c0 = floor(west / scale.lngSize).toLong(); val c1 = floor(east / scale.lngSize).toLong()
        if ((r1 - r0 + 1) * (c1 - c0 + 1) > max) return null
        return (r0..r1).flatMap { r ->
            (c0..c1).mapNotNull { c -> of(LngLat((c + 0.5) * scale.lngSize, (r + 0.5) * scale.latSize), scale) }
        }
    }

    private val CODE = Regex("^([A-V])(\\d{2})(?:([B-H])(\\d{3})(\\d{3}))?$")

    private fun sheet(row: Int, col: Int, scale: Scale, r: Int, c: Int): Sheet {
        val n = scale.perMillion
        val south = row * 4 + (n - r) * scale.latSize
        val west = col * 6 + (c - 1) * scale.lngSize
        val code = "${'A' + row}${col + 31}${scale.letter}${"%03d".format(r)}${"%03d".format(c)}"
        return Sheet(code, scale, Bounds(west, south, west + scale.lngSize, south + scale.latSize))
    }
}
