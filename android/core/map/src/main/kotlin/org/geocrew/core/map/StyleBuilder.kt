package org.geocrew.core.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.geocrew.core.model.Terrarium
import org.geocrew.core.model.tiles.TileKeys
import org.geocrew.core.model.tiles.TileSource
import org.geocrew.core.model.tiles.TileTemplates

/**
 * Turns a [TileSource] into a MapLibre style. Overlay layers (features, my
 * location, team...) are added on top at runtime by [MapLayers], so a base
 * map switch never touches them.
 */
object StyleBuilder {
    const val BACKGROUND = "#EEE8DD"
    private const val DEM = "hillshade"
    private const val DEM_MAX_ZOOM = 15
    private const val DEM_ATTRIBUTION = "山体阴影：Terrain Tiles（Mapzen，SRTM 等）"

    /**
     * [hillshade] adds relief shading from the public Terrarium DEM over the
     * base map, so slopes show on any source (imagery and OSM have none).
     */
    fun json(source: TileSource, keys: TileKeys, hillshade: Boolean = false): String {
        val key = source.keyId?.let { keys[it] }
        val resolved = source.layers.map { TileTemplates.resolve(it, key) }
        return buildJsonObject {
            put("version", 8)
            putJsonObject("sources") {
                resolved.forEachIndexed { i, l ->
                    putJsonObject("base$i") {
                        put("type", "raster")
                        put("tileSize", l.tileSize)
                        put("minzoom", l.minZoom)
                        put("maxzoom", l.maxZoom)
                        if (l.tms) put("scheme", "tms")
                        source.bounds?.takeIf { it.size == 4 }?.let { b -> put("bounds", JsonArray(b.map(::JsonPrimitive))) }
                        put("tiles", JsonArray(l.urls.map(::JsonPrimitive)))
                        if (i == 0 && source.attribution.isNotBlank()) put("attribution", source.attribution)
                    }
                }
                if (hillshade) putJsonObject(DEM) {
                    put("type", "raster-dem")
                    put("encoding", "terrarium")
                    put("tileSize", Terrarium.SIZE)
                    put("maxzoom", DEM_MAX_ZOOM)
                    put("tiles", JsonArray(listOf(JsonPrimitive(Terrarium.DEFAULT_URL))))
                    put("attribution", DEM_ATTRIBUTION)
                }
            }
            putJsonArray("layers") {
                add(
                    buildJsonObject {
                        put("id", "background")
                        put("type", "background")
                        putJsonObject("paint") { put("background-color", BACKGROUND) }
                    },
                )
                resolved.forEachIndexed { i, l ->
                    add(
                        buildJsonObject {
                            put("id", "base$i")
                            put("type", "raster")
                            put("source", "base$i")
                            putJsonObject("paint") { put("raster-opacity", l.opacity) }
                        },
                    )
                }
                if (hillshade) add(
                    buildJsonObject {
                        put("id", DEM)
                        put("type", "hillshade")
                        put("source", DEM)
                        putJsonObject("paint") {
                            put("hillshade-exaggeration", 0.45)
                            put("hillshade-shadow-color", "#3E2F1C")
                            put("hillshade-highlight-color", "#FFFDF5")
                            put("hillshade-illumination-direction", 315)
                        }
                    },
                )
            }
        }.toString()
    }

    /** Hosts the base map requests go to; used to attribute tile errors to a source. */
    fun hosts(source: TileSource): Set<String> = source.layers.flatMap { l ->
        val subs = l.subdomains.ifEmpty { listOf("") }
        subs.mapNotNull { s -> hostOf(l.url.replace("{s}", s)) }
    }.toSet()

    internal fun hostOf(url: String): String? = Regex("^https?://([^/:?#]+)").find(url)?.groupValues?.get(1)?.lowercase()

}
