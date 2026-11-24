package org.geocrew.core.formats

import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Well-known binary geometry (OGC Simple Features), as GeoPackage and
 * PostGIS store it. Writes little-endian 2D, or 3D when any coordinate has
 * an altitude; reads either byte order, 2D, Z, M and ZM in both the ISO
 * (1001…) and the extended (high flag bits) codes.
 */
internal object Wkb {
    class Envelope(var minX: Double = Double.POSITIVE_INFINITY, var minY: Double = Double.POSITIVE_INFINITY,
                   var maxX: Double = Double.NEGATIVE_INFINITY, var maxY: Double = Double.NEGATIVE_INFINITY) {
        fun add(x: Double, y: Double) {
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        fun add(e: Envelope) { if (!e.empty) { add(e.minX, e.minY); add(e.maxX, e.maxY) } }
        val empty get() = minX > maxX
    }

    private const val POINT = 1
    private const val LINE = 2
    private const val POLYGON = 3
    private const val MULTI_POINT = 4
    private const val MULTI_LINE = 5
    private const val MULTI_POLYGON = 6
    private const val COLLECTION = 7

    // --- writing --------------------------------------------------------------

    /** The geometry as WKB, and its envelope. */
    fun encode(g: Geometry): Pair<ByteArray, Envelope> {
        val z = positions(g).any { it.alt != null }
        val out = Out()
        val env = Envelope()
        write(g, z, out, env)
        return out.bytes() to env
    }

    private fun positions(g: Geometry): Sequence<LngLat> = when (g) {
        is Geometry.Point -> sequenceOf(g.position)
        is Geometry.MultiPoint -> g.positions.asSequence()
        is Geometry.LineString -> g.positions.asSequence()
        is Geometry.MultiLineString -> g.lines.asSequence().flatten()
        is Geometry.Polygon -> g.rings.asSequence().flatten()
        is Geometry.MultiPolygon -> g.polygons.asSequence().flatMap { it.rings.asSequence().flatten() }
        is Geometry.GeometryCollection -> g.geometries.asSequence().flatMap(::positions)
    }

    private fun write(g: Geometry, z: Boolean, out: Out, env: Envelope) {
        fun head(type: Int) {
            out.byte(1)
            out.int(if (z) type + 1000 else type)
        }
        fun xy(p: LngLat) {
            out.double(p.lng)
            out.double(p.lat)
            if (z) out.double(p.alt ?: 0.0)
            env.add(p.lng, p.lat)
        }
        fun points(ps: List<LngLat>) {
            out.int(ps.size)
            ps.forEach(::xy)
        }
        fun rings(rs: List<List<LngLat>>) {
            out.int(rs.size)
            rs.forEach(::points)
        }
        when (g) {
            is Geometry.Point -> { head(POINT); xy(g.position) }
            is Geometry.LineString -> { head(LINE); points(g.positions) }
            is Geometry.Polygon -> { head(POLYGON); rings(g.rings) }
            is Geometry.MultiPoint -> { head(MULTI_POINT); out.int(g.positions.size); g.positions.forEach { write(Geometry.Point(it), z, out, env) } }
            is Geometry.MultiLineString -> { head(MULTI_LINE); out.int(g.lines.size); g.lines.forEach { write(Geometry.LineString(it), z, out, env) } }
            is Geometry.MultiPolygon -> { head(MULTI_POLYGON); out.int(g.polygons.size); g.polygons.forEach { write(it, z, out, env) } }
            is Geometry.GeometryCollection -> { head(COLLECTION); out.int(g.geometries.size); g.geometries.forEach { write(it, z, out, env) } }
        }
    }

    private class Out {
        private var b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        private fun room(n: Int) {
            if (b.remaining() < n) {
                val bigger = ByteBuffer.allocate(maxOf(b.capacity() * 2, b.position() + n)).order(ByteOrder.LITTLE_ENDIAN)
                b.flip()
                bigger.put(b)
                b = bigger
            }
        }
        fun byte(v: Int) { room(1); b.put(v.toByte()) }
        fun int(v: Int) { room(4); b.putInt(v) }
        fun double(v: Double) { room(8); b.putDouble(v) }
        fun bytes(): ByteArray = b.array().copyOf(b.position())
    }

    // --- reading --------------------------------------------------------------

    /**
     * Reads one geometry from [bytes] at [offset], mapping each (x, y, z) to a
     * position with [at]. Throws FormatException on anything malformed.
     */
    fun decode(bytes: ByteArray, offset: Int = 0, at: (Double, Double, Double?) -> LngLat): Geometry {
        val b = ByteBuffer.wrap(bytes)
        b.position(offset)
        return try {
            read(b, at, depth = 0)
        } catch (e: java.nio.BufferUnderflowException) {
            throw FormatException("几何数据不完整", e)
        } catch (e: IndexOutOfBoundsException) {
            throw FormatException("几何数据不完整", e)
        }
    }

    private fun read(b: ByteBuffer, at: (Double, Double, Double?) -> LngLat, depth: Int): Geometry {
        if (depth > 8) throw FormatException("几何嵌套过深")
        b.order(if (b.get().toInt() == 0) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val code = b.int
        val ewkbZ = code and 0x80000000.toInt() != 0
        val ewkbM = code and 0x40000000 != 0
        if (code and 0x20000000 != 0) b.int // EWKB SRID, not used
        val iso = (code and 0x0FFFFFFF)
        val type = iso % 1000
        val dims = iso / 1000 // 0 xy, 1 z, 2 m, 3 zm
        val hasZ = ewkbZ || dims == 1 || dims == 3
        val hasM = ewkbM || dims == 2 || dims == 3
        fun count(): Int = b.int.also { if (it < 0 || it > b.remaining()) throw FormatException("几何数据不完整") }
        fun pos(): LngLat {
            val x = b.double
            val y = b.double
            val z = if (hasZ) b.double else null
            if (hasM) b.double
            return at(x, y, z?.takeIf { it.isFinite() })
        }
        fun points() = List(count()) { pos() }
        fun child(): Geometry = read(b, at, depth + 1)
        return when (type) {
            POINT -> Geometry.Point(pos())
            LINE -> Geometry.LineString(points())
            POLYGON -> Geometry.Polygon(List(count()) { points() })
            MULTI_POINT -> Geometry.MultiPoint(List(count()) { (child() as? Geometry.Point ?: throw FormatException("多点里有非点几何")).position })
            MULTI_LINE -> Geometry.MultiLineString(List(count()) { (child() as? Geometry.LineString ?: throw FormatException("多线里有非线几何")).positions })
            MULTI_POLYGON -> Geometry.MultiPolygon(List(count()) { child() as? Geometry.Polygon ?: throw FormatException("多面里有非面几何") })
            COLLECTION -> Geometry.GeometryCollection(List(count()) { child() })
            else -> throw FormatException("不支持的几何类型 $iso（曲线等）")
        }
    }
}
