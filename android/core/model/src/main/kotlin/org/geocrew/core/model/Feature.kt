package org.geocrew.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * What a feature represents. Open-ended on purpose: plugins and teams can
 * define their own kinds; the app falls back to generic handling for them.
 */
@JvmInline
value class FeatureKind(val id: String) {
    companion object {
        val POINT = FeatureKind("point")
        val GEO_POINT = FeatureKind("geo_point")
        val LINE = FeatureKind("line")
        val POLYGON = FeatureKind("polygon")
        val TRACK = FeatureKind("track")
        val PHOTO = FeatureKind("photo")
        /** A measured stratigraphic section (§6.5); its line is the traverse. */
        val SECTION = FeatureKind("section")
        /** A geological route (§6.6); its line joins the observation points it lists. */
        val ROUTE = FeatureKind("route")

        val builtIn = listOf(POINT, GEO_POINT, LINE, POLYGON, TRACK, PHOTO, SECTION, ROUTE)
    }
}

/** Sync bookkeeping, mirroring protocol v0 §4.1. */
data class SyncInfo(
    val version: Long = 1,
    val updatedAt: Instant,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
    /** Server seq of the revision we last saw; null if never synced. */
    val seq: Long? = null,
    /** True while the local revision has not been accepted by the server. */
    val dirty: Boolean = true,
) {
    /** The next local revision after an edit at [at]. */
    fun bumped(at: Instant) = copy(version = version + 1, updatedAt = at, dirty = true)

    /** v0 conflict rule: higher version, then later time, then larger author id. */
    fun isNewerThan(o: SyncInfo): Boolean = when {
        version != o.version -> version > o.version
        updatedAt != o.updatedAt -> updatedAt > o.updatedAt
        else -> (updatedBy ?: "") > (o.updatedBy ?: "")
    }
}

/**
 * One map object: a GeoJSON feature inside a project. [properties] keeps
 * every field, including ones this app version does not understand, so
 * round-trips through the server or files never lose data.
 */
data class GeoFeature(
    val id: String,
    val projectId: String,
    val kind: FeatureKind,
    val geometry: Geometry?,
    val properties: JsonObject = JsonObject(emptyMap()),
    val createdAt: Instant,
    val sync: SyncInfo,
) {
    val deleted get() = sync.deleted

    val title: String? get() = properties.string(Props.TITLE)
    val note: String? get() = properties.string(Props.NOTE)
    val photos: List<String> get() = (properties[Props.PHOTOS] as? JsonArray)?.mapNotNull { it.stringOrNull() }.orEmpty()
    val attitude: Attitude? get() = Attitude.fromProperties(properties)

    /** Returns a copy with [edit] applied to the properties and the sync revision bumped. */
    fun edited(at: Instant, geometry: Geometry? = this.geometry, edit: PropertiesBuilder.() -> Unit = {}): GeoFeature =
        copy(geometry = geometry, properties = properties.edit(edit), sync = sync.bumped(at))

    fun markDeleted(at: Instant) = copy(sync = sync.bumped(at).copy(deleted = true))
}

/** Property keys used by the official client (protocol v0 §6). */
object Props {
    const val KIND = "kind"
    const val TITLE = "title"
    const val NOTE = "note"
    const val PHOTOS = "photos"
    const val PHOTO_INFO = "photo_info"
    const val AUDIO = "audio"
    const val AUDIO_INFO = "audio_info"
    /** Samples taken at the record (§6.4). */
    const val SAMPLES = "samples"
    /** Legs and layers of a measured section (§6.5). */
    const val SECTION = "section"
    /** Stops, descriptions and summary of a geological route (§6.6). */
    const val ROUTE = "route"
    const val LITHOLOGY = "lithology"
    /** What a line stands for: contact or fault, observed or inferred (see [LineStyle]). */
    const val LINE_STYLE = "line_style"
    /** When the group meets at this point (protocol §6.8), an ISO-8601 instant. */
    const val MEET_AT = "meet_at"
    /** A leader's review of the record (protocol §6.9, see [Review]). */
    const val REVIEW = "review"
    const val STRIKE = "strike"
    const val DIP_DIRECTION = "dip_direction"
    const val DIP = "dip"
    const val AZIMUTH = "azimuth"
    const val LENGTH_M = "length_m"
    const val AREA_M2 = "area_m2"
    const val STARTED_AT = "started_at"
    const val ENDED_AT = "ended_at"
    const val ACCURACY_M = "accuracy_m"
    /** Fixes averaged into a point placed by GPS. */
    const val GPS_SAMPLES = "gps_samples"
    /** RMS wander of those fixes around the average, metres. */
    const val GPS_SPREAD_M = "gps_spread_m"
    const val ASSIGNEE = "assignee"
    const val ASSIGNEE_NAME = "assignee_name"
    const val TASK_STATUS = "task_status"
    /** Where a point's altitude came from when it is not the GPS: [ALT_FROM_DEM]. Absent for GPS heights. */
    const val ALT_SOURCE = "alt_source"
    const val ALT_FROM_DEM = "dem"

    /** Keys with their own editors or set by the app; free attributes and form fields never use them. */
    val MANAGED: Set<String> = setOf(
        KIND, TITLE, NOTE, PHOTOS, PHOTO_INFO, AUDIO, AUDIO_INFO, SAMPLES, SECTION, ROUTE, LITHOLOGY, LINE_STYLE, MEET_AT, REVIEW, STRIKE, DIP_DIRECTION, DIP, AZIMUTH,
        STARTED_AT, ENDED_AT, ACCURACY_M, GPS_SAMPLES, GPS_SPREAD_M, LENGTH_M, AREA_M2,
        ASSIGNEE, ASSIGNEE_NAME, TASK_STATUS, ALT_SOURCE,
    )
}

fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.string(key: String): String? = this[key]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }

fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

/** Mutable view used to edit a [JsonObject] without losing unknown keys. */
class PropertiesBuilder internal constructor(initial: Map<String, JsonElement>) {
    internal val map = LinkedHashMap(initial)

    operator fun set(key: String, value: String?) = put(key, value?.let(::JsonPrimitive))
    operator fun set(key: String, value: Number?) = put(key, value?.let(::JsonPrimitive))
    operator fun set(key: String, value: Boolean?) = put(key, value?.let(::JsonPrimitive))
    fun strings(key: String, values: List<String>) = put(key, JsonArray(values.map(::JsonPrimitive)))

    fun put(key: String, value: JsonElement?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    fun remove(key: String) = map.remove(key)
}

fun JsonObject.edit(block: PropertiesBuilder.() -> Unit): JsonObject =
    JsonObject(PropertiesBuilder(this).apply(block).map)

fun buildProperties(block: PropertiesBuilder.() -> Unit): JsonObject = JsonObject(emptyMap()).edit(block)
