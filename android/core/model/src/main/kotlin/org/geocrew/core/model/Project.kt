package org.geocrew.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant

/**
 * A field project ("工程"), the app's top-level container: its features,
 * tracks and photos, its own map setup, and at most one workspace it syncs
 * with. Account-like things (map keys, the tile source library, display
 * preferences) are global and live in the app settings instead.
 */
data class Project(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val team: TeamLink? = null,
    val config: ProjectConfig = ProjectConfig(),
    /** Last time the project was opened on this device; null if never. */
    val openedAt: Instant? = null,
)

/**
 * Per-project settings, stored as JSON so new fields need no database
 * migration. Unknown fields are ignored when reading, so older app versions
 * can open projects written by newer ones. Also the `project.json` of an
 * exported project package.
 */
@Serializable
data class ProjectConfig(
    /** Base map for this project; null means the app-wide default. */
    val baseSourceId: String? = null,
    /** Where the map was when the project was last closed. */
    val view: MapViewport? = null,
    /** Free-text description, e.g. survey area or purpose. */
    val description: String = "",
    /** Attribute fields offered on every record of this project, in order. */
    val fields: List<FieldTemplate> = emptyList(),
    /** Version of the workspace settings [fields] and [legend] were last in step with (protocol v0 §2.2). */
    val teamFormVersion: Long = 0,
    /** How the map colours records by an attribute; shared like [fields] in a team project. */
    val legend: Legend = Legend(),
    /** True when a leader changed [fields] or [legend] locally and the workspace has not received them yet. */
    val teamFormPending: Boolean = false,
    /** Cursor of the workspace's comments already on this device (protocol v0 §8). */
    val teamCommentSeq: Long = 0,
    /** Newest comment seq the user has seen in the team chat on this device. */
    val chatReadSeq: Long = 0,
    /** How new records are named. Kept per device, so teammates can use different prefixes. */
    val numbering: Numbering = Numbering(),
    /** This device asked for background notices from the workspace (UnifiedPush). */
    val pushEnabled: Boolean = false,
    /** What the map draws for this project (record kinds, names, teammates). */
    val display: MapDisplay = MapDisplay(),
    /** Pictures laid over the map (scanned maps, sketch plans), bottom first. Kept per device. */
    val overlays: List<ImageOverlay> = emptyList(),
    /** A local map grid (Beijing 54, Xi'an 80...) with its datum shift, for coordinate tables. Kept per device. */
    val localGrid: LocalGrid? = null,
    /** Base maps the workspace shares (protocol v0 §2.2, key `basemaps`), in the leaders' order. */
    val teamBasemaps: List<org.geocrew.core.model.tiles.SharedBasemap> = emptyList(),
    /** The project's own description words ([FieldTerms]); shared like [fields] in a team project (key `phrases`). */
    val phrases: List<PhraseGroup> = emptyList(),
    /** The workspace's pinned notice (key `notice`); null when none. Only in team projects. */
    val teamNotice: TeamNotice? = null,
    /** [TeamNotice.updatedAt] of the notice this device's user dismissed on the map. Kept per device. */
    val noticeSeenAt: String? = null,
) {
    fun fieldsFor(kind: FeatureKind) = fields.filter { it.appliesTo(kind) }

    /**
     * The settings a new project takes over from this one (新建时沿用): the
     * form, legend, description words, numbering, map display, base map,
     * place on the map and local grid. Nothing tied to this project's data,
     * pictures or workspace comes along: no description, overlays (their
     * files belong to this project), team cursors, notice or the base maps a
     * workspace shared (a base map picked from those falls back to the
     * default).
     */
    fun template(): ProjectConfig = ProjectConfig(
        baseSourceId = baseSourceId,
        view = view,
        fields = fields,
        legend = legend,
        numbering = numbering,
        display = display,
        localGrid = localGrid,
        phrases = phrases,
    )

    /** Required fields [f] has not filled yet, in form order. */
    fun missing(f: GeoFeature): List<FieldTemplate> = if (f.deleted) emptyList() else fields.filter { it.missingFrom(f) }
}

@Serializable
enum class FieldType { TEXT, NUMBER, CHOICE }

