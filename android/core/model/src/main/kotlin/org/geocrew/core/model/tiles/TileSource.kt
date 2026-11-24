package org.geocrew.core.model.tiles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A base map: one or more raster tile layers drawn bottom to top (for example
 * imagery plus a label overlay). Built-in providers and user-added XYZ/WMTS
 * sources share this one shape, so the map never special-cases a provider.
 *
 * URL templates use `{z}`, `{x}`, `{y}` (or `{-y}` for TMS rows), an optional
 * `{s}` that expands over [TileLayer.subdomains], and an optional `{key}` that
 * is filled from the key the user stored for [keyId].
 */
@Serializable
data class TileSource(
    val id: String,
    val name: String,
    val layers: List<TileLayer>,
    val attribution: String = "",
    val minZoom: Int = 0,
    val maxZoom: Int = 18,
    /** Which stored API key `{key}` refers to, e.g. "tianditu"; null when no key is needed. */
    val keyId: String? = null,
    /** True for sources the user added and may edit or delete. */
    val custom: Boolean = false,
    /** Grouping shown in the layer switcher. */
    val group: String = "",
) {
    val needsKey: Boolean get() = keyId != null
}

@Serializable
data class TileLayer(
    val url: String,
    val subdomains: List<String> = emptyList(),
    val tileSize: Int = 256,
    val minZoom: Int = 0,
    val maxZoom: Int = 18,
    /** Layer opacity 0..1, useful for overlays such as hillshade. */
    val opacity: Float = 1f,
    @SerialName("scheme") val tms: Boolean = false,
)

/** A tile layer ready for the renderer: concrete URL list, key filled in. */
data class ResolvedLayer(val urls: List<String>, val tileSize: Int, val minZoom: Int, val maxZoom: Int, val opacity: Float, val tms: Boolean)

object TileTemplates {
    private val placeholder = Regex("""\{([a-z-]+)}""")
    private val known = setOf("z", "x", "y", "-y", "s", "key")

    /** Returns a Chinese error message, or null when [url] is a usable template. */
    fun validate(url: String, subdomains: List<String> = emptyList()): String? {
        val u = url.trim()
        if (!u.startsWith("https://") && !u.startsWith("http://")) return "地址需以 http:// 或 https:// 开头"
        val names = placeholder.findAll(u).map { it.groupValues[1] }.toSet()
        val unknown = names - known
        if (unknown.isNotEmpty()) return "不认识的占位符：" + unknown.joinToString { "{$it}" }
        if ("z" !in names || "x" !in names || ("y" !in names && "-y" !in names)) return "地址里需要有 {z}、{x} 和 {y}"
        if ("s" in names && subdomains.isEmpty()) return "地址里有 {s}，请填写子域名，如 a,b,c"
        return null
    }

    /**
     * Expands [layer] into concrete URLs MapLibre understands. `{-y}` becomes
     * `{y}` with the TMS scheme; `{s}` is expanded into one URL per subdomain.
     */
    fun resolve(layer: TileLayer, key: String?): ResolvedLayer {
        var tms = layer.tms
        var url = layer.url.trim()
        if (url.contains("{-y}")) {
            url = url.replace("{-y}", "{y}")
            tms = true
        }
        if (key != null) url = url.replace("{key}", key)
        val urls = if (url.contains("{s}") && layer.subdomains.isNotEmpty()) {
            layer.subdomains.map { url.replace("{s}", it) }
        } else {
            listOf(url)
        }
        return ResolvedLayer(urls, layer.tileSize, layer.minZoom, layer.maxZoom, layer.opacity.coerceIn(0f, 1f), tms)
    }

    /** Parses "a,b,c" or "0-7" into a subdomain list. */
    fun parseSubdomains(text: String): List<String> {
        val t = text.trim()
        Regex("""^(\d+)\s*-\s*(\d+)$""").matchEntire(t)?.let { m ->
            val (a, b) = m.destructured
            val lo = a.toInt()
            val hi = b.toInt()
            if (hi >= lo && hi - lo < 32) return (lo..hi).map(Int::toString)
        }
        return t.split(',', ' ', '，').map(String::trim).filter(String::isNotEmpty)
    }
}

/** API keys the user entered, by [TileSource.keyId]. */
data class TileKeys(private val keys: Map<String, String>) {
    operator fun get(id: String): String? = keys[id]?.takeIf { it.isNotBlank() }
    fun has(source: TileSource) = source.keyId == null || get(source.keyId) != null
}
