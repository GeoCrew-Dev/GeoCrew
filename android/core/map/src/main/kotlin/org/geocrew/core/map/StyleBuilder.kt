package org.geocrew.core.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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

    fun json(source: TileSource, keys: TileKeys): String {
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
                        put("tiles", JsonArray(l.urls.map(::JsonPrimitive)))
                        if (i == 0 && source.attribution.isNotBlank()) put("attribution", source.attribution)
                    }
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
