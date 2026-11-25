package org.geocrew.core.data.team

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.geocrew.core.model.Ellipsoid
import org.geocrew.core.model.LocalGrid
import org.geocrew.core.model.tiles.SharedBasemap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the shared settings keys (protocol v0 §2.2) are read and written. */
class TeamFormsCodecTest {
    private fun settings(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun basemapsSkipKeyedInvalidAndRepeatedEntries() {
        val s = settings(
            """
            {"basemaps": [
              {"id": "geo", "name": "地质图", "url": "https://g.example.org/wms?BBOX={bbox-epsg-3857}", "max_zoom": 16},
              {"id": "geo", "name": "重复", "url": "https://g.example.org/{z}/{x}/{y}.png"},
              {"id": "tdt", "name": "带 Key", "url": "https://t.example.org/{z}/{x}/{y}?tk={key}"},
              {"id": "bad", "name": "坏地址", "url": "file:///{z}/{x}/{y}"},
              {"id": "nosub", "name": "缺子域名", "url": "https://{s}.example.org/{z}/{x}/{y}.png"},
              {"name": "没有 id"},
              "不是对象",
              {"id": "osm2", "name": "OSM 镜像", "url": "https://{s}.example.org/{z}/{x}/{-y}.png", "subdomains": ["a", "b"], "attribution": "© OSM", "future": 1}
            ]}
            """,
        )
        val list = TeamForms.decodeBasemaps(s)
        assertEquals(listOf("geo", "osm2"), list.map { it.id })
        assertEquals(16, list[0].maxZoom)
        assertEquals(listOf("a", "b"), list[1].subdomains)
        assertTrue(TeamForms.decodeBasemaps(settings("""{"basemaps": {}}""")).isEmpty())
        assertTrue(TeamForms.decodeBasemaps(settings("""{}""")).isEmpty())
    }

    @Test
    fun basemapsRoundTripWithProtocolNames() {
        val b = SharedBasemap("geo", "地质图", "https://g.example.org/{z}/{x}/{y}.png", maxZoom = 15, tms = true)
        val arr = TeamForms.encodeBasemaps(listOf(b))
        val text = arr.toString()
        assertTrue(text, "\"max_zoom\":15" in text)
        assertEquals(listOf(b), TeamForms.decodeBasemaps(JsonObject(mapOf(TeamForms.BASEMAPS_KEY to arr))))
    }

    @Test
    fun gridMustBePlausible() {
        val g = LocalGrid("北京 54", Ellipsoid.BEIJING54, 3, 39)
        val ok = JsonObject(mapOf(TeamForms.GRID_KEY to TeamForms.encodeGrid(g)))
        assertEquals(g, TeamForms.decodeGrid(ok))
        val bad = JsonObject(mapOf(TeamForms.GRID_KEY to TeamForms.encodeGrid(g.copy(width = 5))))
        assertNull(TeamForms.decodeGrid(bad))
        assertNull(TeamForms.decodeGrid(settings("""{"local_grid": null}""")))
    }

    @Test
    fun overlaysNeedAFileHashAndFourCorners() {
        val hash = "a".repeat(64)
        val s = settings(
            """
            {"overlays": [
              {"id": "o1", "name": "1:5万地质图", "file": "$hash", "type": "image/jpeg", "width": 100, "height": 80,
               "corners": [[100,30],[101,30],[101,29],[100,29]], "control_points": 3},
              {"id": "o2", "name": "坏哈希", "file": "abc", "type": "image/jpeg", "width": 1, "height": 1,
               "corners": [[100,30],[101,30],[101,29],[100,29]]},
              {"id": "o3", "name": "三个角", "file": "$hash", "type": "image/png", "width": 1, "height": 1,
               "corners": [[100,30],[101,30],[101,29]]}
            ]}
            """,
        )
        val list = TeamForms.decodeOverlays(s)
        assertEquals(listOf("o1"), list.map { it.id })
        assertEquals(3, list[0].controlPoints)
    }

    @Test
    fun phrasesAreCleanedOnTheWayIn() {
        val s = settings(
            """{"phrases": [{"name": "地层", "items": ["蓟县系", " 蓟县系 ", ""]}, {"name": "", "items": ["x"]}, {"name": "空", "items": []}, 3]}""",
        )
        assertEquals(listOf(org.geocrew.core.model.PhraseGroup("地层", listOf("蓟县系"))), TeamForms.decodePhrases(s))
    }

    @Test
    fun noticeRoundTripsAndBlankMeansNone() {
        val n = org.geocrew.core.model.TeamNotice.of("  今天走 3 号路线，17:00 前回营地  ", java.time.Instant.parse("2026-11-25T01:02:03.456Z"), "老王")!!
        assertEquals("今天走 3 号路线，17:00 前回营地", n.text)
        assertEquals("2026-11-25T01:02:03Z", n.updatedAt)
        val encoded = TeamForms.encodeNotice(n).toString()
        assertEquals("""{"text":"今天走 3 号路线，17:00 前回营地","updated_at":"2026-11-25T01:02:03Z","by":"老王"}""", encoded)
        assertEquals(n, TeamForms.decodeNotice(settings("""{"notice": $encoded}""")))
        assertNull(TeamForms.decodeNotice(settings("""{"notice": {"text": "  ", "updated_at": "2026-11-25T01:02:03Z"}}""")))
        assertNull(TeamForms.decodeNotice(settings("""{"notice": "文字"}""")))
        assertNull(TeamForms.decodeNotice(settings("""{}""")))
        assertNull(org.geocrew.core.model.TeamNotice.of("", java.time.Instant.EPOCH, null))
        val long = TeamForms.decodeNotice(settings("""{"notice": {"text": "${"字".repeat(2100)}", "updated_at": "x", "by": ""}}"""))!!
        assertEquals(2000, long.text.length)
        assertNull(long.by)
        assertNull(long.postedAt)
    }
}
