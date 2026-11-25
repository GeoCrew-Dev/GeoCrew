package org.geocrew.core.formats

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GaussKruger
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.lineStyle
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * AutoCAD DXF (R12, the version every CAD and MapGIS-style program reads)
 * in CGCS2000 3° Gauss-Krüger metres, X = easting with the zone number in
 * front and Y = northing, all in the zone of the records' middle. Records go
 * to one layer per legend value, or per record kind without a legend, with
 * the legend colour mapped to the nearest standard CAD colour; names are
 * TEXT beside them and attitudes a strike line with a dip tick and the dip
 * angle on layer 产状. Lines marked as geological contacts or faults go to
 * a layer per line style instead (faults red, inferred ones dashed). Text is written in code page 936 (GBK), as Chinese
 * CAD expects of R12 files. Symbol sizes suit a 1:10 000 plot.
 */
object DxfFormat : FeatureFormat {
    override val id = "dxf"
    override val displayName = "DXF（CAD，高斯坐标）"
    override val extension = "dxf"
    override val mimeType = "image/vnd.dxf"
    override val canRead = false
    override val canWrite = true

    private const val WIDTH = 3
    private const val TEXT_HEIGHT = 20.0
    private const val STRIKE_HALF = 25.0
    private const val DIP_TICK = 12.0
    private const val ATTITUDE_LAYER = "产状"
    private const val NAME_LAYER = "注记"
    private const val DASHED = "DASHED"
    /** Dash and gap in ground metres: 3 mm and 1 mm at 1:10 000. */
    private const val DASH = 30.0
    private const val GAP = 10.0

    override fun nothingToWrite(features: List<GeoFeature>): String? =
        if (features.none { !it.deleted && it.geometry != null }) "没有带位置的记录可以导出" else null

    override fun write(features: List<GeoFeature>, output: OutputStream, ctx: ExportContext): ExportResult {
        val live = features.filter { !it.deleted && it.geometry != null }
        val lngs = live.flatMap { positions(it.geometry) }.map { it.lng }
        val grid = ctx.localGrid
        val zone = grid?.zone ?: if (lngs.isEmpty()) 39 else GaussKruger.zone((lngs.min() + lngs.max()) / 2, WIDTH)
        fun xy(p: LngLat): Pair<Double, Double> = if (grid != null) {
            grid.toGrid(org.geocrew.core.model.Geodetic(p, p.alt ?: 0.0)).let { (n, e) -> zone * 1_000_000.0 + e to n }
        } else {
            GaussKruger.project(p, WIDTH, zone = zone).let { it.yWithZone to it.x }
        }

        val layers = linkedMapOf<String, Int>()
        val dashedLayers = mutableSetOf<String>()
        val body = StringBuilder()
        var skipped = 0
        for (f in live) {
            val style = f.lineStyle?.takeIf { f.geometry !is Geometry.Point && f.geometry !is Geometry.MultiPoint }
            val layer = style?.let { clean(it.label) } ?: layerOf(f, ctx)
            layers.getOrPut(layer) { if (style != null) (if (style.fault) 1 else 7) else ctx.legend.colorOf(f)?.let(::aci) ?: defaultColor(f.kind) }
            if (style?.dashed == true) dashedLayers += layer
            val before = body.length
            entities(f.geometry!!, layer, ::xy, body)
            if (body.length == before) {
                skipped++
                continue
            }
            val anchor = positions(f.geometry).firstOrNull() ?: continue
            val (x, y) = xy(anchor)
            f.title?.takeIf { it.isNotBlank() }?.let {
                layers.getOrPut(NAME_LAYER) { 7 }
                text(body, NAME_LAYER, x + TEXT_HEIGHT * 0.6, y + TEXT_HEIGHT * 0.4, TEXT_HEIGHT, it)
            }
            f.attitude?.let { a ->
                // A fault or contact shows its plane's attitude half way along the line.
                val (ax, ay) = midLine(f.geometry)?.let(::xy) ?: (x to y)
                layers.getOrPut(ATTITUDE_LAYER) { 1 }
                attitude(body, ax, ay, a.dipDirection, a.dip)
            }
        }

        val head = StringBuilder()
        val system = if (grid != null) {
            "${grid.name} (${grid.ellipsoid.name} ${grid.width}° zone $zone, CM ${GaussKruger.centralMeridian(zone, grid.width).fmt()}E, ${grid.shift?.let { it::class.simpleName } ?: "no datum shift"})"
        } else {
            "CGCS2000 3° Gauss-Kruger zone $zone, CM ${GaussKruger.centralMeridian(zone, WIDTH).fmt()}E"
        }
        head.group(999, "GeoCrew ${ctx.title}: $system, X = easting with zone, Y = northing")
        head.group(0, "SECTION").group(2, "HEADER")
            .group(9, "\$ACADVER").group(1, "AC1009")
            .group(9, "\$DWGCODEPAGE").group(3, "ANSI_936")
            .group(9, "\$INSUNITS").group(70, "6")
            .group(0, "ENDSEC")
        head.group(0, "SECTION").group(2, "TABLES")
            .group(0, "TABLE").group(2, "LTYPE").group(70, "2")
            .group(0, "LTYPE").group(2, "CONTINUOUS").group(70, "0").group(3, "Solid line").group(72, "65").group(73, "0").group(40, "0.0")
            .group(0, "LTYPE").group(2, DASHED).group(70, "0").group(3, "__ __ __").group(72, "65").group(73, "2")
            .group(40, (DASH + GAP).fmt()).group(49, DASH.fmt()).group(49, (-GAP).fmt())
            .group(0, "ENDTAB")
            .group(0, "TABLE").group(2, "LAYER").group(70, layers.size.toString())
        for ((name, color) in layers) {
            head.group(0, "LAYER").group(2, name).group(70, "0").group(62, color.toString()).group(6, if (name in dashedLayers) DASHED else "CONTINUOUS")
        }
        head.group(0, "ENDTAB").group(0, "ENDSEC")
        head.group(0, "SECTION").group(2, "ENTITIES")
        val all = head.append(body).group(0, "ENDSEC").group(0, "EOF")
        output.write(all.toString().toByteArray(Charset.forName("GB18030")))
        output.flush()
        return ExportResult(written = live.size - skipped, skipped = skipped)
    }

