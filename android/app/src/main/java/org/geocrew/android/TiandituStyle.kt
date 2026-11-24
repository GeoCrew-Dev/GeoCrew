package org.geocrew.android

/**
 * 用天地图 Web 墨卡托（_w）WMTS 瓦片拼出 MapLibre 样式。
 * 图层：vec 矢量底图 + cva 矢量注记；img 影像 + cia 影像注记。
 */
object TiandituStyle {

    enum class Base(val layer: String, val label: String) {
        VECTOR("vec", "cva"),
        IMAGE("img", "cia"),
        TERRAIN("ter", "cta"),
    }

    private fun tiles(layer: String, key: String): String {
        // 用 t0..t7 八个子域分摊请求
        return (0..7).joinToString(",") { i ->
            "\"https://t$i.tianditu.gov.cn/${layer}_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
                "&LAYER=$layer&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles" +
                "&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=$key\""
        }
    }

    fun json(base: Base, key: String): String = """
        {
          "version": 8,
          "sources": {
            "base": { "type": "raster", "tileSize": 256, "maxzoom": 18,
                      "tiles": [${tiles(base.layer, key)}],
                      "attribution": "© 天地图" },
            "label": { "type": "raster", "tileSize": 256, "maxzoom": 18,
                       "tiles": [${tiles(base.label, key)}] }
          },
          "layers": [
            { "id": "base", "type": "raster", "source": "base" },
            { "id": "label", "type": "raster", "source": "label" }
          ]
        }
    """.trimIndent()
}
