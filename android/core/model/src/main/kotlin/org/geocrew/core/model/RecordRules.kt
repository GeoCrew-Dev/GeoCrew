package org.geocrew.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a project wants its records filled in: what a geological point must
 * have, how many photos, and whether the point name is asked for. Set by
 * the leader in a workspace and shared with everyone (`record_rules`,
 * protocol §2.2), so manual entry, the assistant and the 待核对 list all
 * check the same things.
 */
@Serializable
data class RecordRules(
    /** Items a geological point must have; see [Item]. */
    val required: List<String> = emptyList(),
    /** Photos a geological point needs; 0 means none. */
    @SerialName("min_photos") val minPhotos: Int = 0,
    /** When placing a point by voice, also ask for what is not required. */
    @SerialName("ask_all") val askAll: Boolean = true,
    /** Ask to confirm or say the point name instead of taking the next number silently. */
    @SerialName("ask_name") val askName: Boolean = true,
    /**
     * Prefix for geological point names in this project, e.g. "D" or "2025D".
     * Overrides each phone's own prefix when set, so the whole team numbers alike.
     */
    @SerialName("point_prefix") val pointPrefix: String? = null,
) {
    enum class Item(val id: String, val label: String) {
        LITHOLOGY("lithology", "岩性"),
        ATTITUDE("attitude", "产状"),
        NOTE("note", "描述"),
        SAMPLE("sample", "样品"),
        ;

        companion object {
            fun of(id: String) = entries.firstOrNull { it.id == id }
        }
    }

    val items: Set<Item> get() = required.mapNotNull(Item::of).toSet()

    fun requires(i: Item) = i in items

    val active: Boolean get() = items.isNotEmpty() || minPhotos > 0 || !pointPrefix.isNullOrBlank()

    /** Kinds the rules apply to: geological points. */
    fun appliesTo(f: GeoFeature) = f.kind == FeatureKind.GEO_POINT

    /** What [f] still lacks, in words, for 待核对. */
    fun missing(f: GeoFeature): List<String> {
        if (!appliesTo(f)) return emptyList()
        return buildList {
            if (requires(Item.LITHOLOGY) && f.properties.string(Props.LITHOLOGY).isNullOrBlank()) add(Item.LITHOLOGY.label)
            if (requires(Item.ATTITUDE) && Attitude.fromProperties(f.properties) == null) add(Item.ATTITUDE.label)
            if (requires(Item.NOTE) && f.note.isNullOrBlank()) add(Item.NOTE.label)
            if (requires(Item.SAMPLE) && f.samples.isEmpty()) add(Item.SAMPLE.label)
            if (minPhotos > 0 && f.photos.size < minPhotos) add(if (minPhotos == 1) "照片" else "照片（至少 $minPhotos 张，现有 ${f.photos.size} 张）")
        }
    }

    /** The name the next geological point gets. */
    fun nextName(numbering: Numbering, titles: Iterable<String?>): String =
        pointPrefix?.trim()?.takeIf { it.isNotEmpty() }?.let { numbering.next(it, titles) }
            ?: numbering.next(FeatureKind.GEO_POINT, titles)!!

    fun clean(): RecordRules = copy(
        required = required.filter { Item.of(it) != null }.distinct(),
        minPhotos = minPhotos.coerceIn(0, 20),
        pointPrefix = pointPrefix?.trim()?.take(20)?.takeIf { it.isNotEmpty() },
    )
}

/** The next name for a record of [kind]: the project's shared point prefix first, then this phone's numbering. */
fun ProjectConfig.nextName(kind: FeatureKind, titles: Iterable<String?>): String? =
    if (kind == FeatureKind.GEO_POINT) recordRules.nextName(numbering, titles) else numbering.next(kind, titles)