    private fun layerOf(f: GeoFeature, ctx: ExportContext): String =
        clean(ctx.legend.valueOf(f) ?: kindName(f.kind))

    /** Layer names may not hold <>/\":;?*|=` in CAD. */
    private fun clean(s: String) = s.map { if (it in "<>/\\\":;?*|=`,") '_' else it }.joinToString("").trim().take(64).ifEmpty { "0" }

    private fun kindName(k: FeatureKind) = when (k) {
        FeatureKind.GEO_POINT -> "地质点"
        FeatureKind.POINT -> "标注点"
        FeatureKind.PHOTO -> "照片点"
        FeatureKind.LINE -> "线"
        FeatureKind.POLYGON -> "面"
        FeatureKind.TRACK -> "轨迹"
        FeatureKind.SECTION -> "实测剖面"
        FeatureKind.ROUTE -> "地质路线"
        FeatureKind.BOREHOLE -> "钻孔"
        else -> k.id
    }

    private fun defaultColor(k: FeatureKind) = when (k) {
        FeatureKind.GEO_POINT -> 1
        FeatureKind.TRACK -> 4
        FeatureKind.POLYGON -> 3
        FeatureKind.SECTION, FeatureKind.ROUTE -> 6
        else -> 7
    }

    private fun positions(g: Geometry?): List<LngLat> = when (g) {
        null -> emptyList()
        is Geometry.Point -> listOf(g.position)
        is Geometry.MultiPoint -> g.positions
        is Geometry.LineString -> g.positions
        is Geometry.MultiLineString -> g.lines.flatten()
        is Geometry.Polygon -> g.rings.flatten()
        is Geometry.MultiPolygon -> g.polygons.flatMap { it.rings.flatten() }
        is Geometry.GeometryCollection -> g.geometries.flatMap { positions(it) }
    }

    private fun entities(g: Geometry, layer: String, xy: (LngLat) -> Pair<Double, Double>, out: StringBuilder) {
        when (g) {
            is Geometry.Point -> point(out, layer, xy(g.position), g.position.alt)
            is Geometry.MultiPoint -> g.positions.forEach { point(out, layer, xy(it), it.alt) }
            is Geometry.LineString -> polyline(out, layer, g.positions.map(xy), closed = false)
            is Geometry.MultiLineString -> g.lines.forEach { polyline(out, layer, it.map(xy), closed = false) }
            is Geometry.Polygon -> g.rings.forEach { polyline(out, layer, open(it).map(xy), closed = true) }
            is Geometry.MultiPolygon -> g.polygons.forEach { p -> p.rings.forEach { polyline(out, layer, open(it).map(xy), closed = true) } }
            is Geometry.GeometryCollection -> g.geometries.forEach { entities(it, layer, xy, out) }
        }
    }

