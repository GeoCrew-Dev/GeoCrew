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
  @Test fun teamTrailsAreLinesBehindTheDots() {
    val walked = TeamMarker("m", "甲", LngLat(116.002, 40.0), null, false, trail = listOf(LngLat(116.0, 40.0), LngLat(116.001, 40.0)))
    val still = TeamMarker("n", "乙", LngLat(116.0, 40.0), null, true)
    val fs = Json.parseToJsonElement(OverlayGeoJson.team(listOf(walked, still))).jsonObject["features"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf("LineString", "Point", "Point"), fs.map { it["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content })
    assertEquals(3, fs[0]["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.size)
    assertEquals("乙", fs[2]["properties"]!!.jsonObject["name"]!!.jsonPrimitive.content)
  }
  @Test fun myOpenTasksAreMarked() {
    fun f(id: String, who: String, st: TaskStatus) = GeoFeature(id,"p",FeatureKind.POINT, Geometry.Point(LngLat(116.0,40.0)),
      buildProperties { TaskInfo(who, "x", st).writeTo(this) }, Instant.EPOCH, SyncInfo(updatedAt = Instant.EPOCH))
    val list = listOf(f("a","me",TaskStatus.TODO), f("b","me",TaskStatus.DONE), f("c","other",TaskStatus.DOING))
    val props = Json.parseToJsonElement(OverlayGeoJson.features(list, null, "me")).jsonObject["features"]!!.jsonArray
      .map { it.jsonObject["properties"]!!.jsonObject }
    assertEquals(listOf(true, false, false), props.map { it["mytask"]?.jsonPrimitive?.boolean == true })
    val none = Json.parseToJsonElement(OverlayGeoJson.features(list, null, null)).jsonObject["features"]!!.jsonArray
    assertTrue(none.none { it.jsonObject["properties"]!!.jsonObject.containsKey("mytask") })
  }
  @Test fun simpleStyleIsCopiedOnlyWhenValid() {
    val props = buildJsonObject { put("fill", "#22A884"); put("stroke", "red; drop"); put("fill-opacity", 0.5); put("stroke-width", 99) }
    val f = GeoFeature("b","p",FeatureKind.POLYGON, Geometry.Polygon.of(listOf(LngLat(1.0,1.0),LngLat(2.0,1.0),LngLat(2.0,2.0))), props, Instant.EPOCH, SyncInfo(updatedAt = Instant.EPOCH))
    val p = Json.parseToJsonElement(OverlayGeoJson.features(listOf(f), null)).jsonObject["features"]!!.jsonArray[0].jsonObject["properties"]!!.jsonObject
    assertEquals("#22A884", p["fill"]!!.jsonPrimitive.content)
    assertEquals(0.5, p["fill-opacity"]!!.jsonPrimitive.double, 0.0)
    assertNull(p["stroke"]); assertNull(p["stroke-width"])
  }
  @Test fun legendColoursRecordsWithoutTheirOwnColour() {
    val legend = Legend(Props.LITHOLOGY, listOf(LegendEntry("砂岩", "#FFEE58")))
    val ring = Geometry.Polygon.of(listOf(LngLat(1.0,1.0),LngLat(2.0,1.0),LngLat(2.0,2.0)))
    val plain = GeoFeature("a","p",FeatureKind.POLYGON, ring, buildJsonObject { put(Props.LITHOLOGY, "砂岩") }, Instant.EPOCH, SyncInfo(updatedAt = Instant.EPOCH))
    val own = GeoFeature("b","p",FeatureKind.POLYGON, ring, buildJsonObject { put(Props.LITHOLOGY, "砂岩"); put("fill", "#22A884") }, Instant.EPOCH, SyncInfo(updatedAt = Instant.EPOCH))
    val ps = Json.parseToJsonElement(OverlayGeoJson.features(listOf(plain, own), null, null, legend)).jsonObject["features"]!!.jsonArray
      .map { it.jsonObject["properties"]!!.jsonObject }
    assertEquals("#FFEE58", ps[0]["fill"]!!.jsonPrimitive.content)
    assertEquals("#FFEE58", ps[0]["stroke"]!!.jsonPrimitive.content)
    assertEquals(0.45, ps[0]["fill-opacity"]!!.jsonPrimitive.double, 0.0)
    assertEquals("#22A884", ps[1]["fill"]!!.jsonPrimitive.content)
    assertNull(ps[1]["stroke"])
  }
}
