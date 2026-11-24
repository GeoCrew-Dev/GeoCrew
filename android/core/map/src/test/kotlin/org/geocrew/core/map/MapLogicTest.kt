package org.geocrew.core.map
import kotlinx.serialization.json.*
import org.geocrew.core.model.*
import org.geocrew.core.model.tiles.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class MapLogicTest {
  @Test fun style() {
    val j = Json.parseToJsonElement(StyleBuilder.json(BuiltInSources.TIANDITU_IMAGE, TileKeys(mapOf("tianditu" to "KEY")))).jsonObject
    assertEquals(3, j["layers"]!!.jsonArray.size)
    val tiles = j["sources"]!!.jsonObject["base1"]!!.jsonObject["tiles"]!!.jsonArray
    assertEquals(8, tiles.size); assertTrue(tiles[0].jsonPrimitive.content.contains("LAYER=cia") && tiles[0].jsonPrimitive.content.endsWith("tk=KEY"))
    assertEquals(setOf("t0.tianditu.gov.cn","t1.tianditu.gov.cn","t2.tianditu.gov.cn","t3.tianditu.gov.cn","t4.tianditu.gov.cn","t5.tianditu.gov.cn","t6.tianditu.gov.cn","t7.tianditu.gov.cn"), StyleBuilder.hosts(BuiltInSources.TIANDITU_IMAGE))
    Json.parseToJsonElement(StyleBuilder.json(BuiltInSources.BLANK, TileKeys(emptyMap())))
  }
  @Test fun overlays() {
    val f = GeoFeature("a","p",FeatureKind.POINT, Geometry.Point(LngLat(116.0,40.0)), JsonObject(emptyMap()), Instant.EPOCH, SyncInfo(updatedAt = Instant.EPOCH))
    val fc = Json.parseToJsonElement(OverlayGeoJson.features(listOf(f), "a")).jsonObject
    val p = fc["features"]!!.jsonArray[0].jsonObject["properties"]!!.jsonObject
    assertEquals("a", p["fid"]!!.jsonPrimitive.content); assertEquals(true, p["selected"]!!.jsonPrimitive.boolean)
    val me = Json.parseToJsonElement(OverlayGeoJson.me(MyLocation(LngLat(116.0,40.0), 30f, null))).jsonObject
    assertEquals(2, me["features"]!!.jsonArray.size)
    val sk = Json.parseToJsonElement(OverlayGeoJson.sketch(listOf(LngLat(1.0,1.0),LngLat(2.0,1.0),LngLat(2.0,2.0)), true)).jsonObject
    assertEquals(4, sk["features"]!!.jsonArray.size)
  }
  @Test fun simpleStyleIsCopiedOnlyWhenValid() {
    val props = buildJsonObject { put("fill", "#22A884"); put("stroke", "red; drop"); put("fill-opacity", 0.5); put("stroke-width", 99) }
    val f = GeoFeature("b","p",FeatureKind.POLYGON, Geometry.Polygon.of(listOf(LngLat(1.0,1.0),LngLat(2.0,1.0),LngLat(2.0,2.0))), props, Instant.EPOCH, SyncInfo(updatedAt = Instant.EPOCH))
    val p = Json.parseToJsonElement(OverlayGeoJson.features(listOf(f), null)).jsonObject["features"]!!.jsonArray[0].jsonObject["properties"]!!.jsonObject
    assertEquals("#22A884", p["fill"]!!.jsonPrimitive.content)
    assertEquals(0.5, p["fill-opacity"]!!.jsonPrimitive.double, 0.0)
    assertNull(p["stroke"]); assertNull(p["stroke-width"])
  }
}
