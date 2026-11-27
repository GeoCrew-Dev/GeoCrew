package org.geocrew.storage

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.geocrew.nativelib.GeoCrewNative

/** A file the probe could not read: GDAL does not know it, it is broken, or it stopped the reader. */
class ProbeFailed(message: String) : Exception(message)

/**
 * What GDAL makes of a file, read in a process of its own (ADR 0003): a file
 * that makes GDAL crash stops [ProbeService]'s process, not the app's.
 */
object Probe {
    /**
     * The file at [path], summed up (see [summarize]); throws [ProbeFailed]
     * when it cannot be read, or nothing comes in [timeoutMs].
     */
    suspend fun inspect(context: Context, path: String, timeoutMs: Long = 30_000): JsonObject {
        var bound: ServiceConnection? = null
        try {
            return withTimeout(timeoutMs) {
                suspendCancellableCoroutine { done ->
                    fun fail(message: String) {
                        if (done.isActive) done.resumeWithException(ProbeFailed(message))
                    }
                    val replies = Messenger(
                        Handler(Looper.getMainLooper()) { reply ->
                            val json = reply.data.getString(JSON)
                            val parsed = json?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                            if (parsed != null) {
                                if (done.isActive) done.resume(parsed)
                            } else {
                                fail(reply.data.getString(ERROR) ?: "no answer")
                            }
                            true
                        },
                    )
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            // The reader dying while it reads means the file broke it.
                            runCatching { binder.linkToDeath({ fail("the file stopped the reader") }, 0) }
                            val ask = Message.obtain(null, PROBE).apply {
                                data = Bundle().apply { putString(PATH, path) }
                                replyTo = replies
                            }
                            runCatching { Messenger(binder).send(ask) }.onFailure { fail("the reader is gone") }
                        }

                        override fun onServiceDisconnected(name: ComponentName) = fail("the file stopped the reader")
                    }
                    bound = connection
                    if (!context.bindService(Intent(context, ProbeService::class.java), connection, Context.BIND_AUTO_CREATE)) fail("no reader")
                }
            }
        } finally {
            bound?.let { runCatching { context.unbindService(it) } }
        }
    }

    /**
     * What [path] holds, as GDAL reads it, short enough to pass between
     * processes. A raster: {"kind": "raster", "driver", "format", "width",
     * "height", "bands": [{"type", "color"}], "palette", "overviews", "crs"
     * (as crs.describe gives it, or null), "pixel": [x, y] in the CRS's unit,
     * "extent": [west, south, east, north] in WGS84}. Vector data: {"kind":
     * "vector", "driver", "format", "layers": [{"name", "geometry", "features",
     * "crs"}]}. Runs in the probe's process; throws when GDAL cannot read it.
     */
    internal fun summarize(path: String): JsonObject {
        val info = Json.parseToJsonElement(GeoCrewNative.run(arrayOf("info", "--format", "json", path))).jsonObject
        val bands = info["bands"] as? JsonArray
        return buildJsonObject {
            put("driver", info.text("driverShortName"))
            put("format", info.text("driverLongName"))
            if (bands != null && bands.isNotEmpty()) {
                put("kind", "raster")
                val size = info["size"] as? JsonArray
                put("width", (size?.getOrNull(0) as? JsonPrimitive)?.longOrNull)
                put("height", (size?.getOrNull(1) as? JsonPrimitive)?.longOrNull)
                putJsonArray("bands") {
                    for (b in bands) addJsonObject {
                        val band = b.jsonObject
                        put("type", band.text("type"))
                        put("color", band.text("colorInterpretation"))
                    }
                }
                val first = bands.first().jsonObject
                put("palette", first["colorTable"] != null || first.text("colorInterpretation") == "Palette")
                put("overviews", (first["overviews"] as? JsonArray)?.size ?: 0)
                put("crs", crsOf((info["coordinateSystem"] as? JsonObject)?.text("wkt")) ?: JsonNull)
                val transform = (info["geoTransform"] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull }
                if (transform != null && transform.size == 6 && transform[1] != null && transform[5] != null) {
                    putJsonArray("pixel") { add(transform[1]); add(-transform[5]!!) }
                }
                boundsOf(info["wgs84Extent"])?.let { e -> putJsonArray("extent") { e.forEach { add(it) } } }
            } else {
                put("kind", "vector")
                putJsonArray("layers") {
                    // GeoCrew's own tables in a project's GeoPackage are not data of their own.
                    val layers = (info["layers"] as? JsonArray).orEmpty().map { it.jsonObject }.filterNot { it.text("name")?.startsWith("geocrew_") == true }
                    for (layer in layers) addJsonObject {
                        val geometry = (layer["geometryFields"] as? JsonArray)?.firstOrNull() as? JsonObject
                        put("name", layer.text("name"))
                        put("geometry", geometry?.text("type") ?: "None")
                        put("features", (layer["featureCount"] as? JsonPrimitive)?.longOrNull)
                        put("crs", crsOf((geometry?.get("coordinateSystem") as? JsonObject)?.text("wkt")) ?: JsonNull)
                    }
                }
            }
        }
    }

    // A CRS's name and code, as crs.describe gives them; null for none or one PROJ does not know.
    private fun crsOf(wkt: String?): JsonElement? =
        wkt?.takeIf { it.isNotBlank() }?.let { runCatching { Crs.describe(it) }.getOrNull() }

    // The bounding box [west, south, east, north] of a GeoJSON polygon.
    private fun boundsOf(e: JsonElement?): List<Double>? {
        val ring = (((e as? JsonObject)?.get("coordinates") as? JsonArray)?.firstOrNull() as? JsonArray) ?: return null
        val xy = ring.mapNotNull { p -> (p as? JsonArray)?.let { (it.getOrNull(0) as? JsonPrimitive)?.doubleOrNull to (it.getOrNull(1) as? JsonPrimitive)?.doubleOrNull } }
            .filter { it.first != null && it.second != null }
        if (xy.isEmpty()) return null
        return listOf(xy.minOf { it.first!! }, xy.minOf { it.second!! }, xy.maxOf { it.first!! }, xy.maxOf { it.second!! })
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    internal const val PROBE = 1
    internal const val PATH = "path"
    internal const val JSON = "json"
    internal const val ERROR = "error"
}

/**
 * Where [Probe] reads files: a service in a process of its own (":probe"),
 * one file at a time on a thread of its own.
 */
class ProbeService : Service() {
    private val thread = HandlerThread("probe").apply { start() }

    private val messenger = Messenger(
        Handler(thread.looper) { ask ->
            val answer = Bundle()
            try {
                NativeData.setup(applicationContext)
                answer.putString(Probe.JSON, Probe.summarize(ask.data.getString(Probe.PATH).orEmpty()).toString())
            } catch (e: Exception) {
                answer.putString(Probe.ERROR, e.message ?: e.toString())
            }
            runCatching { ask.replyTo?.send(Message.obtain(null, Probe.PROBE).apply { data = answer }) }
            true
        },
    )

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }
}