    private fun midLine(g: Geometry?): LngLat? = when (g) {
        is Geometry.LineString -> Geodesy.midpoint(g.positions)
        is Geometry.MultiLineString -> g.lines.maxByOrNull { Geodesy.length(it) }?.let(Geodesy::midpoint)
        is Geometry.Polygon -> g.rings.firstOrNull()?.let(Geodesy::midpoint)
        else -> null
    }

    /** A ring without its repeated last vertex; the closed flag joins it up. */
    private fun open(ring: List<LngLat>) = if (ring.size > 1 && ring.first() == ring.last()) ring.dropLast(1) else ring

    private fun point(out: StringBuilder, layer: String, p: Pair<Double, Double>, z: Double?) {
        out.group(0, "POINT").group(8, layer).group(10, p.first.fmt()).group(20, p.second.fmt()).group(30, (z ?: 0.0).fmt())
    }

    private fun polyline(out: StringBuilder, layer: String, pts: List<Pair<Double, Double>>, closed: Boolean) {
        if (pts.size < 2) return
        out.group(0, "POLYLINE").group(8, layer).group(66, "1").group(10, "0").group(20, "0").group(30, "0").group(70, if (closed) "1" else "0")
        for ((x, y) in pts) out.group(0, "VERTEX").group(8, layer).group(10, x.fmt()).group(20, y.fmt()).group(30, "0")
        out.group(0, "SEQEND").group(8, layer)
    }

    private fun line(out: StringBuilder, layer: String, x1: Double, y1: Double, x2: Double, y2: Double) {
        out.group(0, "LINE").group(8, layer).group(10, x1.fmt()).group(20, y1.fmt()).group(30, "0").group(11, x2.fmt()).group(21, y2.fmt()).group(31, "0")
    }

    private fun text(out: StringBuilder, layer: String, x: Double, y: Double, h: Double, s: String) {
        val one = s.replace('\n', ' ').replace('\r', ' ').take(250)
        out.group(0, "TEXT").group(8, layer).group(10, x.fmt()).group(20, y.fmt()).group(30, "0").group(40, h.fmt()).group(1, one)
    }

    /** Strike line through the point, a tick toward the dip direction and the dip angle beyond it. */
    private fun attitude(out: StringBuilder, x: Double, y: Double, dipDirection: Double, dip: Double) {
        // Map bearings are clockwise from north; CAD's X is east and Y north.
        fun dir(deg: Double) = Math.toRadians(deg).let { sin(it) to cos(it) }
        val (sx, sy) = dir(dipDirection - 90)
        val (dx, dy) = dir(dipDirection)
        line(out, ATTITUDE_LAYER, x - sx * STRIKE_HALF, y - sy * STRIKE_HALF, x + sx * STRIKE_HALF, y + sy * STRIKE_HALF)
        if (dip >= 1 && dip < 89) line(out, ATTITUDE_LAYER, x, y, x + dx * DIP_TICK, y + dy * DIP_TICK)
        text(out, ATTITUDE_LAYER, x + dx * (DIP_TICK + 4), y + dy * (DIP_TICK + 4) - TEXT_HEIGHT * 0.35, TEXT_HEIGHT * 0.7, "${dip.roundToInt()}°")
    }

    /** Nearest of the nine standard AutoCAD colours to a "#rrggbb" colour. */
    internal fun aci(hex: String): Int? {
        val h = hex.removePrefix("#").takeIf { it.length == 6 } ?: return null
        val rgb = h.toLongOrNull(16)?.toInt() ?: return null
        val r = rgb shr 16 and 0xFF; val g = rgb shr 8 and 0xFF; val b = rgb and 0xFF
        val standard = listOf(
            1 to Triple(255, 0, 0), 2 to Triple(255, 255, 0), 3 to Triple(0, 255, 0), 4 to Triple(0, 255, 255),
            5 to Triple(0, 0, 255), 6 to Triple(255, 0, 255), 7 to Triple(255, 255, 255), 8 to Triple(128, 128, 128), 9 to Triple(192, 192, 192),
            // Darker standard shades, so brown and olive lithologies don't all turn grey.
            14 to Triple(128, 0, 0), 54 to Triple(128, 128, 0), 94 to Triple(0, 128, 0), 134 to Triple(0, 128, 128),
            174 to Triple(0, 0, 128), 214 to Triple(128, 0, 128), 30 to Triple(255, 127, 0), 32 to Triple(165, 82, 0),
        )
        return standard.minBy { (_, c) -> (c.first - r) * (c.first - r) + (c.second - g) * (c.second - g) + (c.third - b) * (c.third - b) }.first
    }

    private fun StringBuilder.group(code: Int, value: String): StringBuilder = append(code).append('\n').append(value).append('\n')

    private fun Double.fmt() = String.format(Locale.ROOT, "%.3f", this)
}
