package org.geocrew.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * A sample taken at a record (protocol v0 §6.4). Several samples can hang on
 * one station (a thin section and a chemistry sample, say), so they are a
 * list on the record rather than records of their own. [raw] keeps keys
 * this version does not know about.
 */
data class Sample(
    val no: String,
    val type: String? = null,
    val note: String? = null,
    val takenAt: Instant? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    fun toJson(): JsonObject = raw.edit {
        this["no"] = no
        this["type"] = type?.takeIf { it.isNotBlank() }
        this["note"] = note?.takeIf { it.isNotBlank() }
        this["taken_at"] = takenAt?.toString()
    }

    companion object {
        fun fromJson(o: JsonObject): Sample? {
            val no = o.string("no")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return Sample(
                no = no,
                type = o.string("type")?.takeIf { it.isNotBlank() },
                note = o.string("note")?.takeIf { it.isNotBlank() },
                takenAt = o.string("taken_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
                raw = o,
            )
        }
    }
}

object SampleTypes {
    /** Common sample types in regional mapping; any other text is allowed too. */
    val common = listOf("薄片", "光片", "岩石化学", "微量元素", "同位素年龄", "单矿物", "重砂", "古生物", "土壤", "水系沉积物")

    /**
     * The next free number for a sample at a station called [base]: "D012-1",
     * "D012-2"…, skipping anything in [taken] (numbers used anywhere in the
     * project, compared without case or surrounding spaces).
     */
    fun nextNumber(base: String?, taken: Collection<String>): String {
        val stem = base?.trim()?.takeIf { it.isNotEmpty() } ?: "S"
        val used = taken.mapTo(HashSet()) { it.trim().lowercase() }
        var n = 1
        while ("$stem-$n".lowercase() in used) n++
        return "$stem-$n"
    }
}

/** The samples listed on this record, in order; entries without a number are left out. */
val GeoFeature.samples: List<Sample>
    get() = (properties[Props.SAMPLES] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(Sample::fromJson) }.orEmpty()

/** Replaces the sample list; an empty list removes the key. */
fun PropertiesBuilder.samples(list: List<Sample>) =
    put(Props.SAMPLES, if (list.isEmpty()) null else JsonArray(list.map { it.toJson() }))
