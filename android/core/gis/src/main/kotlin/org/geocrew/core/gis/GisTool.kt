package org.geocrew.core.gis

import kotlinx.serialization.json.JsonObject
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry

/**
 * A spatial analysis tool. Tools read features and parameters and return new
 * features plus a short text summary; they never touch storage or the UI, so
 * the same tool can run on the phone, in tests or on a server.
 *
 * To add a tool, implement this interface and register it (on Android, bind
 * it into the `Set<GisTool>` with Hilt). The app builds the parameter form
 * from [params] and saves the output as ordinary features, so they sync,
 * export and display like anything else.
 */
interface GisTool {
    /** Stable id, recorded on output features as `tool`. Lowercase, no spaces. */
    val id: String
    val name: String
    val description: String
    /** Geometry types this tool reads; other input features are ignored. */
    val accepts: Set<GeometryType>
    val params: List<ToolParam>

    /** Returns a problem with the input in plain words, or null if the tool can run. */
    fun check(input: ToolInput): String? = null

    /** Runs the tool. [progress] receives 0..1; implementations should honour [ToolInput.cancelled]. */
    fun run(input: ToolInput, progress: (Float) -> Unit = {}): ToolOutput
}

enum class GeometryType { POINT, LINE, POLYGON;
    companion object {
        fun of(g: Geometry?): GeometryType? = when (g) {
            is Geometry.Point, is Geometry.MultiPoint -> POINT
            is Geometry.LineString, is Geometry.MultiLineString -> LINE
            is Geometry.Polygon, is Geometry.MultiPolygon -> POLYGON
            else -> null
        }
    }
}

/** One input of a tool. The UI renders a control for each. */
sealed interface ToolParam {
    val key: String
    val label: String
    val help: String?

    /** A number; [default] null means "automatic" and the tool picks a value. */
    data class Number(
        override val key: String,
        override val label: String,
        val default: Double?,
        val min: Double? = null,
        val max: Double? = null,
        val unit: String? = null,
        override val help: String? = null,
    ) : ToolParam

    /** A property of the input features. [numeric] limits the choice to properties holding numbers. */
    data class Field(
        override val key: String,
        override val label: String,
        val numeric: Boolean = true,
        override val help: String? = null,
    ) : ToolParam

    /** One of the input features, e.g. the section line to project onto; the value is its id. */
    data class Feature(
        override val key: String,
        override val label: String,
        val types: Set<GeometryType>,
        override val help: String? = null,
    ) : ToolParam

    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<Option>,
        val default: String = options.first().value,
        override val help: String? = null,
    ) : ToolParam {
        data class Option(val value: String, val label: String)
    }
}

/** Features to analyse and the values the user entered, keyed by [ToolParam.key]. */
data class ToolInput(
    val features: List<GeoFeature>,
    val values: Map<String, String> = emptyMap(),
    val cancelled: () -> Boolean = { false },
    /** Ground heights for tools that want terrain; null when the app offers none. Blocking. */
    val elevation: org.geocrew.core.model.Elevation? = null,
) {
    fun number(p: ToolParam.Number): Double? = values[p.key]?.trim()?.toDoubleOrNull()?.let { v ->
        v.coerceIn(p.min ?: v, p.max ?: v)
    } ?: p.default

    fun text(key: String): String? = values[key]?.trim()?.ifEmpty { null }

    /** The input feature chosen for [p], if it is still there and of an allowed type. */
    fun feature(p: ToolParam.Feature): GeoFeature? = text(p.key)?.let { id ->
        features.firstOrNull { it.id == id && !it.deleted && GeometryType.of(it.geometry) in p.types }
    }

    fun choice(p: ToolParam.Choice): String = text(p.key)?.takeIf { v -> p.options.any { it.value == v } } ?: p.default

    /** Input features whose geometry this tool accepts. */
    fun of(types: Set<GeometryType>): List<GeoFeature> =
        features.filter { !it.deleted && GeometryType.of(it.geometry) in types }
}

class ToolCancelled : RuntimeException("cancelled")

/** A feature a tool produced, before it gets an id and a project. */
data class OutputFeature(val kind: FeatureKind, val geometry: Geometry, val properties: JsonObject)

data class ToolOutput(
    val features: List<OutputFeature>,
    /** One or a few lines for the user, e.g. ranges and counts. */
    val summary: String,
    /** Optional colour key for the output, low to high. */
    val legend: List<LegendEntry> = emptyList(),
)

data class LegendEntry(val color: String, val label: String)

/** Looks tools up by id. */
class ToolRegistry(tools: Iterable<GisTool>) {
    val all: List<GisTool> = tools.sortedBy { it.name }

    init {
        val dup = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "duplicate tool ids: $dup" }
    }

    fun find(id: String): GisTool? = all.firstOrNull { it.id == id }

    companion object {
        /** Tools that ship with the app. */
        fun builtIn(): List<GisTool> = listOf(IdwTool(), KrigingTool(), SectionTool(), MapSectionTool(), TerrainTool(), ThreePointTool(), StatisticsTool(), ConvexHullTool(), BufferTool(), ZonalTool(), DensityTool(), HikeTool(), LeastCostTool())
    }
}
