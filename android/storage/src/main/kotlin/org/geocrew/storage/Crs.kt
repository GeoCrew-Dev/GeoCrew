package org.geocrew.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.geocrew.nativelib.Coordinates

/**
 * CRSs and coordinates between them (ADR 0015), through GeoCrew-Native's PROJ.
 * A CRS is named as GDAL reads it (EPSG:4549, WKT, a PROJ string); x is the
 * longitude or easting. Call [NativeData.setup] first.
 */
object Crs {
    /** What the map and GNSS give: CGCS2000 longitude and latitude. */
    const val LONGITUDE_LATITUDE = "EPSG:4490"

    /** Whether [a] and [b] name the same CRS as written; different spellings of one CRS count as different. */
    fun same(a: String?, b: String?): Boolean = a.equals(b, ignoreCase = true)

    /** What [srs] is: its name, id, whether geographic or projected, and unit. Throws for a CRS GDAL does not know. */
    fun describe(srs: String): JsonObject = Json.parseToJsonElement(Coordinates.describe(srs)).jsonObject

    /** [xy] (x0, y0, x1, y1, ...) from [from] to [to]; NaN for a pair PROJ cannot transform. */
    fun transform(from: String, to: String, xy: DoubleArray): DoubleArray =
        if (same(from, to)) xy.copyOf() else Coordinates.transform(from, to, xy)

    /**
     * [geometries] (GeoJSON of any type, or null) from [from] to [to], in one
     * batch; a third coordinate, such as elevation, stays as it was. A geometry
     * with a position PROJ cannot transform comes back as null.
     */
    fun transform(geometries: List<JsonObject?>, from: String, to: String): List<JsonObject?> {
        if (same(from, to)) return geometries
        val xy = ArrayList<Double>()
        fun collect(e: JsonElement?) {
            val array = e as? JsonArray ?: return
            if (array.firstOrNull() is JsonPrimitive) {
                xy += (array[0] as JsonPrimitive).doubleOrNull ?: Double.NaN
                xy += (array.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: Double.NaN
            } else {
                array.forEach(::collect)
            }
        }
        geometries.forEach { collect(it?.get("coordinates")) }
        val moved = Coordinates.transform(from, to, xy.toDoubleArray())
        var next = 0
        var lost = false
        fun rebuild(e: JsonElement): JsonElement {
            val array = e as? JsonArray ?: return e
            if (array.firstOrNull() is JsonPrimitive) {
                val x = moved[next]
                val y = moved[next + 1]
                next += 2
                if (!x.isFinite() || !y.isFinite()) lost = true
                return JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y)) + array.drop(2))
            }
            return JsonArray(array.map(::rebuild))
        }
        return geometries.map { g ->
            val coordinates = g?.get("coordinates") ?: return@map g
            lost = false
            val rebuilt = rebuild(coordinates)
            if (lost) null else JsonObject(g + ("coordinates" to rebuilt))
        }
    }
}
