package org.geocrew.sample.plugin

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * A GeoCrew analysis plugin (geocrew-protocol docs/plugins.md): reads the
 * records GeoCrew hands over, makes a centre point or a bounding box for each
 * line and polygon, writes them back and finishes. There is no screen; the
 * work runs off the main thread and the activity is translucent; the
 * manifest keeps it from being recreated while it works.
 */
class OutlineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = intent.data
        val output = outputUri()
        if (input == null || output == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        Thread {
            val result = try {
                val text = contentResolver.openInputStream(input)!!.use { it.readBytes().decodeToString() }
                process(JSONObject(text))
            } catch (e: Exception) {
                failure(e.message ?: e.javaClass.simpleName)
            }
            val ok = runCatching {
                contentResolver.openOutputStream(output, "wt")!!.use { it.write(result.toString().toByteArray()) }
            }.isSuccess
            runOnUiThread {
                setResult(if (ok) RESULT_OK else RESULT_CANCELED)
                finish()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun outputUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_OUTPUT, Uri::class.java) else intent.getParcelableExtra(EXTRA_OUTPUT)

    private fun process(root: JSONObject): JSONObject {
        val params = root.optJSONObject("geocrew")?.optJSONObject("params") ?: JSONObject()
        val bbox = params.optString("what", "centroid") == "bbox"
        val margin = params.optString("margin", "0").toDoubleOrNull()?.coerceIn(0.0, 10_000.0) ?: 0.0
        val features = root.optJSONArray("features") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val g = f.optJSONObject("geometry") ?: continue
            val title = f.optJSONObject("properties")?.optString("title").orEmpty().ifEmpty { "第 ${i + 1} 条" }
            val parts = parts(g)
            if (parts.isEmpty()) continue
            out.put(if (bbox) box(parts, margin, title) else centre(parts, g.optString("type").contains("Polygon"), title) ?: continue)
        }
        val what = if (bbox) "外接矩形" else "中心点"
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", out)
            .put("geocrew", JSONObject().put("summary", "为 ${out.length()} 条记录生成了$what。"))
    }

    /** Rings or lines of the geometry as lists of [lng, lat]; polygons give their outer rings only. */
    private fun parts(g: JSONObject): List<List<DoubleArray>> {
        val c = g.optJSONArray("coordinates") ?: return emptyList()
        fun line(a: JSONArray) = (0 until a.length()).mapNotNull { j ->
            a.optJSONArray(j)?.let { p -> doubleArrayOf(p.optDouble(0), p.optDouble(1)).takeIf { it.all(Double::isFinite) } }
        }
        return when (g.optString("type")) {
            "LineString" -> listOf(line(c))
            "MultiLineString" -> (0 until c.length()).map { line(c.getJSONArray(it)) }
            "Polygon" -> listOf(line(c.getJSONArray(0)))
            "MultiPolygon" -> (0 until c.length()).map { line(c.getJSONArray(it).getJSONArray(0)) }
            else -> emptyList()
        }.filter { it.size >= 2 }
    }

    /** Area-weighted centroid of polygons, length-weighted of lines, on a local flat plane. */
    private fun centre(parts: List<List<DoubleArray>>, polygon: Boolean, title: String): JSONObject? {
        val lat0 = parts.first().first()[1]
        val k = cos(lat0 * PI / 180)
        var w = 0.0; var sx = 0.0; var sy = 0.0
        for (p in parts) for (j in 0 until p.size - 1) {
            val (x1, y1) = p[j][0] * k to p[j][1]
            val (x2, y2) = p[j + 1][0] * k to p[j + 1][1]
            if (polygon) {
                val a = x1 * y2 - x2 * y1
                w += a; sx += (x1 + x2) * a; sy += (y1 + y2) * a
            } else {
                val l = hypot(x2 - x1, y2 - y1)
                w += l; sx += (x1 + x2) / 2 * l; sy += (y1 + y2) / 2 * l
            }
        }
        if (w == 0.0) return null
        val (cx, cy) = if (polygon) sx / (3 * w) to sy / (3 * w) else sx / w to sy / w
        return feature(JSONObject().put("type", "Point").put("coordinates", JSONArray().put(cx / k).put(cy)), "$title 中心点", "point")
    }

    private fun box(parts: List<List<DoubleArray>>, margin: Double, title: String): JSONObject {
        val all = parts.flatten()
        val lat = all.map { it[1] }
        val dLat = margin / 111_320.0
        val dLng = margin / (111_320.0 * cos(lat.average() * PI / 180))
        val w = all.minOf { it[0] } - dLng; val e = all.maxOf { it[0] } + dLng
        val s = lat.min() - dLat; val n = lat.max() + dLat
        val ring = JSONArray()
        listOf(w to s, e to s, e to n, w to n, w to s).forEach { (x, y) -> ring.put(JSONArray().put(x).put(y)) }
        return feature(JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring)), "$title 外接矩形", "polygon")
            .also { it.getJSONObject("properties").put("fill", "#FF9800").put("fill-opacity", 0.1).put("stroke", "#E65100") }
    }

    private fun feature(geometry: JSONObject, title: String, kind: String) = JSONObject()
        .put("type", "Feature")
        .put("geometry", geometry)
        .put("properties", JSONObject().put("kind", kind).put("title", title))

    private fun failure(message: String) = JSONObject()
        .put("type", "FeatureCollection")
        .put("features", JSONArray())
        .put("geocrew", JSONObject().put("error", message))

    private companion object {
        const val EXTRA_OUTPUT = "org.geocrew.plugin.extra.OUTPUT"
    }
}
