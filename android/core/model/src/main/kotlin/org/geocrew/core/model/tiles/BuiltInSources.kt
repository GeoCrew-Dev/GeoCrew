package org.geocrew.core.model.tiles

/** Providers shipped with the app. None carries a key: users bring their own. */
object BuiltInSources {
    const val TIANDITU_KEY_ID = "tianditu"

    /** Tianditu keys are 32 hexadecimal characters. */
    fun isPlausibleTiandituKey(key: String) = Regex("^[0-9a-fA-F]{32}$").matches(key.trim())

    private fun tdt(layer: String, maxZoom: Int) = TileLayer(
        url = "https://t{s}.tianditu.gov.cn/${layer}_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
            "&LAYER=$layer&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles" +
            "&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk={key}",
        subdomains = (0..7).map(Int::toString),
        maxZoom = maxZoom,
    )

    private fun tianditu(id: String, name: String, base: String, label: String, maxZoom: Int) = TileSource(
        id = "tianditu-$id",
        name = name,
        layers = listOf(tdt(base, maxZoom), tdt(label, maxZoom)),
        attribution = "© 天地图",
        maxZoom = maxZoom,
        keyId = TIANDITU_KEY_ID,
        group = "天地图",
    )

    val TIANDITU_VECTOR = tianditu("vector", "天地图矢量", "vec", "cva", 18)
    val TIANDITU_IMAGE = tianditu("image", "天地图影像", "img", "cia", 18)
    // Tianditu terrain tiles stop at level 14.
    val TIANDITU_TERRAIN = tianditu("terrain", "天地图地形", "ter", "cta", 14)

    val OSM = TileSource(
        id = "osm",
        name = "OpenStreetMap",
        layers = listOf(TileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", maxZoom = 19)),
        attribution = "© OpenStreetMap contributors",
        maxZoom = 19,
        group = "其他",
    )

    /** A plain background, always available offline. */
    val BLANK = TileSource(id = "blank", name = "空白底图", layers = emptyList(), group = "其他")

    val all = listOf(TIANDITU_VECTOR, TIANDITU_IMAGE, TIANDITU_TERRAIN, OSM, BLANK)

    val default = TIANDITU_VECTOR
}

/** Built-ins plus the user's own sources, with lookup and fallback. */
class TileCatalog(custom: List<TileSource>) {
    val sources: List<TileSource> = BuiltInSources.all + custom.map { it.copy(custom = true) }

    fun find(id: String?): TileSource? = sources.firstOrNull { it.id == id }

    /**
     * The source to draw: the chosen one if usable, otherwise the first usable
     * source without a key, so a missing key never leaves the map empty.
     */
    fun effective(id: String?, keys: TileKeys): TileSource {
        val chosen = find(id) ?: BuiltInSources.default
        if (keys.has(chosen)) return chosen
        return sources.firstOrNull { it.keyId == null && it.layers.isNotEmpty() } ?: BuiltInSources.BLANK
    }
}
