package org.geocrew.core.gis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.geocrew.core.formats.GeoJson
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.edit

/**
 * A tool that lives in another app (a plugin), described by a JSON
 * descriptor and run by exchanging GeoJSON files: GeoCrew writes the
 * accepted records and the entered values, the plugin writes its results
 * back. The exchange is specified in geocrew-protocol `docs/plugins.md`;
 * this class holds the platform-free part, and [runner] does the hand-off
 * (on Android, starting the plugin's activity and waiting for its result).
 *
 * The tool id is `<app>/<descriptor id>`, so plugins cannot clash with
 * each other or with built-in tools.
 */
class ExternalTool(
    val descriptor: Descriptor,
    /** The app providing the tool, e.g. an Android package name. */
    val app: String,
    /** The app's name as the user knows it. */
    val appLabel: String,
    private val runner: Runner,
) : GisTool {
    override val id = "$app/${descriptor.id}"
    override val name = descriptor.name
    override val description = descriptor.description.ifEmpty { "由「$appLabel」提供的分析工具。" } +
        "\n由「$appLabel」提供：运行时把选中的记录交给它处理。"
    override val accepts = descriptor.accepts
    override val params = descriptor.params

    /** Hands [input] (a GeoJSON FeatureCollection) to the plugin and returns what it wrote. */
    fun interface Runner {
        fun run(tool: ExternalTool, input: String, cancelled: () -> Boolean): String
    }

    data class Descriptor(
        val id: String,
        val name: String,
        val description: String,
        val accepts: Set<GeometryType>,
        val params: List<ToolParam>,
        /** Keys of parameters that must be filled before the tool runs. */
        val required: Set<String> = emptySet(),
        /** Fewest accepted records the tool needs. */
        val minFeatures: Int = 1,
    )

    override fun check(input: ToolInput): String? {
        val n = input.of(accepts).size
        if (n < descriptor.minFeatures) return "至少需要 ${descriptor.minFeatures} 条可用的记录，现在有 $n 条"
        params.firstOrNull { it.key in descriptor.required && input.text(it.key) == null }?.let { return "请填写「${it.label}」" }
        return null
    }

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        check(input)?.let { throw IllegalArgumentException(it) }
        val text = encodeInput(input)
        if (input.cancelled()) throw ToolCancelled()
        val out = runner.run(this, text, input.cancelled)
        if (input.cancelled()) throw ToolCancelled()
        progress(1f)
        return decodeOutput(out, id)
    }

    /** The accepted records with the entered values, as plugins receive them. */
    fun encodeInput(input: ToolInput): String {
        val features = input.of(accepts)
        val root = GeoJson.encodeCollection(features).edit {
            put(MEMBER, buildJsonObject {
                put("api", API_VERSION)
                put("tool", descriptor.id)
                put("params", JsonObject(params.mapNotNull { p -> input.text(p.key)?.let { p.key to JsonPrimitive(it) } }.toMap()))
            })
        }
        return root.toString()
    }

    companion object {
        /** The version of the exchange this app speaks; plugins may require at most this. */
        const val API_VERSION = 1

        /** Foreign member of the input and output collections that carries everything but features. */
        const val MEMBER = "geocrew"

        const val MAX_OUTPUT_FEATURES = 20_000
        private const val MAX_PARAMS = 20
        private const val MAX_LEGEND = 20
        private val ID = Regex("^[a-z0-9][a-z0-9_.-]{0,39}$")
        private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Reads a plugin's descriptor. Throws [IllegalArgumentException] in
         * plain words, so a broken plugin can be reported to the user.
         */
        fun parseDescriptor(text: String): Descriptor {
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw IllegalArgumentException("插件说明不是 JSON 对象")
            val api = o.int("api") ?: 1
            require(api in 1..API_VERSION) { "插件需要更新的同勘（接口版本 $api）" }
            val id = o.str("id") ?: throw IllegalArgumentException("插件说明缺少 id")
            require(ID.matches(id)) { "插件 id「$id」只能用小写字母、数字和 _ . -" }
            val name = o.str("name")?.take(40) ?: throw IllegalArgumentException("插件说明缺少 name")
            val accepts = (o["accepts"] as? JsonArray)?.mapNotNull { e ->
                when ((e as? JsonPrimitive)?.contentOrNull) {
                    "point" -> GeometryType.POINT
                    "line" -> GeometryType.LINE
                    "polygon" -> GeometryType.POLYGON
                    else -> null
                }
            }?.toSet()?.ifEmpty { null } ?: GeometryType.entries.toSet()
            val rawParams = (o["params"] as? JsonArray).orEmpty()
            require(rawParams.size <= MAX_PARAMS) { "插件参数太多（最多 $MAX_PARAMS 个）" }
            val required = mutableSetOf<String>()
            val params = rawParams.mapIndexed { i, e ->
                val p = e as? JsonObject ?: throw IllegalArgumentException("第 ${i + 1} 个参数不是对象")
                param(p).also { if (p.bool("required") == true) required += it.key }
            }
            val dup = params.groupBy { it.key }.filterValues { it.size > 1 }.keys
            require(dup.isEmpty()) { "参数 key 重复：${dup.joinToString()}" }
            return Descriptor(
                id = id, name = name, description = o.str("description")?.take(300).orEmpty(),
                accepts = accepts, params = params, required = required,
                minFeatures = (o.int("min_features") ?: 1).coerceIn(0, 100_000),
            )
        }

        private fun param(p: JsonObject): ToolParam {
            val key = p.str("key")?.takeIf { ID.matches(it) } ?: throw IllegalArgumentException("参数缺少 key，或 key 不是小写字母和数字")
            val label = p.str("label")?.take(30) ?: key
            val help = p.str("help")?.take(120)
            return when (val type = p.str("type")) {
                "number" -> ToolParam.Number(key, label, p.num("default"), p.num("min"), p.num("max"), p.str("unit")?.take(10), help)
                "field" -> ToolParam.Field(key, label, numeric = p.bool("numeric") ?: true, help = help)
                "choice" -> {
                    val options = (p["options"] as? JsonArray).orEmpty().mapNotNull { e ->
                        val opt = e as? JsonObject ?: return@mapNotNull null
                        val v = opt.str("value") ?: return@mapNotNull null
                        ToolParam.Choice.Option(v.take(40), opt.str("label")?.take(30) ?: v)
                    }
                    require(options.isNotEmpty()) { "参数「$label」没有选项" }
                    ToolParam.Choice(key, label, options, p.str("default")?.takeIf { d -> options.any { it.value == d } } ?: options.first().value, help)
                }
                "feature" -> ToolParam.Feature(
                    key, label,
                    (p["types"] as? JsonArray)?.mapNotNull { e -> GeometryType.entries.firstOrNull { it.name.equals((e as? JsonPrimitive)?.contentOrNull, true) } }
                        ?.toSet()?.ifEmpty { null } ?: GeometryType.entries.toSet(),
                    help,
                )
                else -> throw IllegalArgumentException("参数「$label」的类型「$type」不认识")
            }
        }

        /**
         * Reads what a plugin wrote: a FeatureCollection whose `geocrew`
         * member may hold `summary`, `legend` and `error`. Output records are
         * stamped with [toolId]; their kind comes from `properties.kind` when
         * it is a point, line or polygon kind, otherwise from the geometry.
         */
        fun decodeOutput(text: String, toolId: String): ToolOutput {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw IllegalArgumentException("插件返回的不是 GeoJSON")
            val meta = root[MEMBER] as? JsonObject
            meta?.str("error")?.let { throw IllegalArgumentException("插件报错：${it.take(300)}") }
            val list = (root["features"] as? JsonArray).orEmpty()
            require(list.size <= MAX_OUTPUT_FEATURES) { "插件返回的记录太多（${list.size} 条，最多 $MAX_OUTPUT_FEATURES 条）" }
            var skipped = 0
            val features = list.mapNotNull { e ->
                val f = e as? JsonObject
                val g = runCatching { GeoJson.decodeGeometry(f?.get("geometry")) }.getOrNull()
                val type = GeometryType.of(g)
                if (f == null || g == null || type == null) {
                    skipped++
                    return@mapNotNull null
                }
                val props = (f["properties"] as? JsonObject) ?: JsonObject(emptyMap())
                val kind = props.str("kind")?.let(::FeatureKind)?.takeIf { it in KINDS[type].orEmpty() } ?: GeoJson.inferKind(g)
                OutputFeature(kind, g, props.edit {
                    remove("kind")
                    this[OutputProps.TOOL] = toolId
                })
            }
            val legend = (meta?.get("legend") as? JsonArray).orEmpty().take(MAX_LEGEND).mapNotNull { e ->
                val l = e as? JsonObject ?: return@mapNotNull null
                val c = l.str("color")?.takeIf { COLOR.matches(it) } ?: return@mapNotNull null
                LegendEntry(c, l.str("label")?.take(40).orEmpty())
            }
            val summary = buildString {
                append(meta?.str("summary")?.take(2000) ?: "插件返回 ${features.size} 条结果。")
                if (skipped > 0) append("\n有 $skipped 条结果没有可用的位置，已略过。")
            }
            return ToolOutput(features, summary, legend)
        }

        private val KINDS = mapOf(
            GeometryType.POINT to setOf(FeatureKind.POINT, FeatureKind.GEO_POINT),
            GeometryType.LINE to setOf(FeatureKind.LINE),
            GeometryType.POLYGON to setOf(FeatureKind.POLYGON),
        )

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.ifEmpty { null }
        private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    }
}