/**
 * One attribute of a project's record form, e.g. "地层" or "样品编号". The
 * value is stored in the record's GeoJSON properties under [key], so it
 * syncs and exports like any other attribute.
 */
@Serializable
data class FieldTemplate(
    val key: String,
    val label: String = key,
    val type: FieldType = FieldType.TEXT,
    /** Allowed values for [FieldType.CHOICE]. */
    val options: List<String> = emptyList(),
    /** Shown after numbers, e.g. "m" or "%". */
    val unit: String = "",
    /** Record kinds the field applies to (see [FeatureKind]); empty means all. */
    val kinds: List<String> = emptyList(),
    /** Records of these kinds count as incomplete until the field is filled (a reminder, never enforced). */
    val required: Boolean = false,
) {
    fun appliesTo(kind: FeatureKind) = kinds.isEmpty() || kind.id in kinds

    /** Whether [f] still lacks this required field. */
    fun missingFrom(f: GeoFeature): Boolean = required && appliesTo(f.kind) && display(f.properties[key]).isBlank()

    /**
     * Turns what the user typed into the stored value: null for blank,
     * a number for [FieldType.NUMBER] (a comma decimal is accepted), and a
     * string otherwise, so codes like "007" keep their zeros.
     * Throws [IllegalArgumentException] when the input does not fit.
     */
    fun encode(raw: String): JsonElement? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        return when (type) {
            FieldType.TEXT -> JsonPrimitive(v)
            FieldType.NUMBER -> {
                val n = v.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: throw IllegalArgumentException("$label 需要填数字")
                JsonPrimitive(n)
            }
            FieldType.CHOICE -> {
                require(options.isEmpty() || v in options) { "$label 只能从选项里选" }
                JsonPrimitive(v)
            }
        }
    }

    /** The stored value as the editor shows it; whole numbers lose their ".0". */
    fun display(value: JsonElement?): String {
        val p = value as? JsonPrimitive ?: return ""
        if (p is JsonNull) return ""
        val d = if (p.isString) null else p.doubleOrNull
        return when {
            d == null -> p.content
            d == Math.rint(d) && kotlin.math.abs(d) < 1e15 -> d.toLong().toString()
            else -> d.toString()
        }
    }

    companion object {
        private val KEY = Regex("^[\\p{L}\\p{N}_ .-]{1,40}$")

        /** Why [field] cannot join [others], or null when it can. */
        fun problem(field: FieldTemplate, others: List<FieldTemplate>): String? = when {
            !KEY.matches(field.key) -> "属性键只能用文字、数字、空格和 _ . -，最多 40 个字"
            field.key in Props.MANAGED -> "“${field.key}”由应用使用，请换一个"
            others.any { it.key == field.key } -> "已有同名属性"
            field.label.isBlank() -> "请填写名称"
            field.type == FieldType.CHOICE && field.options.isEmpty() -> "选项型属性至少要有一个选项"
            else -> null
        }

        /** A starting point for geological mapping; users edit it per project. */
        val geologyPreset = listOf(
            FieldTemplate("point_no", "点号"),
            FieldTemplate("formation", "地层"),
            FieldTemplate(
                "structure", "构造类型", FieldType.CHOICE,
                options = listOf("层理", "节理", "断层", "片理", "劈理", "褶皱轴面", "接触面"),
            ),
            FieldTemplate("sample_no", "样品编号"),
            FieldTemplate("thickness", "厚度", FieldType.NUMBER, unit = "m"),
        )
    }
}

@Serializable
data class MapViewport(val lng: Double, val lat: Double, val zoom: Double, val bearing: Double = 0.0)

/** Connection of a project to a workspace on a self-hosted server. */
data class TeamLink(
    val serverUrl: String,
    val workspaceId: String,
    val workspaceName: String,
    val memberId: String,
    val nickname: String,
    val role: String,
    /** Last server seq merged locally (protocol v0 §4.4). */
    val lastSeq: Long = 0,
)

/** One recorded GPS fix of a track. */
data class TrackPoint(
    val position: LngLat,
    val time: Instant,
    val accuracy: Float? = null,
    val speed: Float? = null,
    val bearing: Float? = null,
)
