package org.geocrew.core.edit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

internal fun point(x: Number, y: Number) = json("""{"type": "Point", "coordinates": [$x, $y]}""")

internal val FIELDS = listOf(
    Field("rock", FieldType.STRING), Field("dip", FieldType.REAL), Field("n", FieldType.INTEGER),
    Field("big", FieldType.INTEGER64), Field("ok", FieldType.BOOLEAN), Field("day", FieldType.DATE),
    Field("at", FieldType.DATETIME), Field("blob", FieldType.OTHER),
)

/** A layer in memory that fails on request and keeps the order of its writes, like a GeoPackage. */
internal class FakeStore(override val capabilities: Capabilities = Capabilities(true, true, true, true)) : LayerStore {
    override val fields = FIELDS
    val rows = sortedMapOf<Long, Feature>()
    val writes = mutableListOf<String>()
    var failOn: String? = null
    private var sequence = 0L // like AUTOINCREMENT: fids are never reused
    private var saved: Pair<Map<Long, Feature>, Long>? = null

    fun put(rock: String, x: Number = 0): Long {
        val fid = ++sequence
        rows[fid] = Feature(fid, point(x, 0), JsonObject(FIELDS.associate { it.name to JsonNull } + ("rock" to JsonPrimitive(rock))))
        return fid
    }

    private fun write(what: String) {
        if (failOn == what) error("$what failed")
        writes += what
    }

    override fun feature(fid: Long) = rows[fid]
    override fun lastFid() = rows.keys.maxOrNull() ?: -1L
    override fun features() = rows.values.toList()

    override fun add(geometry: JsonObject?, properties: JsonObject): Long {
        write("add")
        val fid = ++sequence
        rows[fid] = Feature(fid, geometry, properties)
        return fid
    }

    override fun update(fid: Long, properties: JsonObject, setGeometry: Boolean, geometry: JsonObject?) {
        write("update")
        val f = rows.getValue(fid)
        rows[fid] = f.copy(geometry = if (setGeometry) geometry else f.geometry, properties = JsonObject(f.properties + properties))
    }

    override fun delete(fid: Long) {
        write("delete")
        requireNotNull(rows.remove(fid)) { "there is no feature $fid" }
    }

    override fun begin() {
        saved = rows.toMap() to sequence
    }

    override fun commit() {
        write("commit")
        saved = null
    }

    override fun rollback() {
        val (r, s) = checkNotNull(saved)
        rows.clear()
        rows.putAll(r)
        sequence = s
        saved = null
    }
}
