package org.geocrew.core.model.tiles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A base map a leader shares with the workspace (protocol v0 §2.2, key
 * `basemaps`), such as a regional geological map service. Only sources that
 * need no API key travel: a leader's personal key must never reach the team.
 */
@Serializable
data class SharedBasemap(
    val id: String,
    val name: String,
    /** One tile template as in [TileLayer.url]: XYZ, WMTS (KVP) or WMS with `{bbox-epsg-3857}`. */
    val url: String,
    val subdomains: List<String> = emptyList(),
    @SerialName("max_zoom") val maxZoom: Int = 18,
    val tms: Boolean = false,
    val attribution: String = "",
) {
    /** The source as this phone draws it; it is listed with the project, not among the user's own. */
    fun toSource() = TileSource(
        id = id,
        name = name,
        layers = listOf(TileLayer(url = url, subdomains = subdomains, maxZoom = maxZoom, tms = tms)),
        attribution = attribution,
        maxZoom = maxZoom,
        group = GROUP,
        shared = true,
    )

    companion object {
        const val GROUP = "工作组共享"
        private val ID = Regex("^[A-Za-z0-9_.-]{1,64}$")

        /** What a leader shares for [s]; null for built-in, offline or keyed sources and multi-layer ones. */
        fun of(s: TileSource): SharedBasemap? {
            if (!s.custom || s.offline || s.keyId != null || s.layers.size != 1) return null
            val l = s.layers.first()
            val b = SharedBasemap(s.id, s.name, l.url.trim(), l.subdomains, s.maxZoom, l.tms, s.attribution)
            return b.takeIf(::valid)
        }

        /** True when [b] can be drawn without anyone's key. */
        fun valid(b: SharedBasemap): Boolean =
            ID.matches(b.id) && b.name.isNotBlank() && b.name.length <= 100 && b.maxZoom in 1..24 &&
                b.subdomains.size <= 32 && b.subdomains.all { it.length in 1..32 } &&
                "{key}" !in b.url && b.url.length <= 2000 && TileTemplates.validate(b.url, b.subdomains) == null
    }
}
