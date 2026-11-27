package org.geocrew.storage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.geocrew.core.edit.Attachment
import org.geocrew.core.edit.Capabilities
import org.geocrew.core.edit.Feature
import org.geocrew.core.edit.Field
import org.geocrew.core.edit.FieldType
import org.geocrew.core.edit.LayerStore
import org.geocrew.nativelib.VectorDataset

/**
 * Layer [layer] of an open [dataset] as the store of an edit buffer
 * (ADR 0005). The fields and capabilities are read once, when this is made;
 * the fields' ranges each time, as they can change while the layer is edited.
 * With [layerId], a project layer's id, its features keep attachments (ADR 0004).
 * VectorDataset does not report whether a layer takes new features, so that
 * follows random writes: both or neither for GeoPackage and Shapefile.
 */
class VectorLayerStore(private val dataset: VectorDataset, private val layer: String, private val layerId: String? = null) : LayerStore {
    private val info = Json.parseToJsonElement(dataset.layerInfo(layer)).jsonObject

    private val plain: List<Field> = info.getValue("fields").jsonArray.map {
        val field = it.jsonObject
        Field(field.getValue("name").jsonPrimitive.content, FieldType.of(field.getValue("type").jsonPrimitive.content))
    }

    override val fields: List<Field>
        get() {
            val ranges = FieldRanges.of(dataset, layer)
            return if (ranges.isEmpty()) plain else plain.map { f -> ranges[f.name]?.let { f.copy(range = it) } ?: f }
        }

    override val capabilities: Capabilities = info.getValue("capabilities").jsonObject.let { caps ->
        fun can(name: String) = caps[name]?.jsonPrimitive?.booleanOrNull == true
        Capabilities(add = can("randomWrite"), update = can("randomWrite"), delete = can("deleteFeature"), transactions = can("transactions"))
    }

    override fun feature(fid: Long): Feature? = dataset.feature(layer, fid)?.let { Json.decodeFromString(Feature.serializer(), it) }

    override fun features(): List<Feature> = Json.decodeFromString(ListSerializer(Feature.serializer()), dataset.features(layer))

    // A GeoPackage's fid is its table's rowid, whatever the column is called;
    // other formats are read through.
    override fun lastFid(): Long {
        val max = runCatching {
            val rows = Json.parseToJsonElement(dataset.sql("SELECT MAX(rowid) AS m FROM \"${layer.replace("\"", "\"\"")}\"")).jsonArray
            rows.single().jsonObject.getValue("properties").jsonObject["m"]
        }.getOrNull()
        if (max != null) return (max as? JsonPrimitive)?.longOrNull ?: -1
        return features().maxOfOrNull { it.fid } ?: -1
    }

    override fun add(geometry: JsonObject?, properties: JsonObject): Long =
        dataset.add(layer, buildJsonObject { put("geometry", geometry ?: JsonNull); put("properties", properties) }.toString())

    override fun update(fid: Long, properties: JsonObject, setGeometry: Boolean, geometry: JsonObject?) {
        val feature = buildJsonObject {
            if (setGeometry) put("geometry", geometry ?: JsonNull)
            put("properties", properties)
        }
        dataset.update(layer, fid, feature.toString())
    }

    override fun delete(fid: Long) = dataset.delete(layer, fid)

    override fun attachments(fid: Long): List<Attachment> = layerId?.let { AttachmentTables.of(dataset, it, fid) }.orEmpty()

    override fun setAttachments(fid: Long, attachments: List<Attachment>) {
        val id = layerId ?: return super.setAttachments(fid, attachments)
        AttachmentTables.set(dataset, layer, id, fid, attachments)
    }

    override fun begin() = dataset.begin()

    override fun commit() = dataset.commit()

    override fun rollback() = dataset.rollback()
}
