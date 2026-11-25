package org.geocrew.core.model

/**
 * Finds the record a scanned code stands for: sample labels carry the
 * sample number (§6.4), other labels may carry a record's title (a station
 * number such as D012) or its id. Matching ignores case and surrounding
 * space; a sample number wins over a title, and a live record over a deleted one.
 */
object ScanMatch {
    fun find(features: List<GeoFeature>, text: String): GeoFeature? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val live = features.filterNot { it.deleted }
        return live.firstOrNull { f -> f.samples.any { it.no.trim().equals(t, ignoreCase = true) } }
            ?: live.firstOrNull { it.title?.trim()?.equals(t, ignoreCase = true) == true }
            ?: live.firstOrNull { it.id == t }
    }
}
