package org.geocrew.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * One analysed value from the lab: a measured number, or "below the
 * detection limit" (written `<0.5` in most result sheets).
 */
sealed interface AssayValue {
    /** The number for maps, statistics and interpolation; a value below the limit counts as half the limit. */
    val forAnalysis: Double

    fun toJson(): JsonPrimitive

    /** As a lab sheet writes it: "35.2", "1200", "<0.5". */
    val text: String get() = when (this) {
        is Measured -> plain(value)
        is BelowLimit -> "<" + plain(limit)
    }

    data class Measured(val value: Double) : AssayValue {
        override val forAnalysis get() = value
        override fun toJson() = JsonPrimitive(value)
    }

    data class BelowLimit(val limit: Double) : AssayValue {
        override val forAnalysis get() = limit / 2
        override fun toJson() = JsonPrimitive("<" + plain(limit))
    }

    companion object {
        private val number = Regex("""^[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?$""")

        /** "12.5", "1,234.5", "<0.05", "＜0.05" or "< 0.05"; anything else (blank, "-", "ND", "未检出") is no value. */
        fun parse(text: String): AssayValue? {
            val t = text.trim().replace("＜", "<").replace(",", "").replace(" ", "")
            if (t.startsWith("<")) {
                val v = t.drop(1).takeIf { number.matches(it) }?.toDouble() ?: return null
                return if (v.isFinite() && v > 0) BelowLimit(v) else null
            }
            if (!number.matches(t)) return null
            return t.toDouble().takeIf { it.isFinite() }?.let(::Measured)
        }

        fun fromJson(e: JsonElement?): AssayValue? {
            val p = e as? JsonPrimitive ?: return null
            return if (p.isString) parse(p.content) else p.doubleOrNull?.takeIf { it.isFinite() }?.let(::Measured)
        }

        internal fun plain(d: Double): String =
            if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toBigDecimal().stripTrailingZeros().toPlainString()
    }
}

/** Lab results of one sample, keyed by what was analysed ("Cu(ppm)"). */
val Sample.results: Map<String, AssayValue>
    get() = (raw["results"] as? JsonObject)?.mapNotNull { (k, v) -> AssayValue.fromJson(v)?.let { k to it } }?.toMap().orEmpty()

/** This sample with [values] merged into its results; a new value replaces the old one for the same key. */
fun Sample.withResults(values: Map<String, AssayValue>): Sample {
    val merged = (raw["results"] as? JsonObject).orEmpty() + values.mapValues { it.value.toJson() }
    return copy(raw = raw.edit { put("results", JsonObject(merged)) })
}

/** A lab result sheet read into rows: one sample number and its values per row. */
data class AssayTable(val columns: List<String>, val rows: List<Row>) {
    data class Row(val sampleNo: String, val values: Map<String, AssayValue>)
}

/**
 * What importing an [AssayTable] into a project would change: per record the
 * results of its samples, plus the record-level numbers (the mean over that
 * record's analysed samples) that maps, the legend, statistics and
 * interpolation read.
 */
data class AssayPlan(
    val records: List<Record>,
    /** Sample numbers in the sheet that no record in the project has. */
    val unmatched: List<String>,
    /** Columns written onto records. */
    val columns: List<String>,
    /** Columns left out because their name is one the app itself uses. */
    val reserved: List<String>,
) {
    data class Record(val featureId: String, val samples: Map<String, Map<String, AssayValue>>, val means: Map<String, Double>)

    val sampleCount get() = records.sumOf { it.samples.size }
    val belowLimit get() = records.sumOf { r -> r.samples.values.sumOf { v -> v.values.count { it is AssayValue.BelowLimit } } }

    companion object {
        fun of(table: AssayTable, features: List<GeoFeature>): AssayPlan {
            val reserved = table.columns.filter { it in Props.MANAGED || it.lowercase() in RESERVED }
            val columns = table.columns - reserved.toSet()
            // Sample number → the record that has it; numbers compare without case.
            val owner = HashMap<String, Pair<GeoFeature, Sample>>()
            for (f in features) if (!f.deleted) for (s in f.samples) owner.putIfAbsent(s.no.trim().lowercase(), f to s)
            val bySample = LinkedHashMap<String, Pair<GeoFeature, Map<String, AssayValue>>>()
            val unmatched = mutableListOf<String>()
            for (row in table.rows) {
                val hit = owner[row.sampleNo.trim().lowercase()]
                if (hit == null) { unmatched += row.sampleNo; continue }
                val values = row.values.filterKeys { it in columns }
                if (values.isEmpty()) continue
                // A number listed twice: the later row wins, as a re-analysis would.
                bySample[hit.second.no] = hit.first to values
            }
            val records = bySample.entries.groupBy { it.value.first.id }.map { (id, entries) ->
                val samples = entries.associate { it.key to it.value.second }
                val means = columns.mapNotNull { c ->
                    val vs = samples.values.mapNotNull { it[c]?.forAnalysis }
                    if (vs.isEmpty()) null else c to vs.average()
                }.toMap()
                Record(id, samples, means)
            }
            return AssayPlan(records, unmatched.distinct(), columns, reserved)
        }

        private val RESERVED = setOf("id", "kind", "title", "note", "geometry", "lng", "lat", "lon", "经度", "纬度")
    }
}

/** Writes one record's part of an [AssayPlan]: results onto its samples, means onto the record. */
fun PropertiesBuilder.applyAssays(current: GeoFeature, record: AssayPlan.Record) {
    samples(current.samples.map { s -> record.samples[s.no]?.let(s::withResults) ?: s })
    for ((k, v) in record.means) this[k] = v
}
