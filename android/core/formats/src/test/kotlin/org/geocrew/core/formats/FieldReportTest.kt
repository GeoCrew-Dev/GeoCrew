package org.geocrew.core.formats

import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.FieldTemplate
import org.geocrew.core.model.FieldType
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class FieldReportTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun feature(id: String, kind: FeatureKind, at: String, geometry: Geometry?, props: org.geocrew.core.model.PropertiesBuilder.() -> Unit = {}) =
        GeoFeature(id, "p", kind, geometry, buildProperties(props), Instant.parse(at), SyncInfo(updatedAt = Instant.parse(at)))

    private val features = listOf(
        feature("b", FeatureKind.GEO_POINT, "2026-11-24T02:30:00Z", Geometry.Point(LngLat(116.0, 40.0, 812.4))) {
            this[Props.TITLE] = "D002"
            this[Props.LITHOLOGY] = "灰岩"
            this[Props.DIP_DIRECTION] = 120.0
            this[Props.DIP] = 35.0
            this["sample_no"] = "S-7"
            this[Props.NOTE] = "见腕足化石"
        },
        feature("a", FeatureKind.POINT, "2026-11-24T01:00:00Z", Geometry.Point(LngLat(116.0, 40.0))) { this[Props.TITLE] = "D001" },
        feature("t", FeatureKind.TRACK, "2026-11-24T00:00:00Z", Geometry.LineString(listOf(LngLat(116.0, 40.0), LngLat(116.01, 40.0)))),
        // 23:30 UTC is already the next day in Beijing.
        feature("c", FeatureKind.LINE, "2026-11-24T23:30:00Z", Geometry.LineString(listOf(LngLat(116.0, 40.0), LngLat(116.0, 40.001)))),
    )

    private val fields = listOf(FieldTemplate("sample_no", "样品编号", FieldType.TEXT, kinds = listOf("geo_point")))

    @Test fun groupsByLocalDayInTimeOrder() {
        val r = FieldReport.build("秦岭", features, fields, { it.id }, zone, CoordinateFormat.DECIMAL, generatedAt = Instant.parse("2026-11-25T00:00:00Z"))
        assertEquals("秦岭 · 野外记录", r.title)
        assertEquals(listOf(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 25)), r.days.map { it.date })
        assertEquals(listOf("a", "b"), r.days[0].entries.map { it.featureId })
        assertEquals(3, r.entryCount)
        assertTrue(r.subtitle, r.subtitle.startsWith("2026 年 11 月 24 日 至 2026 年 11 月 25 日，共 3 条"))
    }

    @Test fun entryFacts() {
        val r = FieldReport.build("秦岭", features, fields, { if (it == FeatureKind.GEO_POINT) "地质点" else it.id }, zone, CoordinateFormat.DECIMAL)
        val d2 = r.days[0].entries[1]
        assertEquals("D002 · 地质点（10:30）", d2.heading)
        val facts = d2.facts.toMap()
        assertEquals("116.000000, 40.000000", facts["位置"])
        assertEquals("812 m", facts["海拔"])
        assertEquals("120°∠35°", facts["产状"])
        assertEquals("灰岩", facts["岩性"])
        assertEquals("S-7", facts["样品编号"])
        assertEquals("见腕足化石", d2.note)
        val line = r.days[1].entries.single()
        assertTrue(line.facts.toMap().containsKey("长度"))
    }

    @Test fun filtersDaysAndTracks() {
        val only = FieldReport.build("秦岭", features, fields, { it.id }, zone, days = setOf(LocalDate.of(2026, 11, 25)))
        assertEquals(listOf("c"), only.days.flatMap { it.entries }.map { it.featureId })
        val withTracks = FieldReport.build("秦岭", features, fields, { it.id }, zone, includeTracks = true)
        assertEquals(4, withTracks.entryCount)
        val none = FieldReport.build("秦岭", emptyList(), fields, { it.id }, zone)
        assertTrue(none.subtitle.startsWith("没有记录"))
    }

    @Test fun sectionFacts() {
        val sec = org.geocrew.core.model.MeasuredSection(
            listOf(org.geocrew.core.model.TraverseLeg(90.0, 0.0, 20.0)),
            listOf(
                org.geocrew.core.model.SectionLayer("1", 10.0, "灰岩", org.geocrew.core.model.Attitude(90.0, 30.0)),
                org.geocrew.core.model.SectionLayer("2", 20.0),
            ),
        )
        val f = feature("s", FeatureKind.SECTION, "2026-11-24T02:00:00Z", null) {
            this[Props.TITLE] = "P1"
            put(Props.SECTION, sec.toJson())
        }
        val facts = FieldReport.entry(f, emptyList(), { "实测剖面" }, zone, CoordinateFormat.DECIMAL).facts.toMap()
        assertEquals("1 段，共 20.0 m", facts["导线"])
        assertEquals("1 灰岩 5.0 m；2 5.0 m", facts["分层"])
        assertEquals("10.0 m", facts["总厚度"])
    }
}
