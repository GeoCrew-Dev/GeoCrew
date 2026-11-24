package org.geocrew.core.gis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.string
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class ExternalToolTest {
    private val at = Instant.parse("2026-11-24T00:00:00Z")

    private val descriptor = """
        {
          "api": 1, "id": "slope", "name": "坡度分析", "description": "按 DEM 算坡度",
          "accepts": ["point", "polygon"], "min_features": 2,
          "params": [
            {"type": "field", "key": "elev", "label": "高程属性", "required": true},
            {"type": "number", "key": "cell", "label": "网格", "default": 10, "min": 1, "unit": "m"},
            {"type": "choice", "key": "unit", "label": "单位", "options": [{"value": "deg", "label": "度"}, {"value": "pct", "label": "百分比"}], "default": "pct"},
            {"type": "feature", "key": "area", "label": "范围", "types": ["polygon"]}
          ]
        }
    """.trimIndent()

    private fun point(id: String, lng: Double, elev: Double) =
        GeoFeature(id, "p", FeatureKind.GEO_POINT, Geometry.Point(LngLat(lng, 40.0)), buildProperties {
            this[Props.TITLE] = id
            this["elev"] = elev
        }, at, SyncInfo(updatedAt = at))

    private val line = GeoFeature("L", "p", FeatureKind.LINE, Geometry.LineString(listOf(LngLat(116.0, 40.0), LngLat(116.1, 40.0))),
        buildProperties { }, at, SyncInfo(updatedAt = at))

    @Test fun readsTheDescriptorIntoParameters() {
        val d = ExternalTool.parseDescriptor(descriptor)
        assertEquals("slope", d.id)
        assertEquals(setOf(GeometryType.POINT, GeometryType.POLYGON), d.accepts)
        assertEquals(4, d.params.size)
        assertEquals(setOf("elev"), d.required)
        val n = d.params[1] as ToolParam.Number
        assertEquals(10.0, n.default!!, 0.0)
        assertEquals("m", n.unit)
        assertEquals("pct", (d.params[2] as ToolParam.Choice).default)
        assertEquals(setOf(GeometryType.POLYGON), (d.params[3] as ToolParam.Feature).types)
    }

    @Test fun rejectsBrokenDescriptors() {
        for (bad in listOf("[]", """{"name":"x"}""", """{"id":"Bad Id","name":"x"}""", """{"id":"a","name":"x","api":99}""",
            """{"id":"a","name":"x","params":[{"type":"color","key":"c"}]}""", """{"id":"a","name":"x","params":[{"type":"number","key":"k"},{"type":"number","key":"k"}]}""")) {
            try {
                ExternalTool.parseDescriptor(bad)
                fail("accepted $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }

    @Test fun sendsAcceptedRecordsAndValuesAndReadsTheResult() {
        var sent: String? = null
        val tool = ExternalTool(ExternalTool.parseDescriptor(descriptor), "com.example.dem", "DEM 工具") { _, input, _ ->
            sent = input
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[116.0,40.0]},"properties":{"kind":"geo_point","slope":12.5}},
              {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[116,40],[116.01,40],[116.01,40.01],[116,40]]]},"properties":{"kind":"track"}},
              {"type":"Feature","geometry":null,"properties":{}}
            ],"geocrew":{"summary":"平均坡度 12.5°","legend":[{"color":"#FF0000","label":"陡"},{"color":"red","label":"坏"}]}}
            """.trimIndent()
        }
        val input = ToolInput(listOf(point("A", 116.0, 1200.0), point("B", 116.01, 1250.0), line), mapOf("elev" to "elev", "unit" to "deg", "other" to "x"))
        assertNull(tool.check(input))
        val out = tool.run(input)

        val root = Json.parseToJsonElement(sent!!).jsonObject
        assertEquals(2, root["features"]!!.jsonArray.size) // the line is not accepted
        val meta = root[ExternalTool.MEMBER]!!.jsonObject
        assertEquals("slope", meta["tool"]!!.jsonPrimitive.content)
        assertEquals("deg", meta["params"]!!.jsonObject["unit"]!!.jsonPrimitive.content)
        assertNull(meta["params"]!!.jsonObject["other"])

        assertEquals("com.example.dem/slope", tool.id)
        assertEquals(2, out.features.size)
        assertEquals(FeatureKind.GEO_POINT, out.features[0].kind)
        assertEquals(FeatureKind.POLYGON, out.features[1].kind) // "track" does not fit a polygon
        assertEquals("com.example.dem/slope", out.features[0].properties.string(OutputProps.TOOL))
        assertNull(out.features[0].properties["kind"])
        assertTrue(out.summary.startsWith("平均坡度 12.5°"))
        assertTrue(out.summary.contains("1 条"))
        assertEquals(listOf(LegendEntry("#FF0000", "陡")), out.legend)
    }

    @Test fun checksRequiredValuesAndCounts() {
        val tool = ExternalTool(ExternalTool.parseDescriptor(descriptor), "a", "A") { _, _, _ -> "{}" }
        assertNotNull(tool.check(ToolInput(listOf(point("A", 116.0, 1.0)), mapOf("elev" to "elev"))))
        assertEquals("请填写「高程属性」", tool.check(ToolInput(listOf(point("A", 116.0, 1.0), point("B", 116.1, 2.0)))))
    }

    @Test fun reportsThePluginsError() {
        try {
            ExternalTool.decodeOutput("""{"type":"FeatureCollection","features":[],"geocrew":{"error":"找不到 DEM"}}""", "x/y")
            fail()
        } catch (e: IllegalArgumentException) {
            assertEquals("插件报错：找不到 DEM", e.message)
        }
    }
}
