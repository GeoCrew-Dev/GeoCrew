package org.geocrew.core.model

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class RecordShareTest {
    private val at = Instant.parse("2026-11-25T02:30:00Z")

    @Test
    fun pointWithEverything() {
        val f = GeoFeature(
            id = "a", projectId = "p", kind = FeatureKind.GEO_POINT,
            geometry = Geometry.Point(LngLat(116.123456, 40.5, alt = 1234.4)),
            properties = buildProperties {
                this[Props.TITLE] = "D012"
                Attitude.of(125.0, 42.0).writeTo(this)
                this[Props.LITHOLOGY] = "灰岩"
                this["weathering"] = "弱"
                this["track_only"] = "x"
                this[Props.NOTE] = "  中厚层，含燧石结核  "
                put(Props.PHOTOS, kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a".repeat(64)))))
                samples(listOf(Sample("D012-1", "薄片"), Sample("D012-2")))
            },
            createdAt = at, sync = SyncInfo(updatedAt = at),
        )
        val fields = listOf(
            FieldTemplate("weathering", "风化程度"),
            FieldTemplate("track_only", "轨迹用", kinds = listOf(FeatureKind.TRACK.id)),
            FieldTemplate("empty", "空的"),
        )
        val text = RecordShare.text(f, "地质点", CoordinateFormat.DECIMAL, fields, "东山填图", ZoneOffset.ofHours(8))
        assertEquals(
            """
            D012 · 地质点
            位置：116.123456, 40.500000，海拔 1234 m
            产状：125°∠42°
            岩性：灰岩
            风化程度：弱
            样品：D012-1（薄片）、D012-2
            中厚层，含燧石结核
            照片 1 张
            东山填图 · 2026-11-25 10:30
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun lineAndPolygonSayHowBigAndWhere() {
        fun rec(g: Geometry, kind: FeatureKind) = GeoFeature("x", "p", kind, g, createdAt = at, sync = SyncInfo(updatedAt = at))
        val line = RecordShare.text(rec(Geometry.LineString(listOf(LngLat(116.0, 40.0), LngLat(116.0, 40.01))), FeatureKind.LINE), "线", CoordinateFormat.DECIMAL, zone = ZoneOffset.UTC)
        assertTrue(line, line.startsWith("线\n长 1.11 km，起点 116.000000, 40.000000\n"))
        val ring = listOf(LngLat(116.0, 40.0), LngLat(116.01, 40.0), LngLat(116.01, 40.01), LngLat(116.0, 40.01), LngLat(116.0, 40.0))
        val poly = RecordShare.text(rec(Geometry.Polygon(listOf(ring)), FeatureKind.POLYGON), "面", CoordinateFormat.DECIMAL, zone = ZoneOffset.UTC)
        assertTrue(poly, poly.contains("中心 116.005000, 40.005000"))
        assertTrue(poly, poly.endsWith("2026-11-25 02:30"))
    }
}
